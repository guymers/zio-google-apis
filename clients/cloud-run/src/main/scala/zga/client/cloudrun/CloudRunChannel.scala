package zga.client.cloudrun

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class CloudRunChannel(val channel: ZChannel)

object CloudRunChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-run/google-cloud-run/src/main/java/com/google/cloud/run/v2/stub/JobsStubSettings.java#L322
  val Host = "run.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): CloudRunChannel = {
    new CloudRunChannel(channel)
  }

  val default: ZIO[Scope, Throwable, CloudRunChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
