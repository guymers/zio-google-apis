package zga.test

import com.google.auth.oauth2.GoogleCredentials
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import zga.auth.Credentials
import zga.client.ZChannel
import zio.Tag
import zio.ZIO
import zio.ZLayer
import zio.test.Annotations
import zio.test.TestAspect
import zio.test.TestAspectAtLeastR

object GoogleTestEnvironment {

  private val EnvVarKey = "ZGA_TEST_GOOGLE_DEFAULT_CREDENTIALS_AVAILABLE"
  private val envVar = sys.env.get(EnvVarKey).filter(_.nonEmpty)

  private val ProjectEnvVarKey = "ZGA_TEST_GOOGLE_PROJECT_ID"
  private val projectEnvVar = sys.env.get(ProjectEnvVarKey).filter(_.nonEmpty).flatMap(_.toLongOption)

  val available: TestAspectAtLeastR[Annotations] = {
    if (envVar.isDefined && projectEnvVar.isDefined) TestAspect.identity else TestAspect.ignore
  }

  val credentials = ZLayer.fromZIO(Credentials.defaultGoogleCredentials)

  def channel[A: Tag](builder: => NettyChannelBuilder, wrap: ZChannel => A): ZLayer[GoogleIntegrationTest.Env, Throwable, A] = {
    ZLayer.scoped(for {
      eventLoopGroup <- ZIO.service[EventLoopGroup]
      socketChannelFactory <- ZIO.service[ChannelFactory[SocketChannel]]
      channel <- ZChannel.create {
        builder
          .eventLoopGroup(eventLoopGroup)
          .channelFactory(socketChannelFactory)
      }
    } yield wrap(channel))
  }

  def authentication[A: Tag](fromGoogle: GoogleCredentials => A): ZLayer[Any, Throwable, A] = {
    credentials >>> ZLayer.fromZIO(ZIO.serviceWith[GoogleCredentials](fromGoogle))
  }

  val projectId = ZLayer.succeed {
    val id = projectEnvVar.getOrElse(throw new IllegalStateException("ProjectEnv variable not defined"))
    GoogleCloudProjectId(id)
  }
}

case class GoogleCloudProjectId(value: Long) {
  override def toString = value.toString
}
