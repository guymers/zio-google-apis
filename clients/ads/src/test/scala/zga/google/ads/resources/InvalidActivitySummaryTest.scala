package zga.google.ads.resources

import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.checkAll

import java.time.Month

/**
 * `original_month_of_service` is an `optional` enum mapped to `Option[Month]`,
 * so the field is an `Option[Option[Month]]`: unset is `None` and `UNSPECIFIED`
 * is `Some(None)`.
 */
object InvalidActivitySummaryTest extends ZIOSpecDefault {
  import Invoice.InvalidActivitySummary
  import com.google.ads.googleads.v26.enums.MonthOfYearEnum.MonthOfYear as JavaMonthOfYear
  import com.google.ads.googleads.v26.resources.Invoice.InvalidActivitySummary as JavaInvalidActivitySummary

  override val spec = suite("Invoice.InvalidActivitySummary")(
    test("read and write") {
      val months: List[Option[Option[Month]]] = None :: Some(None) :: Month.values.toList.map(month => Some(Some(month)))
      checkAll(Gen.fromIterable(months)) { month =>
        val builder = JavaInvalidActivitySummary.newBuilder
        val bytes = month
          .fold(builder) { month =>
            builder.setOriginalMonthOfService(month.fold(JavaMonthOfYear.UNSPECIFIED)(month => JavaMonthOfYear.valueOf(month.name)))
          }
          .build()
          .toByteArray
        val value = InvalidActivitySummary(originalMonthOfService = month)
        assertTrue(InvalidActivitySummary.messageCodec.toByteArray(value).sameElements(bytes)) &&
        assertTrue(InvalidActivitySummary.messageCodec.parseFrom(bytes) == value)
      }
    },
  )
}
