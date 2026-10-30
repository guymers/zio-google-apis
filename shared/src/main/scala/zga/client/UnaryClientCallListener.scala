package zga.client

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status
import zga.error.Error
import zio.Cause
import zio.Exit
import zio.Promise
import zio.Ref
import zio.Runtime
import zio.Trace
import zio.Unsafe
import zio.ZIO

// ported from `zio_grpc` v0.6.3, with two intentional differences:
// - a successful call yields only its response message, dropping the response headers and trailers that upstream carries in `ResponseContext`
// - a protocol error fails the call straight away, rather than cancelling it and waiting for the `CANCELLED` status
// https://github.com/scalapb/zio-grpc/blob/v0.6.3/core/src/main/scalajvm/scalapb/zio_grpc/client/UnaryClientCallListener.scala

sealed trait UnaryCallState[+Res]
object UnaryCallState {
  case object Initial extends UnaryCallState[Nothing]
  case class HeadersReceived[Res](headers: Metadata) extends UnaryCallState[Res]
  case class ResponseReceived[Res](headers: Metadata, message: Res) extends UnaryCallState[Res]
  case class Failure(s: String) extends UnaryCallState[Nothing]
}

class UnaryClientCallListener[Res] private[client] (
  runtime: Runtime[Any],
  state: Ref[UnaryCallState[Res]],
  promise: Promise[Error, (Metadata, Res)],
) extends ClientCall.Listener[Res] {
  import UnaryCallState.*

  override def onHeaders(headers: Metadata) = update {
    case Initial => HeadersReceived(headers)
    case HeadersReceived(_) => Failure("onHeaders already called")
    case ResponseReceived(_, _) => Failure("onHeaders already called")
    case f @ Failure(_) => f
  }

  override def onMessage(message: Res) = update {
    case Initial => Failure("onMessage called before onHeaders")
    case HeadersReceived(headers) => ResponseReceived(headers, message)
    case ResponseReceived(_, _) => Failure("onMessage called more than once for unary call")
    case f @ Failure(_) => f
  }

  // Fail on the first protocol error, as only 2 messages are requested and the
  // call would otherwise stall until its deadline. Ending the call cancels it.
  private def update(f: UnaryCallState[Res] => UnaryCallState[Res]) = run(Error.emptyMetadata) {
    state.modify { current =>
      val next = f(current)
      val fail = (current, next) match {
        case (Failure(_), _) => ZIO.unit
        case (_, Failure(msg)) => promise.failCause(Error.fromStatusAndTrailers(Status.INTERNAL.withDescription(msg), Error.emptyMetadata)).unit
        case _ => ZIO.unit
      }
      (fail, next)
    }.flatten
  }

  // does nothing after a protocol error, as that has already failed the call
  override def onClose(status: Status, trailers: Metadata) = run(trailers) {
    for {
      s <- state.get
      result = s match {
        case _ if !status.isOk => Left(status)
        case ResponseReceived(headers, message) => Right((headers, message))
        case _ => Left(Status.INTERNAL.withDescription("No data received"))
      }
      exit = result match {
        case Left(status_) => Exit.failCause(Error.fromStatusAndTrailers(status_, trailers))
        case Right(value) => Exit.succeed(value)
      }
      _ <- promise.done(exit).unit
    } yield ()
  }

  private def run(trailers: Metadata)(effect: => ZIO[Any, Nothing, Unit]): Unit = Unsafe.unsafe { unsafe ?=>
    runtime.unsafe.run[Nothing, Unit] {
      effect.catchAllCause { cause =>
        promise.done(Exit.failCause(Cause.fail(CallbackFailure(cause, trailers)))).unit
      }
    }.getOrThrow()
  }

  private[client] def result(using Trace): ZIO[Any, Error, (Metadata, Res)] = promise.await
}

object UnaryClientCallListener {

  private[client] def make[Res](using Trace): ZIO[Any, Nothing, UnaryClientCallListener[Res]] = for {
    runtime <- ZIO.runtime[Any]
    state <- Ref.make[UnaryCallState[Res]](UnaryCallState.Initial)
    promise <- Promise.make[Error, (Metadata, Res)]
  } yield new UnaryClientCallListener[Res](runtime, state, promise)
}
