package zga.client.cloudtrace.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object CloudTraceAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-trace/google-cloud-trace/src/main/java/com/google/cloud/trace/v2/stub/TraceServiceStubSettings.java#L108
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/trace.append",
  )

  def apply(credentials: Credentials): CloudTraceAuthentication = {
    new CloudTraceAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): CloudTraceAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class CloudTraceAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
