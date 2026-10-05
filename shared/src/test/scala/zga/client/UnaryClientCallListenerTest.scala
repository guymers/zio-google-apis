package zga.client

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status
import zga.error.Error
import zio.Trace
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object UnaryClientCallListenerTest extends ZIOSpecDefault {

  override val spec = suite("UnaryClientCallListener")(
    test("rejects a second response") {
      val call = new RecordingZClientCall
      for {
        ready <- ReadySignal.make
        listener <- UnaryClientCallListener.make[String](call, ready)
        _ <- ZIO.succeed {
          listener.onHeaders(new Metadata)
          listener.onMessage("first")
          listener.onMessage("second")
          listener.onClose(Status.OK, new Metadata)
        }
        exit <- listener.result.exit
      } yield {
        assertErrorIs[Error.Internal](exit) &&
        assertTrue(call.cancelled == List("onMessage called more than once for unary call"))
      }
    },
    test("fails the result when handling the callback dies") {
      val call = new DyingCancelZClientCall
      for {
        ready <- ReadySignal.make
        listener <- UnaryClientCallListener.make[String](call, ready)
        _ <- ZIO.succeed {
          listener.onHeaders(new Metadata)
          listener.onMessage("first")
          // a protocol error cancels the call, and cancelling dies
          listener.onMessage("second")
        }
        exit <- listener.result.exit
      } yield {
        assertErrorIs[Error.Internal](exit)
      }
    },
  ) @@ TestAspect.timeout(15.seconds)

  private final class RecordingZClientCall extends ZClientCall[String, String] {
    @volatile var cancelled: List[String] = Nil

    override def start(listener: ClientCall.Listener[String], headers: SafeMetadata)(using Trace) = ZIO.unit
    override def request(numMessages: Int)(using Trace) = ZIO.unit
    override def cancel(message: String)(using Trace) = ZIO.succeed { cancelled = message :: cancelled }
    override def isReady(using Trace) = ZIO.succeed(true)
    override def halfClose()(using Trace) = ZIO.unit
    override def sendMessage(message: String)(using Trace) = ZIO.unit
  }

  private final class DyingCancelZClientCall extends ZClientCall[String, String] {
    override def start(listener: ClientCall.Listener[String], headers: SafeMetadata)(using Trace) = ZIO.unit
    override def request(numMessages: Int)(using Trace) = ZIO.unit
    override def cancel(message: String)(using Trace): ZIO[Any, Status, Unit] = ZIO.die(new RuntimeException("cancel failed"))
    override def isReady(using Trace) = ZIO.succeed(true)
    override def halfClose()(using Trace) = ZIO.unit
    override def sendMessage(message: String)(using Trace) = ZIO.unit
  }
}
