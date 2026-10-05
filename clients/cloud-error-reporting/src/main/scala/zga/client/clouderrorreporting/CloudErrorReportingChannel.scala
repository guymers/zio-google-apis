package zga.client.clouderrorreporting

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class CloudErrorReportingChannel(val channel: ZChannel)

object CloudErrorReportingChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-errorreporting/google-cloud-errorreporting/src/main/java/com/google/devtools/clouderrorreporting/v1beta1/stub/ReportErrorsServiceStubSettings.java#L151
  val Host = "clouderrorreporting.googleapis.com"
  val Port = 443

  def builder: NettyChannelBuilder = NettyChannelBuilder.forAddress(Host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): CloudErrorReportingChannel = {
    new CloudErrorReportingChannel(channel)
  }

  val default: ZIO[Scope, Throwable, CloudErrorReportingChannel] = {
    ZChannel.create(builder).map(apply)
  }
}
