package zga.client

import io.grpc.CallOptions
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
    headers: SafeMetadata,
    req: Req,
  )(using Trace): ZIO[Any, Error, Res] = ZIO.scoped[Any] {
    // a call that sends exactly one message does not wait for `onReady`
    newCall(channel, method, options).flatMap { call =>
      unaryResponseCall(call, headers, _ => call.sendMessage(req))
    }
  }

  def serverStreaming[Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: SafeMetadata,
    req: Req,
  )(using Trace): ZStream[Any, Error, Res] = {
    ZStream.scoped[Any](newCall(channel, method, options)).flatMap { call =>
      streamingResponseCall(call, headers, _ => call.sendMessage(req))
    }
  }

  def clientStreaming[R, Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: SafeMetadata,
    req: ZStream[R, Status, Req],
  )(using Trace): ZIO[R, Error, Res] = ZIO.scoped[R] {
    newCall(channel, method, options).flatMap { call =>
      unaryResponseCall(call, headers, sendAll(call, _, req))
    }
  }

  def bidirectional[R, Req, Res](
    channel: ZChannel,
    method: MethodDescriptor[Req, Res],
    options: CallOptions,
    headers: SafeMetadata,
    req: ZStream[R, Status, Req],
  )(using Trace): ZStream[R, Error, Res] = {
    ZStream.scoped[R](newCall(channel, method, options)).flatMap { call =>
      streamingResponseCall(call, headers, sendAll(call, _, req))
    }
  }

  private def sendAll[R, Req](
    call: ZClientCall[Req, ?],
    ready: ReadySignal,
    req: ZStream[R, Status, Req],
  )(using Trace): ZIO[R, Status, Unit] = {
    req.foreach(message => ready.awaitReady(call) *> call.sendMessage(message))
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
        call.halfClose()
    result = listener.result.map(_._2)
    // the server can close the call before every request is sent, for
    // example to reject it, so sending stops as soon as there is a result
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
            call.halfClose()

        // the responses end when the server closes the call, even if requests are still being sent
        ZStream
          .fromZIO(send)
          .handleStatus
          .drain
          .merge(listener.stream.tap(_ => call.request(1).handleStatus), ZStream.HaltStrategy.Right)
    }
  }
}
