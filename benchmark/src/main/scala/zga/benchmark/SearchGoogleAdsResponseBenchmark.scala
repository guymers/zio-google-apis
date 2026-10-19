package zga.benchmark

import com.google.ads.googleads.v26.common.Metrics
import com.google.ads.googleads.v26.common.Segments
import com.google.ads.googleads.v26.enums.CampaignStatusEnum.CampaignStatus
import com.google.ads.googleads.v26.resources.Campaign
import com.google.ads.googleads.v26.services.GoogleAdsRow
import com.google.ads.googleads.v26.services.SearchGoogleAdsResponse as JavaSearchGoogleAdsResponse
import org.openjdk.jmh.annotations.*
import zga.benchmark.SearchGoogleAdsResponseBenchmark.JavaResponse
import zga.benchmark.SearchGoogleAdsResponseBenchmark.Page
import zga.common.MessageCodec
import zga.google.ads.services.SearchGoogleAdsResponse

import java.util.concurrent.TimeUnit

/**
 * Encoding and decoding a page of Google Ads search results, each row a
 * campaign with a day's metrics: a few fields set in messages of hundreds of
 * fields, benchmarked against the protobuf-java codecs.
 *
 * The bytes are built with protobuf-java, so the input does not come from the
 * codec under test and does not depend on how the generated case classes of
 * wide messages are laid out. Setup checks that decoding and re-encoding them
 * reproduces the same bytes.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class SearchGoogleAdsResponseBenchmark {

  private val codec = MessageCodec[SearchGoogleAdsResponse]

  @Benchmark
  def encode(page: Page): Array[Byte] = codec.toByteArray(page.response)

  @Benchmark
  def encodeJava(state: JavaResponse): Array[Byte] = state.response.toByteArray

  @Benchmark
  def decode(page: Page): SearchGoogleAdsResponse = codec.parseFrom(page.bytes)

  @Benchmark
  def decodeJava(page: Page): JavaSearchGoogleAdsResponse = JavaSearchGoogleAdsResponse.parseFrom(page.bytes)
}

object SearchGoogleAdsResponseBenchmark {

  /**
   * The page that the benchmarks encode or decode, built once per fork and
   * shared between threads, as it does not change.
   */
  @State(Scope.Benchmark)
  class Page {

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

    private val codec = MessageCodec[SearchGoogleAdsResponse]

    private[benchmark] val response = codec.parseFrom(bytes)

    @Setup(Level.Trial)
    def checkSameEncoding(): Unit = {
      if (response.results.length != 1000 || !codec.toByteArray(response).sameElements(bytes)) {
        throw IllegalStateException("the response does not encode to the same bytes as protobuf-java")
      }
    }
  }

  /**
   * protobuf-java memoizes the serialized size of a message once it has
   * computed it, so a second write is cheaper than the first. Each invocation
   * reads a fresh response, as one that has just been received or built would
   * be. The state is its own, so that only this benchmark reads repeatedly.
   */
  @State(Scope.Thread)
  class JavaResponse {

    var response: JavaSearchGoogleAdsResponse = scala.compiletime.uninitialized

    @Setup(Level.Invocation)
    def read(page: Page): Unit = {
      response = JavaSearchGoogleAdsResponse.parseFrom(page.bytes)
    }
  }
}
