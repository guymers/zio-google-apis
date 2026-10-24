package zga.client.secretmanager

import zga.client.secretmanager.auth.SecretManagerAuthentication
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zga.test.GoogleTestEnvironment
import zio.ZLayer

object SecretManagerIntegrationTest {

  type Env = SecretManagerChannel & SecretManagerAuthentication & GoogleCloudProjectId

  private val channelLayer = GoogleTestEnvironment.channel(SecretManagerChannel.builder, SecretManagerChannel(_))

  private val authenticationLayer = GoogleTestEnvironment.authentication(SecretManagerAuthentication.fromGoogle)

  val layer = ZLayer.makeSome[GoogleIntegrationTest.Env, Env](
    channelLayer,
    authenticationLayer,
    GoogleTestEnvironment.projectId,
  )
}
