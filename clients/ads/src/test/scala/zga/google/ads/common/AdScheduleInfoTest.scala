package zga.google.ads.common

import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.checkAll

import java.time.DayOfWeek
import scala.util.Try

object AdScheduleInfoTest extends ZIOSpecDefault {
  import com.google.ads.googleads.v26.common.AdScheduleInfo as JavaAdScheduleInfo
  import com.google.ads.googleads.v26.enums.DayOfWeekEnum.DayOfWeek as JavaDayOfWeek

  override val spec = suite("AdScheduleInfo")(
    test("read") {
      checkAll(Gen.int(-1, 10)) { number =>
        val bytes = JavaAdScheduleInfo.newBuilder.setDayOfWeekValue(number).build().toByteArray
        val read = Try(AdScheduleInfo.messageCodec.parseFrom(bytes).dayOfWeek).toEither.left.map(_.getClass.getSimpleName)
        val expected = Option(JavaDayOfWeek.forNumber(number)) match {
          case Some(JavaDayOfWeek.UNKNOWN) | None => Left("InvalidProtocolBufferException")
          case Some(JavaDayOfWeek.UNSPECIFIED) => Right(None)
          case Some(day) => Right(Some(DayOfWeek.valueOf(day.name)))
        }
        assertTrue(read == expected)
      }
    },
    test("write") {
      checkAll(Gen.fromIterable(None +: DayOfWeek.values.toList.map(Some(_)))) { day =>
        val javaDay = day.fold(JavaDayOfWeek.UNSPECIFIED)(day => JavaDayOfWeek.valueOf(day.name))
        val bytes = JavaAdScheduleInfo.newBuilder.setDayOfWeek(javaDay).build().toByteArray
        assertTrue(AdScheduleInfo.messageCodec.toByteArray(AdScheduleInfo(dayOfWeek = day)).sameElements(bytes)) &&
        assertTrue(AdScheduleInfo.messageCodec.parseFrom(bytes).dayOfWeek == day)
      }
    },
  )
}
