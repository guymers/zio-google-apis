package zga.client.merchant

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class MerchantChannel(val channel: ZChannel)

object MerchantChannel {

  // every Merchant API service uses the same host and scope, see e.g.
  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-shopping-merchant-accounts/google-shopping-merchant-accounts/src/main/java/com/google/shopping/merchant/accounts/v1/stub/AccountsServiceStubSettings.java#L314
  val Host = "merchantapi.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): MerchantChannel = {
    new MerchantChannel(channel)
  }

  val default: ZIO[Scope, Throwable, MerchantChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
