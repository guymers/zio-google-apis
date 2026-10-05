package zga.client.secretmanager.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object SecretManagerAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-secretmanager/google-cloud-secretmanager/src/main/java/com/google/cloud/secretmanager/v1/stub/SecretManagerServiceStubSettings.java#L142
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
  )

  def apply(credentials: Credentials): SecretManagerAuthentication = {
    new SecretManagerAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): SecretManagerAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class SecretManagerAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
