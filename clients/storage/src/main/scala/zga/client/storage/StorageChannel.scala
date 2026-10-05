package zga.client.storage

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class StorageChannel(val channel: ZChannel)

object StorageChannel {

  // https://github.com/googleapis/java-storage/blob/v2.64.1/gapic-google-cloud-storage-v2/src/main/java/com/google/storage/v2/stub/StorageStubSettings.java#L447
  val Host = "storage.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): StorageChannel = {
    new StorageChannel(channel)
  }

  val default: ZIO[Scope, Throwable, StorageChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
