package zga.google.ads.common

import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.check
import zio.test.checkAll

import java.time.Month
import scala.util.Try

object YearMonthTest extends ZIOSpecDefault {
  import com.google.ads.googleads.v26.common.YearMonth as JavaYearMonth
  import com.google.ads.googleads.v26.enums.MonthOfYearEnum.MonthOfYear as JavaMonthOfYear

  private val month = Gen.option(Gen.elements(Month.values.toSeq*))

  override val spec = suite("YearMonth")(
    test("read") {
      checkAll(Gen.int(-1, 15)) { number =>
        val bytes = JavaYearMonth.newBuilder.setMonthValue(number).build().toByteArray
        val read = Try(YearMonth.messageCodec.parseFrom(bytes).month).toEither.left.map(_.getClass.getSimpleName)
        val expected = Option(JavaMonthOfYear.forNumber(number)) match {
          case Some(JavaMonthOfYear.UNKNOWN) | None => Left("InvalidProtocolBufferException")
          case Some(JavaMonthOfYear.UNSPECIFIED) => Right(None)
          case Some(month) => Right(Some(Month.valueOf(month.name)))
        }
        assertTrue(read == expected)
      }
    },
    test("write") {
      check(Gen.long, month) { (year, month) =>
        val javaMonth = month.fold(JavaMonthOfYear.UNSPECIFIED) { month =>
          JavaMonthOfYear.valueOf(month.name)
        }
        val bytes = JavaYearMonth.newBuilder.setYear(year).setMonth(javaMonth).build().toByteArray
        val value = YearMonth(year = year, month = month)
        assertTrue(YearMonth.messageCodec.toByteArray(value).sameElements(bytes)) &&
        assertTrue(YearMonth.messageCodec.parseFrom(bytes) == value)
      }
    },
  )
}
