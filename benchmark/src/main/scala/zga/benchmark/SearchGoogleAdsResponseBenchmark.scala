package zga.benchmark

import com.google.ads.googleads.v25.common.Metrics
import com.google.ads.googleads.v25.common.Segments
import com.google.ads.googleads.v25.enums.CampaignStatusEnum.CampaignStatus
import com.google.ads.googleads.v25.resources.Campaign
import com.google.ads.googleads.v25.services.GoogleAdsRow
import com.google.ads.googleads.v25.services.SearchGoogleAdsResponse as JavaSearchGoogleAdsResponse
import org.openjdk.jmh.annotations.*
import zga.common.MessageCodec
import zga.google.ads.services.SearchGoogleAdsResponse

import java.util.concurrent.TimeUnit

/**
 * Encoding and decoding a page of Google Ads search results, each row a
 * campaign with its metrics for a day: a few fields set in messages of hundreds
 * of fields.
 *
 * The page is built with protobuf-java and decoded, so that it does not depend
 * on how the generated case classes of wide messages are laid out.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class SearchGoogleAdsResponseBenchmark {

  private val codec = MessageCodec[SearchGoogleAdsResponse]

  private[benchmark] val bytes = {
    val response = JavaSearchGoogleAdsResponse.newBuilder.setNextPageToken("token")
    (1 to 1000).foreach { i =>
      val _ = response.addResults(
        GoogleAdsRow.newBuilder
          .setCampaign(
            Campaign.newBuilder
              .setResourceName(s"customers/1234567890/campaigns/$i")
              .setId(i.toLong)
              .setName(s"Campaign $i")
              .setStatus(CampaignStatus.ENABLED),
          )
          .setMetrics(
            Metrics.newBuilder
              .setClicks(i.toLong * 3)
              .setImpressions(i.toLong * 100)
              .setCostMicros(i.toLong * 1234567)
              .setConversions(i * 0.5d)
              .setCtr(0.03d)
              .setAverageCpc(1.5d),
          )
          .setSegments(Segments.newBuilder.setDate("2026-10-11")),
      )
    }
    response.build().toByteArray
  }

  private val response = codec.parseFrom(bytes)

  @Setup(Level.Trial)
  def checkSameEncoding(): Unit = {
    if (response.results.length != 1000 || !codec.toByteArray(response).sameElements(bytes)) {
      throw IllegalStateException("the response does not encode to the same bytes as protobuf-java")
    }
  }

  @Benchmark
  def encode: Array[Byte] = codec.toByteArray(response)

  @Benchmark
  def encodeJava(state: SearchGoogleAdsResponseBenchmark.JavaResponse): Array[Byte] = state.response.toByteArray

  @Benchmark
  def decode: SearchGoogleAdsResponse = codec.parseFrom(bytes)

  @Benchmark
  def decodeJava: JavaSearchGoogleAdsResponse = JavaSearchGoogleAdsResponse.parseFrom(bytes)
}

object SearchGoogleAdsResponseBenchmark {

  /**
   * protobuf-java memoizes the size of a message once it has been written, so
   * each write is of a message freshly read, as one received or built would be.
   * A state of its own, so that reading it is not done for the other
   * benchmarks.
   */
  @State(Scope.Thread)
  class JavaResponse {

    var response: JavaSearchGoogleAdsResponse = scala.compiletime.uninitialized

    @Setup(Level.Invocation)
    def read(benchmark: SearchGoogleAdsResponseBenchmark): Unit = {
      response = JavaSearchGoogleAdsResponse.parseFrom(benchmark.bytes)
    }
  }
}
