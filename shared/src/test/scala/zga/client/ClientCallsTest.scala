package zga.client

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import zga.error.Error
import zio.Chunk
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
          response <- ClientCalls.unary(channel(call), method, CallOptions.DEFAULT, headers, "request")
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
          exit <- ClientCalls.unary(channel(call), method, CallOptions.DEFAULT, headers, "request").exit
        } yield {
          assertErrorIs[Error.Internal](exit) &&
          assertTrue(call.cancelled.contains("onMessage called more than once for unary call"))
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
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, headers, ZStream("a", "b"))
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
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, headers, ZStream.never)
            .exit
        } yield {
          assertErrorIs[Error.PermissionDenied](exit)
        }
      } ::
      test("fails when the request stream fails") {
        val call = new FakeClientCall[String, String](List("response"))
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .clientStreaming(channel(call), method, CallOptions.DEFAULT, headers, req)
            .exit
        } yield {
          assertErrorIs[Error.Aborted](exit)
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
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, headers, "request")
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
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, headers, "request")
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
            .serverStreaming(channel(call), method, CallOptions.DEFAULT, headers, "request")
            .runCollect
            .exit
        } yield {
          assertTrue(exit.isFailure) &&
          assertTrue(call.cancelled == List("ZIO Failure"))
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
            .bidirectional(channel(call), method, CallOptions.DEFAULT, headers, ZStream("a", "b"))
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
            .bidirectional(channel(call), method, CallOptions.DEFAULT, headers, ZStream.never)
            .runCollect
        } yield {
          assertTrue(values == Chunk("first", "second"))
        }
      } ::
      test("fails when the request stream fails") {
        val call = new FakeClientCall[String, String](Nil)
        val req: ZStream[Any, Status, String] = ZStream.fail(Status.ABORTED.withDescription("request stream failed"))
        for {
          headers <- SafeMetadata.make
          exit <- ClientCalls
            .bidirectional(channel(call), method, CallOptions.DEFAULT, headers, req)
            .runCollect
            .exit
        } yield {
          assertErrorIs[Error.Aborted](exit)
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
