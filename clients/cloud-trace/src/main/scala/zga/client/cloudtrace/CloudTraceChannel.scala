package zga.client.cloudtrace

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class CloudTraceChannel(val channel: ZChannel)

object CloudTraceChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-trace/google-cloud-trace/src/main/java/com/google/cloud/trace/v2/stub/TraceServiceStubSettings.java#L156
  val Host = "cloudtrace.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): CloudTraceChannel = {
    new CloudTraceChannel(channel)
  }

  val default: ZIO[Scope, Throwable, CloudTraceChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
