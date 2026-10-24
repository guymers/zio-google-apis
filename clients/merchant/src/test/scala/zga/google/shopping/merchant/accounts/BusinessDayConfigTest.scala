package zga.google.shopping.merchant.accounts

import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.checkAll

import java.time.DayOfWeek
import scala.util.Try

/**
 * `Weekday` numbers days Monday 1 to Sunday 7 like `java.time.DayOfWeek`, with
 * `WEEKDAY_UNSPECIFIED` 0. There is no protobuf-java Merchant dependency to
 * compare against, so these tests check the numbering directly.
 */
object BusinessDayConfigTest extends ZIOSpecDefault {

  override val spec = suite("BusinessDayConfig")(
    test("read") {
      checkAll(Gen.int(-1, 9)) { number =>
        val read = Try(BusinessDayConfig.Weekday.fromValue(number)).toEither.left.map(_.getClass.getSimpleName)
        val expected =
          if (number == 0) Right(None)
          else if (number >= 1 && number <= 7) Right(Some(DayOfWeek.of(number)))
          else Left("InvalidProtocolBufferException")
        assertTrue(read == expected)
      }
    },
    test("write") {
      checkAll(Gen.fromIterable(None +: DayOfWeek.values.toList.map(Some(_)))) { day =>
        assertTrue(BusinessDayConfig.Weekday.value(day) == day.fold(0)(_.getValue))
      }
    },
    test("round trip") {
      val day = Gen.fromIterable(None +: DayOfWeek.values.toList.map(Some(_)))
      checkAll(Gen.chunkOf(day)) { days =>
        val value = BusinessDayConfig(businessDays = days)
        assertTrue(BusinessDayConfig.messageCodec.parseFrom(BusinessDayConfig.messageCodec.toByteArray(value)) == value)
      }
    },
  )
}
