package zga.client.ads.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Metadata
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object AdsAuthentication {

  // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads/src/main/java/com/google/ads/googleads/lib/GoogleAdsClient.java#L170
  val scopes = List(
    "https://www.googleapis.com/auth/adwords",
  )

  def apply(
    credentials: Credentials,
    loginCustomerId: Option[LoginCustomerId],
    linkedCustomerId: Option[LinkedCustomerId],
  ): AdsAuthentication = {
    new AdsAuthentication(credentials, Headers(loginCustomerId, linkedCustomerId))
  }

  def fromGoogle(
    credentials: GoogleAuthCredentials,
    loginCustomerId: Option[LoginCustomerId],
    linkedCustomerId: Option[LinkedCustomerId],
  ) = apply(Credentials.create(credentials, scopes), loginCustomerId, linkedCustomerId)

  case class Headers(
    loginCustomerId: Option[LoginCustomerId],
    linkedCustomerId: Option[LinkedCustomerId],
  ) {
    def metadata = {
      val m = new Metadata
      loginCustomerId.foreach(id => m.put(Headers.LoginCustomerId, id.value))
      linkedCustomerId.foreach(id => m.put(Headers.LinkedCustomerId, id.value))
      m
    }
  }

  object Headers {
    // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads/src/main/java/com/google/ads/googleads/lib/GoogleAdsHeaderProvider.java#L66
    val LoginCustomerId = Metadata.Key.of("login-customer-id", longAsciiMarshaller)
    val LinkedCustomerId = Metadata.Key.of("linked-customer-id", longAsciiMarshaller)

    private def stringAsciiMarshaller = Metadata.ASCII_STRING_MARSHALLER
    private def longAsciiMarshaller = new Metadata.AsciiMarshaller[Long] {
      override def toAsciiString(value: Long) = stringAsciiMarshaller.toAsciiString(value.toString)
      override def parseAsciiString(serialized: String) = stringAsciiMarshaller.parseAsciiString(serialized).toLong
    }
  }
}

final class AdsAuthentication(credentials: Credentials, headers: AdsAuthentication.Headers) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = for {
    uri <- Credentials.serviceUri(authority, serviceName)
    metadata <- credentials.headers(uri)
    _ = metadata.merge(headers.metadata)
    safe <- SafeMetadata.fromMetadata(metadata)
  } yield safe

  def withLoginCustomerId(loginCustomerId: LoginCustomerId) = {
    new AdsAuthentication(credentials, headers.copy(loginCustomerId = Some(loginCustomerId)))
  }

  def withLinkedCustomerId(linkedCustomerId: LinkedCustomerId) = {
    new AdsAuthentication(credentials, headers.copy(linkedCustomerId = Some(linkedCustomerId)))
  }
}
