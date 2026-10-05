package zga.client.analytics.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object AnalyticsAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-analytics-data/google-analytics-data/src/main/java/com/google/analytics/data/v1beta/stub/BetaAnalyticsDataStubSettings.java#L169
  val dataScopes = List(
    "https://www.googleapis.com/auth/analytics",
    "https://www.googleapis.com/auth/analytics.readonly",
  )

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-analytics-admin/google-analytics-admin/src/main/java/com/google/analytics/admin/v1beta/stub/AnalyticsAdminServiceStubSettings.java#L213
  val adminScopes = List(
    "https://www.googleapis.com/auth/analytics.edit",
    "https://www.googleapis.com/auth/analytics.readonly",
  )

  // the client serves both Analytics APIs, so it requests the scopes of both
  val scopes: List[String] = (dataScopes ++ adminScopes).distinct

  def apply(credentials: Credentials): AnalyticsAuthentication = {
    new AnalyticsAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): AnalyticsAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }

  def fromGoogle(credentials: GoogleAuthCredentials, scopes: List[String]): AnalyticsAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class AnalyticsAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
