package zga.client.secretmanager

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class SecretManagerChannel(val channel: ZChannel)

object SecretManagerChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-secretmanager/google-cloud-secretmanager/src/main/java/com/google/cloud/secretmanager/v1/stub/SecretManagerServiceStubSettings.java#L405
  val Host = "secretmanager.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): SecretManagerChannel = {
    new SecretManagerChannel(channel)
  }

  val default: ZIO[Scope, Throwable, SecretManagerChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
