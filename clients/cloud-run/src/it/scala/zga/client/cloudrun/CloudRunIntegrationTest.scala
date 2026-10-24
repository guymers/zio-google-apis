package zga.client.cloudrun

import zga.client.cloudrun.auth.CloudRunAuthentication
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zga.test.GoogleTestEnvironment
import zio.ZLayer

object CloudRunIntegrationTest {

  type Env = CloudRunChannel & CloudRunAuthentication & GoogleCloudProjectId

  private val channelLayer = GoogleTestEnvironment.channel(CloudRunChannel.builder, CloudRunChannel(_))

  private val authenticationLayer = GoogleTestEnvironment.authentication(CloudRunAuthentication.fromGoogle)

  val layer = ZLayer.makeSome[GoogleIntegrationTest.Env, Env](
    channelLayer,
    authenticationLayer,
    GoogleTestEnvironment.projectId,
  )
}
