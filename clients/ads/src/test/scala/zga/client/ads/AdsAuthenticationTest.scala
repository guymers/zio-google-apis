package zga.client.ads

import com.google.auth.Credentials as GoogleAuthCredentials
import zga.client.ads.auth.AdsAuthentication
import zga.client.ads.auth.LinkedCustomerId
import zga.client.ads.auth.LoginCustomerId
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.net.URI
import java.util.Collections

object AdsAuthenticationTest extends ZIOSpecDefault {

  override val spec = suite("AdsAuthentication")(
    test("customer id in metadata") {
      val login = LoginCustomerId(1234567890L)
      val linked = LinkedCustomerId(2345678901L)
      val auth = AdsAuthentication.fromGoogle(credentials, loginCustomerId = None, linkedCustomerId = None)
        .withLoginCustomerId(login)
        .withLinkedCustomerId(linked)
      for {
        metadata <- auth.metadata(AdsChannel.Host, "test.Service")
        actualLogin <- metadata.get(AdsAuthentication.Headers.LoginCustomerId)
        actualLinked <- metadata.get(AdsAuthentication.Headers.LinkedCustomerId)
      } yield assertTrue(
        actualLogin == Some(login.value),
        actualLinked == Some(linked.value),
      )
    },
  )

  private lazy val credentials = new GoogleAuthCredentials {
    override def getAuthenticationType = "test"
    override def getRequestMetadata(uri: URI) = Collections.emptyMap()
    override def hasRequestMetadata = true
    override def hasRequestMetadataOnly = true
    override def refresh() = ()
  }
}
