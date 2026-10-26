package zga.client

import io.grpc.Status
import zio.Trace
import zio.ZIO

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Request-side flow control: lets a sender wait for a call's `Listener.onReady`
 * instead of letting gRPC buffer every message it is given.
 *
 * `onReady` and `onClose` are called on a gRPC transport thread, so the signal
 * is a [[java.util.concurrent.CompletableFuture]] that the callbacks complete
 * directly.
 */
private[client] final class ReadySignal private (
  ref: AtomicReference[CompletableFuture[Unit]],
  closed: AtomicBoolean,
) {

  /**
   * Wakes every fiber waiting in [[awaitReady]]. Called from `onReady`.
   */
  def signal(): Unit = {
    val signalled = ref.getAndSet(new CompletableFuture[Unit]())
    val _ = signalled.complete(())
  }

  /**
   * Wakes every fiber waiting in [[awaitReady]], and stops any later wait, as a
   * closed call never becomes ready again. Called from `onClose`, and before
   * cancelling the call, as gRPC rejects any message sent after that.
   */
  def close(): Unit = {
    closed.set(true)
    signal()
  }

  /** Whether [[close]] has been called. */
  def isClosed(using Trace): ZIO[Any, Nothing, Boolean] = ZIO.succeed(closed.get())

  /**
   * Waits until the call can accept a message without buffering it. Returns
   * `false`, without waiting, once the call has closed.
   */
  def awaitReady(call: ZClientCall[?, ?])(using Trace): ZIO[Any, Status, Boolean] = for {
    signalled <- ZIO.succeed(ref.get())
    open <- ZIO.succeed(!closed.get())
    ready <- if (open) call.isReady else ZIO.succeed(false)
    result <- if (ready || !open) ZIO.succeed(open) else ZIO.fromCompletionStage(signalled).orDie *> awaitReady(call)
  } yield result
}

private[client] object ReadySignal {

  def make(using Trace): ZIO[Any, Nothing, ReadySignal] = ZIO.succeed {
    new ReadySignal(new AtomicReference(new CompletableFuture[Unit]()), new AtomicBoolean(false))
  }
}
