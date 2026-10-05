package zga.client

import io.grpc.Status
import zio.Trace
import zio.ZIO

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Request-side flow control: lets a sender wait for a call's `Listener.onReady`
 * instead of letting gRPC buffer every message it is given.
 *
 * `onReady` is called on a gRPC transport thread, so the signal is a
 * [[java.util.concurrent.CompletableFuture]] that the callback completes
 * directly.
 */
private[client] final class ReadySignal private (
  ref: AtomicReference[CompletableFuture[Unit]],
) {

  /**
   * Wakes every fiber waiting in [[awaitReady]]. Called from `onReady`.
   */
  def signal(): Unit = {
    val signalled = ref.getAndSet(new CompletableFuture[Unit]())
    val _ = signalled.complete(())
  }

  /**
   * Waits until the call can accept a message without buffering it.
   */
  def awaitReady(call: ZClientCall[?, ?])(using Trace): ZIO[Any, Status, Unit] = for {
    signalled <- ZIO.succeed(ref.get())
    ready <- call.isReady
    _ <- if (ready) ZIO.unit else ZIO.fromCompletionStage(signalled).orDie *> awaitReady(call)
  } yield ()
}

private[client] object ReadySignal {

  def make(using Trace): ZIO[Any, Nothing, ReadySignal] = ZIO.succeed {
    new ReadySignal(new AtomicReference(new CompletableFuture[Unit]()))
  }
}
