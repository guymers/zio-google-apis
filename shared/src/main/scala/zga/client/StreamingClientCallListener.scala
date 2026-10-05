package zga.client

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status
import zga.error.Error
import zio.Cause
import zio.Queue
import zio.Runtime
import zio.Trace
import zio.Unsafe
import zio.ZIO
import zio.stream.Take
import zio.stream.ZStream

// ported from `zio_grpc` v0.6.3, with two intentional differences:
// - the stream carries only response messages, dropping the `ResponseFrame` headers and trailers of a successful call
// - the response window is a fixed one message instead of upstream's configurable `prefetch`
// https://github.com/scalapb/zio-grpc/blob/v0.6.3/core/src/main/scalajvm/scalapb/zio_grpc/client/StreamingClientCallListener.scala

class StreamingClientCallListener[Res] private[client] (
  runtime: Runtime[Any],
  ready: ReadySignal,
  queue: Queue[Take[Error, Res]],
) extends ClientCall.Listener[Res] {

  override def onMessage(message: Res) = offer(Take.single(message))

  override def onClose(status: Status, trailers: Metadata) = offer {
    if (status.isOk) {
      Take.end
    } else {
      Take.failCause(Error.fromStatusAndTrailers(status, trailers))
    }
  }

  // `offer` on a queue that has been shutdown returns an interrupted cause,
  // ignore it to avoid throwing inside the gRPC callback
  private def offer(take: => Take[Error, Res]) = Unsafe.unsafe { unsafe ?=>
    runtime.unsafe.run[Nothing, Unit] {
      ZIO.succeed(take).flatMap(queue.offer(_)).unit.catchAllCause { cause =>
        if (cause.isInterruptedOnly) {
          ZIO.unit
        } else {
          queue.offer(Take.failCause(Cause.fail(CallbackFailure(cause, Error.emptyMetadata)))).unit.catchAllCause(_ => ZIO.unit)
        }
      }
    }.getOrThrow()
  }

  override def onReady() = ready.signal()

  def stream(using Trace): ZStream[Any, Error, Res] = ZStream.fromQueueWithShutdown(queue).flattenTake

}

object StreamingClientCallListener {

  private[client] def make[Res](
    ready: ReadySignal,
  )(using Trace): ZIO[Any, Nothing, StreamingClientCallListener[Res]] = for {
    runtime <- ZIO.runtime[Any]
    queue <- Queue.unbounded[Take[Error, Res]]
  } yield new StreamingClientCallListener(runtime, ready, queue)
}
