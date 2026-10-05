package zga.client.merchant.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import io.grpc.Status
import zga.auth.Credentials
import zga.client.SafeMetadata
import zio.Trace
import zio.ZIO

object MerchantAuthentication {

  // every Merchant API service uses the same host and scope, see e.g.
  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-shopping-merchant-accounts/google-shopping-merchant-accounts/src/main/java/com/google/shopping/merchant/accounts/v1/stub/AccountsServiceStubSettings.java#L126
  val scopes = List(
    "https://www.googleapis.com/auth/content",
  )

  def apply(credentials: Credentials): MerchantAuthentication = {
    new MerchantAuthentication(credentials)
  }

  def fromGoogle(credentials: GoogleAuthCredentials): MerchantAuthentication = {
    apply(Credentials.create(credentials, scopes))
  }
}

final class MerchantAuthentication(credentials: Credentials) {

  def metadata(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, SafeMetadata] = {
    Credentials
      .serviceUri(authority, serviceName)
      .flatMap(credentials.headers(_))
      .flatMap(SafeMetadata.fromMetadata(_))
  }
}
