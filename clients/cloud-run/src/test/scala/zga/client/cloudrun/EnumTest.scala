package zga.client.cloudrun

import zga.common.MessageCodec
import zga.google.cloud.run.v2.Condition
import zio.test.Assertion.isLeft
import zio.test.Assertion.isRight
import zio.test.TestResultZIOOps
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.assertZIO
import zio.test.typeCheck

object EnumTest extends ZIOSpecDefault {

  private val codec = summon[MessageCodec[Condition]]

  private def roundTrip(condition: Condition): Condition = codec.parseFrom(codec.toByteArray(condition))

  override val spec = suite("Enum")(
    test("exports the recognized values onto the enum") {
      assertTrue(Condition.State.CONDITION_FAILED == Condition.State.Recognized.CONDITION_FAILED) &&
      assertTrue(Condition.State.Recognized.values.head == Condition.State.STATE_UNSPECIFIED)
    },
    test("looks up a known number") {
      assertTrue(Condition.State.fromValue(3) == Condition.State.CONDITION_FAILED)
    },
    test("keeps an unknown number") {
      Condition.State.fromValue(99) match {
        case unrecognized: Condition.State.Unrecognized => assertTrue(unrecognized.value == 99)
        case _ => assertTrue(false)
      }
    },
    test("cannot construct an unrecognized value directly") {
      // the valid snippet guards against the others failing for an unrelated reason
      assertZIO(typeCheck(
        "zga.google.cloud.run.v2.Condition.State.fromValue(99) match { case u: zga.google.cloud.run.v2.Condition.State.Unrecognized => u.value }",
      ))(isRight) &&
      assertZIO(typeCheck("zga.google.cloud.run.v2.Condition.State.Unrecognized(0)"))(isLeft) &&
      assertZIO(typeCheck(
        "zga.google.cloud.run.v2.Condition.State.fromValue(99) match { case u: zga.google.cloud.run.v2.Condition.State.Unrecognized => u.copy(value = 0) }",
      ))(isLeft)
    },
    test("defaults to the zero value") {
      assertTrue(Condition().state == Condition.State.STATE_UNSPECIFIED)
    },
    test("round trips a recognized value") {
      val condition = Condition(state = Condition.State.CONDITION_FAILED)
      assertTrue(roundTrip(condition) == condition)
    },
    test("round trips an unrecognized value") {
      val condition = Condition(state = Condition.State.fromValue(99))
      assertTrue(roundTrip(condition) == condition)
    },
    test("round trips an unrecognized value in a oneof") {
      val condition = Condition(reasons = Some(Condition.Reasons.Reason(Condition.CommonReason.fromValue(1234))))
      assertTrue(roundTrip(condition) == condition)
    },
  )
}
