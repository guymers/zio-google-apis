package zga.client.ads

import com.google.auth.Credentials as GoogleAuthCredentials
import zga.client.ads.auth.AdsAuthentication
import zga.client.ads.auth.LinkedCustomerId
import zga.client.ads.auth.LoginCustomerId
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.net.URI
import java.util.Collections
import java.util.List as JList
import java.util.Map as JMap

object AdsAuthenticationTest extends ZIOSpecDefault {

  private val credentials = new GoogleAuthCredentials {
    override def getAuthenticationType = "test"
    override def getRequestMetadata(uri: URI): JMap[String, JList[String]] = Collections.emptyMap()
    override def hasRequestMetadata = true
    override def hasRequestMetadataOnly = true
    override def refresh(): Unit = ()
  }

  override val spec = suite("AdsAuthentication")(
    test("customer ID factories allow callers to configure request headers") {
      val login = LoginCustomerId(1234567890L)
      val linked = LinkedCustomerId(2345678901L)
      val auth = AdsAuthentication.fromGoogle(credentials, loginCustomerId = None, linkedCustomerId = None)
        .withLoginCustomerId(login).withLinkedCustomerId(linked)
      for {
        metadata <- auth.metadata(AdsChannel.Host, "test.Service")
        actualLogin <- metadata.get(AdsAuthentication.Headers.LoginCustomerId)
        actualLinked <- metadata.get(AdsAuthentication.Headers.LinkedCustomerId)
      } yield assertTrue(actualLogin.contains(login.value), actualLinked.contains(linked.value))
    },
  )
}
