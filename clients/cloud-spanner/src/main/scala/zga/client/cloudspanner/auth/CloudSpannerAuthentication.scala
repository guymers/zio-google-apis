package zga.client.cloudspanner.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object CloudSpannerAuthentication {

  // https://github.com/googleapis/java-spanner/blob/v6.113.0/google-cloud-spanner/src/main/java/com/google/cloud/spanner/v1/stub/SpannerStubSettings.java#L136
  val dataScopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/spanner.data",
  )

  // the Database Admin and Instance Admin services request the same scopes
  // https://github.com/googleapis/java-spanner/blob/v6.113.0/google-cloud-spanner/src/main/java/com/google/cloud/spanner/admin/database/v1/stub/DatabaseAdminStubSettings.java#L196
  // https://github.com/googleapis/java-spanner/blob/v6.113.0/google-cloud-spanner/src/main/java/com/google/cloud/spanner/admin/instance/v1/stub/InstanceAdminStubSettings.java#L186
  val adminScopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/spanner.admin",
  )

  // the client serves both the data and admin APIs, so it requests the scopes of both
  val scopes: List[String] = (dataScopes ++ adminScopes).distinct

  def apply(credentials: Credentials): CloudSpannerAuthentication = {
    new CloudSpannerAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): CloudSpannerAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class CloudSpannerAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
