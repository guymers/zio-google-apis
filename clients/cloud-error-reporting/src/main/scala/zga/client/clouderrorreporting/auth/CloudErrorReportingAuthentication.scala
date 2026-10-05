package zga.client.clouderrorreporting.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object CloudErrorReportingAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-errorreporting/google-cloud-errorreporting/src/main/java/com/google/devtools/clouderrorreporting/v1beta1/stub/ReportErrorsServiceStubSettings.java#L110
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
  )

  def apply(credentials: Credentials): CloudErrorReportingAuthentication = {
    new CloudErrorReportingAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): CloudErrorReportingAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class CloudErrorReportingAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
