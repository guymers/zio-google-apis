package zga.error

import io.grpc.Metadata
import zga.common.MessageCodec
import zio.Chunk

import java.time.Duration
import scala.util.Try
import scala.util.control.NonFatal

case class RpcStatus(
  code: Int,
  message: String,
  details: Chunk[RpcStatus.Details],
  unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
)
object RpcStatus {

  sealed trait Details
  object Details {

    case class BadRequest(
      fieldViolations: Chunk[BadRequest.FieldViolation],
    ) extends Details
    object BadRequest {
      case class FieldViolation(
        field: String,
        description: String,
      )
    }

    case class DebugInfo(
      detail: String,
      stackEntries: Chunk[String],
    ) extends Details

    case class ErrorInfo(
      reason: String,
      domain: String,
      metadata: Map[String, String],
    ) extends Details

    case class Help(
      links: Chunk[Help.Link],
    ) extends Details
    object Help {
      case class Link(
        url: String,
        description: String,
      )
    }

    case class LocalizedMessage(
      locale: String,
      message: String,
    ) extends Details

    case class PreconditionFailure(
      violations: Chunk[PreconditionFailure.Violation],
    ) extends Details
    object PreconditionFailure {
      case class Violation(
        `type`: String,
        subject: String,
        description: String,
      )
    }

    case class QuotaFailure(
      violations: Chunk[QuotaFailure.Violation],
    ) extends Details
    object QuotaFailure {
      case class Violation(
        subject: String,
        description: String,
      )
    }

    case class RequestInfo(
      requestId: String,
      servingData: String,
    ) extends Details

    case class ResourceInfo(
      resourceType: String,
      resourceName: String,
      owner: String,
      description: String,
    ) extends Details

    case class RetryInfo(
      retryDelay: Option[Duration],
    ) extends Details

    def from(a: zga.google.protobuf.Any): Either[Throwable, Details] = {
      try {
        parse(a)
      } catch { case NonFatal(e) => Left(e) }
    }

    private def parse(a: zga.google.protobuf.Any) = {
      // Compare only the suffix after the last "/" so that a custom type url
      // prefix (e.g. a proxy rewriting "type.googleapis.com") still matches.
      val typeUrl = a.typeUrl.substring(a.typeUrl.lastIndexOf('/') + 1)
      typeUrl match {
        case "google.rpc.BadRequest" =>
          MessageCodec[zga.google.rpc.BadRequest].safeParseFrom(a.value).map { badRequest =>
            BadRequest(badRequest.fieldViolations.map { v =>
              BadRequest.FieldViolation(
                field = v.field,
                description = v.description,
              )
            })
          }

        case "google.rpc.DebugInfo" =>
          MessageCodec[zga.google.rpc.DebugInfo].safeParseFrom(a.value).map { debugInfo =>
            DebugInfo(
              detail = debugInfo.detail,
              stackEntries = debugInfo.stackEntries,
            )
          }

        case "google.rpc.ErrorInfo" =>
          MessageCodec[zga.google.rpc.ErrorInfo].safeParseFrom(a.value).map { errorInfo =>
            ErrorInfo(
              reason = errorInfo.reason,
              domain = errorInfo.domain,
              metadata = errorInfo.metadata,
            )
          }

        case "google.rpc.Help" =>
          MessageCodec[zga.google.rpc.Help].safeParseFrom(a.value).map { help =>
            Help(help.links.map { link =>
              Help.Link(
                url = link.url,
                description = link.description,
              )
            })
          }

        case "google.rpc.LocalizedMessage" =>
          MessageCodec[zga.google.rpc.LocalizedMessage].safeParseFrom(a.value).map { localizedMessage =>
            LocalizedMessage(
              locale = localizedMessage.locale,
              message = localizedMessage.message,
            )
          }

        case "google.rpc.PreconditionFailure" =>
          MessageCodec[zga.google.rpc.PreconditionFailure].safeParseFrom(a.value).map { preconditionFailure =>
            PreconditionFailure(preconditionFailure.violations.map { v =>
              PreconditionFailure.Violation(
                `type` = v.`type`,
                subject = v.subject,
                description = v.description,
              )
            })
          }

        case "google.rpc.QuotaFailure" =>
          MessageCodec[zga.google.rpc.QuotaFailure].safeParseFrom(a.value).map { quotaFailure =>
            QuotaFailure(quotaFailure.violations.map { v =>
              QuotaFailure.Violation(
                subject = v.subject,
                description = v.description,
              )
            })
          }

        case "google.rpc.RequestInfo" =>
          MessageCodec[zga.google.rpc.RequestInfo].safeParseFrom(a.value).map { requestInfo =>
            RequestInfo(
              requestId = requestInfo.requestId,
              servingData = requestInfo.servingData,
            )
          }

        case "google.rpc.ResourceInfo" =>
          MessageCodec[zga.google.rpc.ResourceInfo].safeParseFrom(a.value).map { resourceInfo =>
            ResourceInfo(
              resourceType = resourceInfo.resourceType,
              resourceName = resourceInfo.resourceName,
              owner = resourceInfo.owner,
              description = resourceInfo.description,
            )
          }

        case "google.rpc.RetryInfo" =>
          MessageCodec[zga.google.rpc.RetryInfo].safeParseFrom(a.value).map { retryInfo =>
            RetryInfo(retryInfo.retryDelay.map(duration => Duration.ofSeconds(duration.seconds, duration.nanos.toLong)))
          }

        case _ =>
          Left(new IllegalArgumentException(s"Unhandled Google type url: ${a.typeUrl}"))
      }
    }
  }

  private val RpcStatusKey = Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)

  def fromTrailers(trailers: Metadata): Option[RpcStatus] = for {
    data <- Option(trailers.get(RpcStatusKey))
    status <- Try(zga.google.rpc.Status.messageCodec.parseFrom(data)).toOption
    (unparseableDetails, details) = status.details.partitionMap(a => Details.from(a).left.map((a, _)))
  } yield RpcStatus(code = status.code, message = status.message, details, unparseableDetails)
}
