package zga.client

import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.Status
import zga.error.Error
import zga.error.syntax.*
import zio.Chunk
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.stream.ZStream

// ported from `zio_grpc` v0.6.3, and handles backpressure on both the request and the response side
//
// https://github.com/scalapb/zio-grpc/blob/v0.6.3/core/src/main/scalajvm/scalapb/zio_grpc/client/ClientCalls.scala

object ClientCalls {

  private[client] def newCall[Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
  )(using Trace): ZIO[Scope, Nothing, ZClientCall[Req, Res]] = {
    val call = channel.newCall(method, options)
    ZIO.acquireReleaseExit(call)((call, exit) => {
      val message = exit.causeOption match {
        case Some(cause) if cause.isInterrupted => "ZIO Interrupted"
        case Some(cause) if cause.isDie => "ZIO Died"
        case Some(_) => "ZIO Failure"
        case None => "ZIO Success"
      }
      call.cancel(message).ignore
    })
  }

  def unary[Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: ZIO[Any, Status, SafeMetadata],
    routing: List[Routing.Parameter[Req]],
    req: Req,
  )(using Trace): ZIO[Any, Error, Res] = ZIO.scoped[Any](for {
    metadata <- headers.handleStatus
    _ <- Routing.setRequestParams(metadata, req, routing)
    call <- newCall(channel, method, options)
    // a call that sends exactly one message does not wait for `onReady`
    response <- unaryResponseCall(call, metadata, _ => call.sendMessage(req))
  } yield response)

  def serverStreaming[Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: ZIO[Any, Status, SafeMetadata],
    routing: List[Routing.Parameter[Req]],
    req: Req,
  )(using Trace): ZStream[Any, Error, Res] = {
    val start = for {
      metadata <- headers.handleStatus
      _ <- Routing.setRequestParams(metadata, req, routing)
      call <- newCall(channel, method, options)
    } yield (metadata, call)
    ZStream.scoped[Any](start).flatMap { (metadata, call) =>
      streamingResponseCall(call, metadata, _ => call.sendMessage(req))
    }
  }

  def clientStreaming[R, Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: ZIO[Any, Status, SafeMetadata],
    routing: List[Routing.Parameter[Req]],
    req: ZStream[R, Status, Req],
  )(using Trace): ZIO[R, Error, Res] = ZIO.scoped[R](for {
    (metadata, requests) <- Routing.setFirstRequestParams(headers, req, routing, Option(options.getDeadline)).handleStatus
    call <- newCall(channel, method, options)
    response <- unaryResponseCall(call, metadata, sendAll(call, _, requests))
  } yield response)

  def bidirectional[R, Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: ZIO[Any, Status, SafeMetadata],
    routing: List[Routing.Parameter[Req]],
    req: ZStream[R, Status, Req],
  )(using Trace): ZStream[R, Error, Res] = {
    val start = for {
      (metadata, requests) <- Routing.setFirstRequestParams(headers, req, routing, Option(options.getDeadline)).handleStatus
      call <- newCall(channel, method, options)
    } yield (metadata, requests, call)
    ZStream.scoped[R](start).flatMap { (metadata, requests, call) =>
      streamingResponseCall(call, metadata, sendAll(call, _, requests))
    }
  }

  // Stops pulling the requests once the call has closed, as nothing more can be
  // sent. Sending runs in a fiber of its own, so waiting to send is
  // interruptible even in an uninterruptible region, letting the call stop
  // sending at the next request without interrupting the requests themselves.
  // The requests are pulled rather than run, as running a stream runs it in
  // another fiber, which interrupting this one would not reach.
  private def sendAll[R, Req](
    call: ZClientCall[Req, ?],
    ready: ReadySignal,
    req: ZStream[R, Status, Req],
  )(using Trace): ZIO[R, Status, Unit] = ZIO.scoped[R] {
    req.toPull.flatMap { pull =>
      def send(messages: Chunk[Req]) = {
        ZIO.forall(messages)(message => ready.awaitReady(call).interruptible.tap(ZIO.when(_)(call.sendMessage(message))))
      }
      def loop: ZIO[R, Status, Unit] = pull.foldZIO(
        _.fold(ZIO.unit)(ZIO.fail(_)),
        messages => send(messages).flatMap(open => if (open) loop else ZIO.unit),
      )
      loop
    }
  }

  // a closed call has nothing to half-close, and gRPC rejects it once the call is cancelled
  private def halfClose(call: ZClientCall[?, ?], ready: ReadySignal)(using Trace): ZIO[Any, Status, Unit] = {
    ZIO.unlessZIODiscard(ready.isClosed)(call.halfClose())
  }

  private def unaryResponseCall[R, Req, Res](
    call: ZClientCall[Req, Res],
    headers: SafeMetadata,
    sendMessages: ReadySignal => ZIO[R, Status, Unit],
  )(using Trace): ZIO[R, Error, Res] = for {
    ready <- ReadySignal.make
    listener <- UnaryClientCallListener.make(call, ready)
    send =
      call.start(listener, headers) *>
        // request 2 so that a server that sends more than one response is
        // able to deliver it, letting the listener reject the protocol error
        call.request(2) *>
        sendMessages(ready) *>
        halfClose(call, ready)
    // interruptible even in an uninterruptible region, so that a failure to
    // send does not wait for a result that the server is yet to send
    result = listener.result.map(_._2).interruptible
    // the server can close the call before every request is sent, for
    // example to reject it, so sending stops as soon as there is a result:
    // it is interrupted, or in an uninterruptible region, where the requests
    // must not be interrupted, it stops at the next request
    response <- (send.handleStatus *> result).raceFirst(result)
  } yield response

  private def streamingResponseCall[R, Req, Res](
    call: ZClientCall[Req, Res],
    headers: SafeMetadata,
    sendMessages: ReadySignal => ZIO[R, Status, Unit],
  )(using Trace): ZStream[R, Error, Res] = {
    ZStream.fromZIO(ReadySignal.make.flatMap(ready => StreamingClientCallListener.make[Res](ready).map(ready -> _))).flatMap {
      case (ready, listener) =>
        val send =
          call.start(listener, headers) *>
            call.request(1) *>
            sendMessages(ready) *>
            halfClose(call, ready)

        // the responses end when the server closes the call, even if requests are still being sent
        ZStream
          .fromZIO(send)
          .handleStatus
          .drain
          .merge(listener.stream.tap(_ => call.request(1).handleStatus), ZStream.HaltStrategy.Right)
    }
  }
}
