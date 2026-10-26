package zga.client

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import zga.error.Error
import zio.Chunk
import zio.Exit
import zio.Promise
import zio.ZIO
import zio.durationInt
import zio.stream.ZStream
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object ClientCallsTest extends ZIOSpecDefault {

  private val headerKey = Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)

  private val nameRouting = List(Routing.Parameter[String](request => List("name" -> request)))

  override val spec = suite("ClientCalls")(
    suite("newCall") {
      val method = methodDescriptor(MethodDescriptor.MethodType.UNARY)

      test("cancels with an interruption message when the call is interrupted") {
        val call = new FakeClientCall[String, String](Nil)
        for {
          acquired <- Promise.make[Nothing, Unit]
          fiber <- ZIO.scoped {
            ClientCalls.newCall(channel(call), method, CallOptions.DEFAULT) *>
              acquired.succeed(()) *>
              ZIO.never
          }.fork
          _ <- acquired.await
          _ <- fiber.interrupt
        } yield {
          assertTrue(call.cancelled == List("ZIO Interrupted"))
        }
      } ::
      test("cancels with a defect message when the call dies") {
        val call = new FakeClientCall[String, String](Nil)
        for {
          exit <- ZIO.scoped {
            ClientCalls.newCall(channel(call), method, CallOptions.DEFAULT) *>
              ZIO.die(new RuntimeException("boom"))
          }.exit
        } yield {
          assertTrue(exit.isFailure) &&
          assertTrue(call.cancelled == List("ZIO Died"))
        }
      } :: Nil
    },
    suite("unary") {
      val method = methodDescriptor(MethodDescriptor.MethodType.UNARY)

      test("success") {
        val call = new FakeClientCall[String, String](List("response"))
        for {
          headers <- SafeMetadata.make
          _ <- headers.put(headerKey, "value")
          response <- ClientCalls.unary(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, "request")
        } yield {
          assertTrue(response == "response") &&
          assertTrue(call.sent == List("request")) &&
          assertTrue(call.requests == List(2)) &&
          assertTrue(call.halfCloseCalls == 1) &&
          assertTrue(call.startHeaders.exists(_.get(headerKey) == "value"))
        }
      } ::
      test("cancels if more than one response is sent") {
        val call = new FakeClientCall[String, String](List("first", "second", "third"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls.unary(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, "request").exit
        } yield {
          assertErrorIs[Error.Internal](exit) &&
          assertTrue(call.cancelled.contains("onMessage called more than once for unary call"))
        }
      } ::
      test("sets the routing header from the request") {
        val call = new FakeClientCall[String, String](List("response"))
        for {
          headers <- SafeMetadata.make
          _ <- ClientCalls.unary(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, "request")
        } yield {
          assertTrue(call.startHeaders.exists(_.get(Routing.RequestParamsKey) == "name=request"))
        }
      } :: Nil
    },
    suite("client streaming") {
      val method = methodDescriptor(MethodDescriptor.MethodType.CLIENT_STREAMING)

      test("waits for the call to be ready before sending") {
        val call = new FakeClientCall[String, String](List("response"), initiallyReady = false)
        for {
          headers <- SafeMetadata.make
          fiber <- ClientCalls
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, ZStream("a", "b"))
            .fork
          _ <- ZIO.yieldNow.repeatUntil(_ => call.readyChecks > 0)
          sentBeforeReady = call.sent
          _ <- ZIO.succeed(call.makeReady())
          response <- fiber.join
        } yield {
          assertTrue(sentBeforeReady.isEmpty) &&
          assertTrue(call.sent == List("a", "b")) &&
          assertTrue(response == "response") &&
          assertTrue(call.halfCloseCalls == 1)
        }
      } ::
      test("returns an early server error without draining the requests") {
        val call = new FakeClientCall[String, String](Nil, Status.PERMISSION_DENIED, respondOnStart = true)
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, ZStream.never)
            .exit
        } yield {
          assertErrorIs[Error.PermissionDenied](exit)
        }
      } ::
      test("stops sending when the server closes the call in an uninterruptible region") {
        val call = new FakeClientCall[String, String](Nil, Status.PERMISSION_DENIED, respondOnStart = true, initiallyReady = false)
        for {
          released <- Promise.make[Nothing, Unit]
          req = ZStream("a", "b").ensuring(released.succeed(()))
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, req)
          }
          requestsReleased <- released.isDone
        } yield {
          assertErrorIs[Error.PermissionDenied](exit) &&
          assertTrue(call.sent.isEmpty) &&
          assertTrue(requestsReleased)
        }
      } ::
      test("neither sends nor half-closes after a protocol error cancels the call") {
        val call = new FakeClientCall[String, String](List("first", "second"), respondOnStart = true, closeOnCancel = false)
        for {
          headers <- SafeMetadata.make
          // uninterruptible, so that sending has finished when the call returns
          fiber <- runUninterruptible {
            ClientCalls.clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, ZStream("a", "b"))
          }.fork
          _ <- ZIO.yieldNow.repeatUntil(_ => call.cancelled.nonEmpty)
          _ <- ZIO.succeed(call.closeCancelled())
          exit <- fiber.join
        } yield {
          assertErrorIs[Error.Internal](exit) &&
          assertTrue(call.rejectedCalls == 0) &&
          assertTrue(call.sent.isEmpty) &&
          assertTrue(call.halfCloseCalls == 0)
        }
      } ::
      test("fails when the request stream fails") {
        val call = new FakeClientCall[String, String](List("response"))
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req)
            .exit
        } yield {
          assertErrorIs[Error.Aborted](exit)
        }
      } ::
      test("fails when the request stream fails in an uninterruptible region") {
        val call = new FakeClientCall[String, String](List("response"))
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req)
          }
        } yield {
          assertErrorIs[Error.Aborted](exit)
        }
      } ::
      test("sets the routing header from the first request") {
        val call = new FakeClientCall[String, String](List("response"))
        for {
          headers <- SafeMetadata.make
          _ <- ClientCalls.clientStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, ZStream("a", "b"))
        } yield {
          assertTrue(call.startHeaders.exists(_.get(Routing.RequestParamsKey) == "name=a")) &&
          assertTrue(call.sent == List("a", "b"))
        }
      } :: Nil
    },
    suite("server streaming") {
      val method = methodDescriptor(MethodDescriptor.MethodType.SERVER_STREAMING)

      test("drains every response of a successful call") {
        val call = new FakeClientCall[String, String](List("first", "second", "third"))
        for {
          headers <- SafeMetadata.make
          values <- ClientCalls
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, "request")
            .runCollect
        } yield {
          assertTrue(values == Chunk("first", "second", "third")) &&
          assertTrue(call.sent == List("request")) &&
          assertTrue(call.halfCloseCalls == 1) &&
          // the response window stays at a single message
          assertTrue(call.requests.nonEmpty) &&
          assertTrue(call.requests.forall(_ == 1))
        }
      } ::
      test("a partially consumed server stream cancels the call") {
        val call = new FakeClientCall[String, String](List("first", "second", "third"))
        for {
          headers <- SafeMetadata.make
          values <- ClientCalls
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, "request")
            .take(1)
            .runCollect
        } yield {
          assertTrue(values == Chunk("first")) &&
          assertTrue(call.cancelled == List("ZIO Success"))
        }
      } ::
      test("a failed call is cancelled with the failure") {
        val call = new FakeClientCall[String, String](Nil, Status.INTERNAL.withDescription("boom"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, "request")
            .runCollect
            .exit
        } yield {
          assertTrue(exit.isFailure) &&
          assertTrue(call.cancelled == List("ZIO Failure"))
        }
      } ::
      test("sets the routing header from the request") {
        val call = new FakeClientCall[String, String](List("response"))
        for {
          headers <- SafeMetadata.make
          _ <- ClientCalls.serverStreaming(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, "request").runDrain
        } yield {
          assertTrue(call.startHeaders.exists(_.get(Routing.RequestParamsKey) == "name=request"))
        }
      } :: Nil
    },
    suite("bidirectional") {
      val method = methodDescriptor(MethodDescriptor.MethodType.BIDI_STREAMING)

      test("ends when the requests complete and the responses drain") {
        val call = new FakeClientCall[String, String](List("first", "second"))
        for {
          headers <- SafeMetadata.make
          values <- ClientCalls
            .bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, ZStream("a", "b"))
            .runCollect
        } yield {
          assertTrue(values == Chunk("first", "second")) &&
          assertTrue(call.sent == List("a", "b")) &&
          assertTrue(call.halfCloseCalls == 1) &&
          assertTrue(call.requests.nonEmpty) &&
          assertTrue(call.requests.forall(_ == 1))
        }
      } ::
      test("ends when the server closes the call before the requests end") {
        val call = new FakeClientCall[String, String](List("first", "second"), respondOnStart = true)
        for {
          headers <- SafeMetadata.make
          values <- ClientCalls
            .bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, ZStream.never)
            .runCollect
        } yield {
          assertTrue(values == Chunk("first", "second"))
        }
      } ::
      test("stops sending when the server closes the call in an uninterruptible region") {
        val call = new FakeClientCall[String, String](List("first", "second"), respondOnStart = true, initiallyReady = false)
        for {
          released <- Promise.make[Nothing, Unit]
          req = ZStream("a", "b").ensuring(released.succeed(()))
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, req).runCollect
          }
          requestsReleased <- released.isDone
        } yield {
          assertTrue(exit == Exit.succeed(Chunk("first", "second"))) &&
          assertTrue(call.sent.isEmpty) &&
          assertTrue(requestsReleased)
        }
      } ::
      test("fails when the request stream fails") {
        val call = new FakeClientCall[String, String](Nil)
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req)
            .runCollect
            .exit
        } yield {
          assertErrorIs[Error.Aborted](exit)
        }
      } ::
      test("fails when the request stream fails in an uninterruptible region") {
        val call = new FakeClientCall[String, String](Nil)
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req).runCollect
          }
        } yield {
          assertErrorIs[Error.Aborted](exit)
        }
      } ::
      test("stops sending when the responses are no longer consumed in an uninterruptible region") {
        val call = new FakeClientCall[String, String](List.tabulate(10)(i => s"response-$i"), respondOnStart = true)
        val req = ZStream.iterate(0)(_ + 1).map(_.toString)
        for {
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req).take(1).runCollect
          }
        } yield {
          assertTrue(exit == Exit.succeed(Chunk("response-0"))) &&
          assertTrue(call.cancelled == List("ZIO Success"))
        }
      } ::
      test("does not interrupt a request when the responses are no longer consumed in an uninterruptible region") {
        val call = new FakeClientCall[String, String](List.tabulate(10)(i => s"response-$i"), respondOnStart = true)
        for {
          pulled <- Promise.make[Nothing, Unit]
          req = ZStream.fromZIO(ZIO.sleep(300.millis) *> pulled.succeed(()).as("a"))
          headers <- SafeMetadata.make
          exit <- runUninterruptible {
            ClientCalls.bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), Nil, req).take(1).runCollect
          }
          requestPulled <- pulled.isDone
        } yield {
          assertTrue(exit == Exit.succeed(Chunk("response-0"))) &&
          assertTrue(requestPulled) &&
          assertTrue(call.sent.isEmpty)
        }
      } ::
      test("sets the routing header from the first request") {
        val call = new FakeClientCall[String, String](List("response"))
        for {
          headers <- SafeMetadata.make
          _ <- ClientCalls.bidirectional(channel(call), method, CallOptions.DEFAULT, ZIO.succeed(headers), nameRouting, ZStream("a", "b")).runDrain
        } yield {
          assertTrue(call.startHeaders.exists(_.get(Routing.RequestParamsKey) == "name=a")) &&
          assertTrue(call.sent == List("a", "b"))
        }
      } :: Nil
    },
  ) @@ TestAspect.timeout(15.seconds)

  private lazy val marshaller = new MethodDescriptor.Marshaller[String] {
    override def stream(value: String) = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))
    override def parse(stream: InputStream) = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
  }

  private def methodDescriptor(kind: MethodDescriptor.MethodType) =
    MethodDescriptor
      .newBuilder[String, String]()
      .setType(kind)
      .setFullMethodName("test.Service/Method")
      .setRequestMarshaller(marshaller)
      .setResponseMarshaller(marshaller)
      .build()

  private def channel(call: ClientCall[?, ?]) = new ZChannel(new FakeManagedChannel(call), Nil)

  private final class FakeManagedChannel(call: ClientCall[?, ?]) extends ManagedChannel {
    override def newCall[ReqT, RespT](method: MethodDescriptor[ReqT, RespT], options: CallOptions) = call.asInstanceOf[ClientCall[ReqT, RespT]]
    override def authority() = "test"
    override def shutdown() = this
    override def shutdownNow() = this
    override def isShutdown = false
    override def isTerminated = false
    override def awaitTermination(timeout: Long, unit: TimeUnit) = true
  }
}
