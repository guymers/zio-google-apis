package zga.client.cloudrun

import com.google.auth.oauth2.GoogleCredentials
import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import zga.client.ZChannel
import zga.client.cloudrun.auth.CloudRunAuthentication
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zga.test.GoogleTestEnvironment
import zio.ZIO
import zio.ZLayer

object CloudRunIntegrationTest {

  type Env = CloudRunChannel & CloudRunAuthentication & GoogleCloudProjectId

  private val channelLayer = ZLayer.scoped(for {
    eventLoopGroup <- ZIO.service[EventLoopGroup]
    socketChannelFactory <- ZIO.service[ChannelFactory[SocketChannel]]
    channel <- ZChannel.create {
      CloudRunChannel.builder
        .eventLoopGroup(eventLoopGroup)
        .channelFactory(socketChannelFactory)
    }.map(CloudRunChannel(_))
  } yield channel)

  private val authenticationLayer = GoogleTestEnvironment.credentials >>> ZLayer.fromZIO(for {
    googleCredentials <- ZIO.service[GoogleCredentials]
  } yield CloudRunAuthentication.fromGoogle(googleCredentials))

  val layer = ZLayer.makeSome[GoogleIntegrationTest.Env, Env](
    channelLayer,
    authenticationLayer,
    GoogleTestEnvironment.projectId,
  )
}
