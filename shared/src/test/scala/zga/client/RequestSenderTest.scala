package zga.client

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status
import zio.Promise
import zio.Runtime
import zio.Unsafe
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object RequestSenderTest extends ZIOSpecDefault {

  override val spec = suite("RequestSender")(
    test("an interrupted wait for the call to be ready does not interrupt the next wait") {
      val call = new FakeClientCall[String, String](Nil, initiallyReady = false)
      val zcall = ZClientCall(call)
      for {
        sender <- RequestSender.make(zcall)
        headers <- SafeMetadata.make
        _ <- zcall.start(sender.listener(new ClientCall.Listener[String] {}), headers)
        first <- sender.send("a").fork
        _ <- first.status.repeatUntil(_.isSuspended)
        _ <- first.interrupt
        second <- sender.send("b").fork
        _ <- ZIO.yieldNow.repeatUntil(_ => call.readyChecks > 1)
        _ <- ZIO.succeed(call.makeReady())
        open <- second.join
      } yield {
        assertTrue(open) &&
        assertTrue(call.sent == List("b"))
      }
    },
    test("stops sending once the call has closed, before the listener sees the close") {
      val call = new FakeClientCall[String, String](Nil, respondOn = FakeClientCall.RespondOn.Start)
      val zcall = ZClientCall(call)
      for {
        sender <- RequestSender.make(zcall)
        sentOnClose <- Promise.make[Status, Boolean]
        headers <- SafeMetadata.make
        listener = new ClientCall.Listener[String] {
          override def onClose(status: Status, trailers: Metadata) = Unsafe.unsafe { unsafe ?=>
            val _ = Runtime.default.unsafe.run(sender.send("a").intoPromise(sentOnClose)).getOrThrowFiberFailure()
          }
        }
        _ <- zcall.start(sender.listener(listener), headers)
        open <- sentOnClose.await
      } yield {
        assertTrue(!open) &&
        assertTrue(call.sent.isEmpty)
      }
    },
  ) @@ TestAspect.timeout(15.seconds)
}
