package zga.client.cloudkms.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object CloudKmsAuthentication {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-kms/google-cloud-kms/src/main/java/com/google/cloud/kms/v1/stub/KeyManagementServiceStubSettings.java#L221
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/cloudkms",
  )

  def apply(credentials: Credentials): CloudKmsAuthentication = {
    new CloudKmsAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): CloudKmsAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class CloudKmsAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
