package zga.google.ads.common

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object MetricsTest extends ZIOSpecDefault {

  private val metrics = Metrics(
    part1 = Metrics.Part1(activeViewCpm = Some(1.25d)),
    part2 = Metrics.Part2(absoluteBrandLift = Some(2.5d)),
  )
  private val bytes = Metrics.messageCodec.toByteArray(metrics)

  override val spec = suite("Metrics")(
    test("fields in each part are exported to seem like one case class") {
      val parsed = Metrics.messageCodec.parseFrom(bytes)
      assertTrue(parsed == metrics) &&
      assertTrue(parsed.activeViewCpm == Some(1.25d)) &&
      assertTrue(parsed.absoluteBrandLift == Some(2.5d))
    },
  )
}
