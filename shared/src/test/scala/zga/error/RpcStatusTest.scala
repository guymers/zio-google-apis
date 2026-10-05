package zga.error

import com.google.protobuf.ByteString
import io.grpc.Metadata
import io.grpc.Status
import zga.common.MessageCodec
import zio.Chunk
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object RpcStatusTest extends ZIOSpecDefault {

  private val statusKey = Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)

  private val quotaFailure = zga.google.rpc.QuotaFailure(
    violations = Chunk(
      zga.google.rpc.QuotaFailure.Violation(subject = "project:test", description = "quota exceeded"),
    ),
  )

  private val quotaFailureDetails = RpcStatus.Details.QuotaFailure(
    Chunk(RpcStatus.Details.QuotaFailure.Violation(subject = "project:test", description = "quota exceeded")),
  )

  override val spec = suite("RpcStatus")(
    test("parses details from the generated google.rpc messages") {
      val status = zga.google.rpc.Status(
        code = 8,
        message = "resource exhausted",
        details = Chunk(pack("type.googleapis.com/google.rpc.QuotaFailure", quotaFailure)),
      )

      val expected = RpcStatus(
        code = 8,
        message = "resource exhausted",
        details = Chunk(quotaFailureDetails),
        unparseableDetails = Chunk.empty,
      )

      assertTrue(RpcStatus.fromTrailers(trailers(status)) == Some(expected))
    },
    test("matches a type url with a custom prefix") {
      val status = zga.google.rpc.Status(
        code = 8,
        message = "resource exhausted",
        details = Chunk(pack("https://example.com/google.rpc.QuotaFailure", quotaFailure)),
      )

      assertTrue(RpcStatus.fromTrailers(trailers(status)).map(_.details) == Some(Chunk(quotaFailureDetails)))
    },
    test("matches a type url with no prefix") {
      val status = zga.google.rpc.Status(
        code = 8,
        message = "resource exhausted",
        details = Chunk(pack("google.rpc.QuotaFailure", quotaFailure)),
      )

      assertTrue(RpcStatus.fromTrailers(trailers(status)).map(_.details) == Some(Chunk(quotaFailureDetails)))
    },
    test("keeps details it cannot parse") {
      val status = zga.google.rpc.Status(
        code = 3,
        message = "invalid",
        details = Chunk(zga.google.protobuf.Any(typeUrl = "type.googleapis.com/example.Unknown", value = ByteString.EMPTY)),
      )

      val rpcStatus = RpcStatus.fromTrailers(trailers(status))

      assertTrue(rpcStatus.map(_.details) == Some(Chunk.empty)) &&
      assertTrue(rpcStatus.map(_.unparseableDetails.length) == Some(1))
    },
    test("keeps a detail that cannot be converted without failing the whole status") {
      // `Long.MaxValue` seconds with a full second of nanos overflows the
      // seconds arithmetic when the delay is converted to a `java.time.Duration`
      val retryInfo = zga.google.rpc.RetryInfo(
        retryDelay = Some(zga.google.protobuf.Duration(seconds = Long.MaxValue, nanos = 1000000000)),
      )
      val status = zga.google.rpc.Status(
        code = 8,
        message = "resource exhausted",
        details = Chunk(pack("type.googleapis.com/google.rpc.RetryInfo", retryInfo)),
      )

      val rpcStatus = RpcStatus.fromTrailers(trailers(status))

      assertTrue(rpcStatus.map(_.details) == Some(Chunk.empty)) &&
      assertTrue(rpcStatus.map(_.unparseableDetails.length) == Some(1))
    },
    test("ignores a malformed status header") {
      val trailers = new Metadata()
      trailers.put(statusKey, Array[Byte](0x08))

      assertTrue(RpcStatus.fromTrailers(trailers).isEmpty)
    },
    test("has no status when the header is absent") {
      assertTrue(RpcStatus.fromTrailers(new Metadata()).isEmpty)
    },
    test("exposes trailers and details on the base error type") {
      val status = zga.google.rpc.Status(
        code = 8,
        message = "resource exhausted",
        details = Chunk(pack("type.googleapis.com/google.rpc.QuotaFailure", quotaFailure)),
      )
      val rpcTrailers = trailers(status)

      val error = Error.fromStatusAndTrailers(Status.RESOURCE_EXHAUSTED, rpcTrailers).failureOption

      assertTrue(error.map(_.code) == Some(Status.Code.RESOURCE_EXHAUSTED)) &&
      assertTrue(error.map(_.details) == Some(Chunk(quotaFailureDetails))) &&
      assertTrue(error.map(_.unparseableDetails) == Some(Chunk.empty)) &&
      assertTrue(error.exists(_.trailers.get(statusKey) != null))
    },
  )

  private def pack[A](typeUrl: String, value: A)(using messageCodec: MessageCodec[A]) =
    zga.google.protobuf.Any(typeUrl = typeUrl, value = ByteString.copyFrom(messageCodec.toByteArray(value)))

  private def trailers(status: zga.google.rpc.Status) = {
    val trailers = new Metadata()
    trailers.put(statusKey, summon[MessageCodec[zga.google.rpc.Status]].toByteArray(status))
    trailers
  }
}
