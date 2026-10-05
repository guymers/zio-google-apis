package zga.client.cloudrun.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object CloudRunAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/main/java-run/google-cloud-run/src/main/java/com/google/cloud/run/v2/stub/JobsStubSettings.java#L152
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
  )

  def apply(credentials: Credentials): CloudRunAuthentication = {
    new CloudRunAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): CloudRunAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class CloudRunAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
