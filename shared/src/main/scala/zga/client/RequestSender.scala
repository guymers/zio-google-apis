package zga.client

import io.grpc.ClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.Status
import zio.Trace
import zio.ZIO

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The request side of a call: sends messages as the call becomes ready, instead
 * of letting gRPC buffer every message it is given, and stops sending once the
 * call has closed.
 *
 * `onReady` and `onClose` are called on a gRPC transport thread, so the signal
 * is a [[java.util.concurrent.CompletableFuture]] that the callbacks complete
 * directly.
 */
private[client] final class RequestSender[Req] private (
  call: ZClientCall[Req, ?],
  ref: AtomicReference[CompletableFuture[Unit]],
  closed: AtomicBoolean,
) {

  /**
   * Wraps `delegate` so that this sender sees the call's `onReady` and
   * `onClose`. Sending stops before `delegate` sees the close.
   */
  def listener[Res](delegate: ClientCall.Listener[Res]): ClientCall.Listener[Res] = {
    new ForwardingClientCallListener.SimpleForwardingClientCallListener[Res](delegate) {
      override def onReady() = signal()

      override def onClose(status: Status, trailers: Metadata) = {
        closed.set(true)
        // a closed call never becomes ready again, so wake every fiber waiting for it
        signal()
        super.onClose(status, trailers)
      }
    }
  }

  /**
   * Waits until the call can accept `message` without buffering it, then sends
   * it. Returns whether the call is still open.
   */
  def send(message: Req)(using Trace): ZIO[Any, Status, Boolean] = {
    ZIO.ifZIO(awaitReady)(whileOpen(call.sendMessage(message)) *> isClosed.negate, ZIO.succeed(false))
  }

  /**
   * Sends `message` without waiting for the call to be ready, for a call that
   * sends exactly one message.
   */
  def sendNow(message: Req)(using Trace): ZIO[Any, Status, Unit] = whileOpen(call.sendMessage(message))

  def halfClose(using Trace): ZIO[Any, Status, Unit] = whileOpen(call.halfClose())

  private def signal(): Unit = {
    val signalled = ref.getAndSet(new CompletableFuture[Unit]())
    val _ = signalled.complete(())
  }

  // returns `false` immediately once the call has closed
  private def awaitReady(using Trace): ZIO[Any, Status, Boolean] = for {
    // taken before checking, so a signal sent after the check is not missed
    signalled <- ZIO.succeed(ref.get())
    closed <- isClosed
    result <- {
      if (closed) ZIO.succeed(false)
      // a copy, as interrupting the wait cancels the future it waits on
      else ZIO.ifZIO(call.isReady)(ZIO.succeed(true), ZIO.fromCompletionStage(signalled.copy()).orDie *> awaitReady)
    }
  } yield result

  private def whileOpen(effect: ZIO[Any, Status, Unit])(using Trace) = ZIO.unlessZIODiscard(isClosed)(effect)

  private def isClosed(using Trace) = ZIO.succeed(closed.get())
}

private[client] object RequestSender {

  def make[Req](call: ZClientCall[Req, ?])(using Trace): ZIO[Any, Nothing, RequestSender[Req]] = ZIO.succeed {
    new RequestSender(call, new AtomicReference(new CompletableFuture[Unit]()), new AtomicBoolean(false))
  }
}
