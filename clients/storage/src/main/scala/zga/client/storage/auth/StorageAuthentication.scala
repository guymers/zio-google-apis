package zga.client.storage.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object StorageAuthentication {

  // https://github.com/googleapis/java-storage/blob/v2.64.1/gapic-google-cloud-storage-v2/src/main/java/com/google/storage/v2/stub/StorageStubSettings.java#L148
  val scopes = List(
    "https://www.googleapis.com/auth/cloud-platform",
    "https://www.googleapis.com/auth/cloud-platform.read-only",
    "https://www.googleapis.com/auth/devstorage.full_control",
    "https://www.googleapis.com/auth/devstorage.read_only",
    "https://www.googleapis.com/auth/devstorage.read_write",
  )

  def apply(credentials: Credentials): StorageAuthentication = {
    new StorageAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): StorageAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class StorageAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
