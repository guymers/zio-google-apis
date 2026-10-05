package zga.error

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import zga.common.MessageCodec
import zio.Chunk
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.util.Locale

object ErrorTest extends ZIOSpecDefault {

  private val quotaFailure = zga.google.rpc.QuotaFailure(
    violations = Chunk(
      zga.google.rpc.QuotaFailure.Violation(subject = "project:test", description = "quota exceeded"),
    ),
  )

  private val quotaFailureDetails = RpcStatus.Details.QuotaFailure(
    Chunk(RpcStatus.Details.QuotaFailure.Violation(subject = "project:test", description = "quota exceeded")),
  )

  override val spec = suite("Error")(
    suite("from status")(
      test(Status.Code.OK.toString) {
        val cause = Error.fromStatus(Status.OK)
        val codes = cause.defects.collect {
          case e: StatusRuntimeException => e.getStatus.getCode
        }
        assertTrue(cause.isDie) &&
        assertTrue(codes == List(Status.Code.OK))
      } ::
      Status.Code.values().filterNot(_ == Status.Code.OK).map { code =>
        test(code.toString) {
          val cause = Error.fromStatus(code.toStatus)
          val error = cause.failureOption
          val className = error.map(_.getClass.getSimpleName)
          val expectedClassName = code.toString.split('_').map(_.toLowerCase(Locale.ROOT).capitalize).mkString

          assertTrue(error.map(_.code) == Some(code)) &&
          assertTrue(className == Some(expectedClassName))
        }
      }.toList,
    ),
    suite("from rpc status")(
      test("builds the subtype for the code and takes the message as the description") {
        val cause = Error.fromRpcStatus(zga.google.rpc.Status(code = 8, message = "resource exhausted"))
        val error = cause.failureOption

        assertTrue(error.map(_.code) == Some(Status.Code.RESOURCE_EXHAUSTED)) &&
        assertTrue(error.flatMap(_.description) == Some("resource exhausted")) &&
        assertTrue(error.map(_.getClass.getSimpleName) == Some("ResourceExhausted"))
      },
      test("has no description when the message is empty") {
        val error = Error.fromRpcStatus(zga.google.rpc.Status(code = 5)).failureOption

        assertTrue(error.map(_.code) == Some(Status.Code.NOT_FOUND)) &&
        assertTrue(error.map(_.description) == Some(None))
      },
      test("parses the details the status carries") {
        val status = zga.google.rpc.Status(
          code = 8,
          message = "resource exhausted",
          details = Chunk(pack("type.googleapis.com/google.rpc.QuotaFailure", quotaFailure)),
        )
        val error = Error.fromRpcStatus(status).failureOption

        assertTrue(error.map(_.details) == Some(Chunk(quotaFailureDetails))) &&
        assertTrue(error.map(_.unparseableDetails) == Some(Chunk.empty))
      },
      test("keeps details it cannot parse") {
        val status = zga.google.rpc.Status(
          code = 3,
          message = "invalid",
          details = Chunk(zga.google.protobuf.Any(typeUrl = "type.googleapis.com/example.Unknown", value = ByteString.EMPTY)),
        )
        val error = Error.fromRpcStatus(status).failureOption

        assertTrue(error.map(_.details) == Some(Chunk.empty)) &&
        assertTrue(error.map(_.unparseableDetails.length) == Some(1))
      },
      test("has no trailers and no cause") {
        val error = Error.fromRpcStatus(zga.google.rpc.Status(code = 13)).failureOption

        assertTrue(error.map(_.cause) == Some(None)) &&
        assertTrue(error.map(_.trailers.keys().isEmpty) == Some(true))
      },
      test("an unknown code is UNKNOWN") {
        val error = Error.fromRpcStatus(zga.google.rpc.Status(code = 99)).failureOption

        assertTrue(error.map(_.code) == Some(Status.Code.UNKNOWN))
      },
      test("an OK status is a defect") {
        val cause = Error.fromRpcStatus(zga.google.rpc.Status(code = 0))

        assertTrue(cause.isDie)
      },
    ),
  ) @@ TestAspect.timeout(15.seconds)

  private def pack[A](typeUrl: String, value: A)(using messageCodec: MessageCodec[A]) =
    zga.google.protobuf.Any(typeUrl = typeUrl, value = ByteString.copyFrom(messageCodec.toByteArray(value)))
}
