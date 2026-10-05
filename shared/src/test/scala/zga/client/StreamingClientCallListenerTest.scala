package zga.client

import io.grpc.Metadata
import io.grpc.Status
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object StreamingClientCallListenerTest extends ZIOSpecDefault {

  override val spec = suite("StreamingClientCallListener")(
    test("ignores callbacks after its stream is shut down") {
      for {
        ready <- ReadySignal.make
        listener <- StreamingClientCallListener.make[String](ready)
        _ <- ZIO.succeed(listener.onMessage("first"))
        // ending the stream early shuts its queue down
        _ <- listener.stream.take(1).runDrain
        exit <- ZIO.attempt {
          listener.onMessage("late")
          listener.onClose(Status.OK, new Metadata)
        }.exit
      } yield {
        assertTrue(exit.isSuccess)
      }
    },
  ) @@ TestAspect.timeout(15.seconds)
}
