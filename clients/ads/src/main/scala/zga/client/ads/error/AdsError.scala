package zga.client.ads.error

import io.grpc.Metadata
import zga.google.ads.errors.GoogleAdsError
import zga.google.ads.errors.GoogleAdsFailure

import java.util.Locale
import scala.util.Try

sealed abstract class AdsError(
  val error: zga.error.Error,
  val msg: String,
  cause: Option[Throwable],
) extends RuntimeException(
    s"$msg${AdsError.extractRequestId(error).fold("")(id => s" (id=${id.value})")}",
    cause.orNull,
  ) {

  def requestId: Option[RequestId] = AdsError.extractRequestId(error)
}

object AdsError {

  // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads-stubs-v25/src/main/java/com/google/ads/googleads/v25/errors/GoogleAdsException.java#L57
  private val name = GoogleAdsFailure.messageCodec.descriptor.getFullName.toLowerCase(Locale.ROOT) + "-bin"
  private val FailureKey = Metadata.Key.of(name, Metadata.BINARY_BYTE_MARSHALLER)

  // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads-stubs-lib/src/main/java/com/google/ads/googleads/lib/stubs/exceptions/BaseGoogleAdsException.java#L34
  private val RequestIdKey = Metadata.Key.of("request-id", Metadata.ASCII_STRING_MARSHALLER)

  def extractRequestId(error: zga.error.Error): Option[RequestId] = {
    Option(error.trailers.get(RequestIdKey)).map(RequestId.apply)
  }

  def fromError(error: zga.error.Error): AdsError = {
    Option(error.trailers.get(FailureKey)).map { data =>
      Try(GoogleAdsFailure.messageCodec.parseFrom(data)).toEither match {
        case Left(t) => Unparseable(error, t)
        case Right(failure) => Failure(failure, error)
      }
    }.getOrElse(Error(error))
  }

  case class Failure(failure: GoogleAdsFailure, override val error: zga.error.Error) extends AdsError(
      error,
      showGoogleAdsFailure(failure),
      Some(error.asRuntimeException),
    )

  case class Error(override val error: zga.error.Error) extends AdsError(
      error,
      error.description.fold(error.code.toString)(description => s"${error.code}: $description"),
      error.cause,
    )

  case class Unparseable(override val error: zga.error.Error, t: Throwable) extends AdsError(
      error,
      s"Failed to parse Google Ads failure: ${Option(t.getMessage).getOrElse("unknown")}",
      error.cause,
    )

  private def showGoogleAdsFailure(failure: GoogleAdsFailure) = {
    val count = failure.errors.size
    if (count == 0) "Google Ads failure has no errors"
    else {
      val str = failure.errors.take(3).map(showGoogleAdsError(_)).mkString("; ")
      s"Google Ads failure: ${if (count > 3) s"$str; ..." else str}"
    }
  }

  private def showGoogleAdsError(error: GoogleAdsError) = {
    val code = error.errorCode.flatMap(_.errorCode).map(_.toString).fold("")(code => s"$code - ")
    s"$code${error.message}"
  }
}
