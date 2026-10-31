package zga.client

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.MethodDescriptor
import io.grpc.Status
import zga.error.Error
import zga.error.syntax.*
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.stream.ZStream

// ported from `zio_grpc` v0.6.3, and handles backpressure on both the request and the response side
//
// https://github.com/scalapb/zio-grpc/blob/v0.6.3/core/src/main/scalajvm/scalapb/zio_grpc/client/ClientCalls.scala
//
// A call can run in an uninterruptible region. Sending, and waiting for a
// response, are interruptible regardless, so that a call stops once it fails or
// has its result. Only pulling a request keeps the interruptibility of the call,
// so in an uninterruptible region a request being pulled is left to finish, and
// sending stops before the next. A call therefore does not end while a request is
// being pulled, and in an uninterruptible region the requests must not wait on
// something that only ending the call would provide, such as the responses
// being consumed to the end. Interruptibility is changed only within the
// fibers a call forks, as an uninterruptible caller can have an interruption
// pending, which the caller's fiber would act on in an interruptible region.

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
    response <- unaryResponseCall(call, metadata, Requests.One(req))
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
      streamingResponseCall(call, metadata, Requests.One(req))
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
    response <- unaryResponseCall(call, metadata, Requests.Many(requests))
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
      streamingResponseCall(call, metadata, Requests.Many(requests))
    }
  }

  private enum Requests[-R, +Req] {
    // sent without waiting for the call to be ready, as it is the only one
    case One[+Req](req: Req) extends Requests[Any, Req]
    case Many[-R, +Req](requests: ZStream[R, Status, Req]) extends Requests[R, Req]
  }

  // Starts the call, then sends the requests and half-closes it. Everything is
  // interruptible except pulling a request, see the top of this file.
  private def sendRequests[R, Req, Res](
    call: ZClientCall[Req, Res],
    listener: ClientCall.Listener[Res],
    headers: SafeMetadata,
    responses: Int,
    requests: Requests[R, Req],
  )(using Trace): ZIO[R, Status, Unit] = RequestSender.make(call).flatMap { sender =>
    ZIO.interruptibleMask { restore =>
      call.start(sender.listener(listener), headers) *>
        call.request(responses) *>
        (requests match {
          case Requests.One(req) => sender.sendNow(req)
          case Requests.Many(req) => sendAll(sender, restore, req)
        }) *>
        sender.halfClose
    }
  }

  // Sends the requests until they end or the call closes, without pulling
  // another request once it has closed. The requests are pulled rather than
  // run, as running a stream forks a fiber that the interruption cannot reach.
  // `restore` gives pulling the interruptibility of the call.
  private def sendAll[R, Req](
    sender: RequestSender[Req],
    restore: ZIO.InterruptibilityRestorer,
    req: ZStream[R, Status, Req],
  )(using Trace): ZIO[R, Status, Unit] = ZIO.scoped[R] {
    restore(req.toPull).flatMap { pull =>
      def loop: ZIO[R, Status, Unit] = restore(pull.unsome).flatMap {
        case Some(messages) => ZIO.forall(messages)(sender.send).flatMap(ZIO.whenDiscard(_)(loop))
        case None => ZIO.unit
      }
      loop
    }
  }

  private def unaryResponseCall[R, Req, Res](
    call: ZClientCall[Req, Res],
    headers: SafeMetadata,
    requests: Requests[R, Req],
  )(using Trace): ZIO[R, Error, Res] = for {
    listener <- UnaryClientCallListener.make[Res]
    // request 2 so that a server that sends more than one response is
    // able to deliver it, letting the listener reject the protocol error
    send = sendRequests(call, listener, headers, 2, requests)
    // interruptible even in an uninterruptible region, see the top of this file
    result = listener.result.map(_._2).interruptible
    // the server can close the call before every request is sent, for
    // example to reject it, so sending stops as soon as there is a result
    response <- (send.handleStatus *> result).raceFirst(result)
  } yield response

  private def streamingResponseCall[R, Req, Res](
    call: ZClientCall[Req, Res],
    headers: SafeMetadata,
    requests: Requests[R, Req],
  )(using Trace): ZStream[R, Error, Res] = ZStream.unwrap(StreamingClientCallListener.make[Res].map { listener =>
    // the responses end when the server closes the call, even if requests are still being sent
    ZStream
      .fromZIO(sendRequests(call, listener, headers, 1, requests))
      .handleStatus
      .drain
      .merge(listener.stream.tap(_ => call.request(1).handleStatus), ZStream.HaltStrategy.Right)
  })
}
