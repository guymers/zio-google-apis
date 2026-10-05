package zga.client.analytics

import io.grpc.netty.NettyChannelBuilder
import zga.client.ZChannel
import zio.Scope
import zio.ZIO

final class AnalyticsChannel(val channel: ZChannel)
object AnalyticsChannel {

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-analytics-data/google-analytics-data/src/main/java/com/google/analytics/data/v1beta/stub/BetaAnalyticsDataStubSettings.java#L356
  val DataHost = "analyticsdata.googleapis.com"

  // https://github.com/googleapis/google-cloud-java/blob/v1.93.0/java-analytics-admin/google-analytics-admin/src/main/java/com/google/analytics/admin/v1beta/stub/AnalyticsAdminServiceStubSettings.java#L1432
  val AdminHost = "analyticsadmin.googleapis.com"

  val Port = 443

  def builder(host: String): NettyChannelBuilder = NettyChannelBuilder.forAddress(host, Port)
    .disableRetry // perform retries using ZIO
    .useTransportSecurity

  def apply(channel: ZChannel): AnalyticsChannel = {
    new AnalyticsChannel(channel)
  }

  val data: ZIO[Scope, Throwable, AnalyticsChannel] = {
    ZChannel.create(builder(DataHost)).map(apply)
  }

  val admin: ZIO[Scope, Throwable, AnalyticsChannel] = {
    ZChannel.create(builder(AdminHost)).map(apply)
  }
}
