package zga.client.cloudkms

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class CloudKmsChannel(val channel: ZChannel)

object CloudKmsChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-kms/google-cloud-kms/src/main/java/com/google/cloud/kms/v1/stub/KeyManagementServiceStubSettings.java#L913
  val Host = "cloudkms.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): CloudKmsChannel = {
    new CloudKmsChannel(channel)
  }

  val default: ZIO[Scope, Throwable, CloudKmsChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
