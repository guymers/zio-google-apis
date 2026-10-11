package zga.client.ads

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class AdsChannel(val channel: ZChannel)

object AdsChannel {

  // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads/src/main/java/com/google/ads/googleads/lib/GoogleAdsClient.java#L71
  val Host = "googleads.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads/src/main/java/com/google/ads/googleads/lib/GoogleAdsClient.java#L77
    .maxInboundMetadataSize(16 * 1024 * 1024)
    // https://github.com/googleads/google-ads-java/blob/47.0.0/google-ads/src/main/java/com/google/ads/googleads/lib/GoogleAdsClient.java#L83
    // the Google SDK sets it to 64mb, have seen a 400mb message in the wild
    .maxInboundMessageSize(512 * 1024 * 1024)
    .useTransportSecurity

  def apply(channel: ZChannel): AdsChannel = {
    new AdsChannel(channel)
  }

  val default: ZIO[Scope, Throwable, AdsChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
