package zga.client.cloudspanner

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class CloudSpannerChannel(val channel: ZChannel)

object CloudSpannerChannel {

  // https://github.com/googleapis/java-spanner/blob/v6.113.0/google-cloud-spanner/src/main/java/com/google/cloud/spanner/v1/stub/SpannerStubSettings.java#L330
  val Host = "spanner.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): CloudSpannerChannel = {
    new CloudSpannerChannel(channel)
  }

  val default: ZIO[Scope, Throwable, CloudSpannerChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
