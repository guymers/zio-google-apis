package zga.client

import io.grpc.Metadata
import io.grpc.Status
import zga.error.Error
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object UnaryClientCallListenerTest extends ZIOSpecDefault {

  override val spec = suite("UnaryClientCallListener")(
    test("fails on a second response without waiting for the call to close") {
      for {
        listener <- UnaryClientCallListener.make[String]
        _ <- ZIO.succeed {
          listener.onHeaders(new Metadata)
          listener.onMessage("first")
          listener.onMessage("second")
        }
        exit <- listener.result.exit
      } yield {
        assertErrorIs[Error.Internal](exit) &&
        assertTrue(exit.causeOption.flatMap(_.failureOption).flatMap(_.description).contains("onMessage called more than once for unary call"))
      }
    },
    test("a protocol error takes precedence over the status the call closes with") {
      for {
        listener <- UnaryClientCallListener.make[String]
        _ <- ZIO.succeed {
          listener.onHeaders(new Metadata)
          listener.onMessage("first")
          listener.onMessage("second")
          listener.onClose(Status.CANCELLED, new Metadata)
        }
        exit <- listener.result.exit
      } yield {
        assertErrorIs[Error.Internal](exit)
      }
    },
  ) @@ TestAspect.timeout(15.seconds)
}
