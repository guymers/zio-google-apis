package zga.client.secretmanager

import com.google.auth.oauth2.GoogleCredentials
import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import zga.client.ZChannel
import zga.client.secretmanager.auth.SecretManagerAuthentication
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zga.test.GoogleTestEnvironment
import zio.ZIO
import zio.ZLayer

object SecretManagerIntegrationTest {

  type Env = SecretManagerChannel & SecretManagerAuthentication & GoogleCloudProjectId

  private val channelLayer = ZLayer.scoped(for {
    eventLoopGroup <- ZIO.service[EventLoopGroup]
    socketChannelFactory <- ZIO.service[ChannelFactory[SocketChannel]]
    channel <- ZChannel.create {
      SecretManagerChannel.builder
        .eventLoopGroup(eventLoopGroup)
        .channelFactory(socketChannelFactory)
    }.map(SecretManagerChannel(_))
  } yield channel)

  private val authenticationLayer = GoogleTestEnvironment.credentials >>> ZLayer.fromZIO(for {
    googleCredentials <- ZIO.service[GoogleCredentials]
  } yield SecretManagerAuthentication.fromGoogle(googleCredentials))

  val layer = ZLayer.makeSome[GoogleIntegrationTest.Env, Env](
    channelLayer,
    authenticationLayer,
    GoogleTestEnvironment.projectId,
  )
}
