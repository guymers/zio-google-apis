package zga.common

import com.google.`type` as gt
import com.google.protobuf as pb
import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.check
import zio.test.checkAll

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.Month
import scala.util.Try

/**
 * The well-known types represented as `java.time` types encode to the same
 * bytes as protobuf-java, and read protobuf-java's bytes as the same value. A
 * value `java.time` cannot represent, which protobuf-java does not check for,
 * fails to read as an invalid message, as does an enum number `java.time` has
 * no value for.
 */
object JavaTimePropertyTest extends ZIOSpecDefault {

  // the ranges the protos document: a timestamp from 0001-01-01T00:00:00Z to
  // 9999-12-31T23:59:59.999999999Z, and a duration of up to 10,000 years
  private val MinTimestampSeconds = -62_135_596_800L
  private val MaxTimestampSeconds = 253_402_300_799L
  private val MaxDurationSeconds = 315_576_000_000L
  private val MaxNanos = 999_999_999

  override val spec = suite("java.time types match protobuf-java")(
    suite("Timestamp")(
      test("a valid timestamp writes and reads the bytes protobuf-java does") {
        check(
          edgesOr(Gen.long(MinTimestampSeconds, MaxTimestampSeconds), MinTimestampSeconds, MaxTimestampSeconds),
          edgesOr(Gen.int(0, MaxNanos), 0, MaxNanos),
        ) { (seconds, nanos) =>
          val value = Instant.ofEpochSecond(seconds, nanos.toLong)
          val java = pb.Timestamp.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(MessageCodec[Instant].toByteArray(value).sameElements(java)) &&
          assertTrue(MessageCodec[Instant].parseFrom(java) == value)
        }
      },
      test("any seconds and nanos read as java.time adds them, or fail as an invalid message") {
        val seconds = edgesOr(Gen.long(Instant.MIN.getEpochSecond, Instant.MAX.getEpochSecond), Long.MinValue, Long.MaxValue)
        check(seconds, anyNanos) { (seconds, nanos) =>
          val java = pb.Timestamp.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(read[Instant](java) == expected(Instant.ofEpochSecond(seconds, nanos.toLong)))
        }
      },
    ),
    suite("Duration")(
      test("a valid duration writes and reads the bytes protobuf-java does") {
        check(validDuration) { (seconds, nanos) =>
          val value = Duration.ofSeconds(seconds, nanos.toLong)
          val java = pb.Duration.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(MessageCodec[Duration].toByteArray(value).sameElements(java)) &&
          assertTrue(MessageCodec[Duration].parseFrom(java) == value)
        }
      },
      test("any seconds and nanos read as java.time adds them, or fail as an invalid message") {
        val seconds = edgesOr(Gen.long(-MaxDurationSeconds, MaxDurationSeconds), Long.MinValue, Long.MaxValue)
        check(seconds, anyNanos) { (seconds, nanos) =>
          val java = pb.Duration.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(read[Duration](java) == expected(Duration.ofSeconds(seconds, nanos.toLong)))
        }
      },
    ),
    // an enum field writes its number, so a mapped enum encodes as protobuf-java
    // does when its numbers are protobuf-java's
    suite("DayOfWeek")(
      test("reads each number as protobuf-java's value of that number, the unspecified value as None") {
        checkAll(Gen.int(-1, 9)) { number =>
          val read = Try(zga.google.`type`.DayOfWeek.fromValue(number).map(_.name)).toEither.left.map(_.getClass.getSimpleName)
          val java = Option(gt.DayOfWeek.forNumber(number)) match {
            case Some(value) => Right(Option.when(value != gt.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED)(value.name))
            case None => Left("InvalidProtocolBufferException")
          }
          assertTrue(read == java)
        }
      },
      test("writes each day as protobuf-java's number for it, None as the unspecified value") {
        checkAll(Gen.fromIterable(None +: DayOfWeek.values.toList.map(Some(_)))) { day =>
          val java = day.fold(gt.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED)(day => gt.DayOfWeek.valueOf(day.name))
          assertTrue(zga.google.`type`.DayOfWeek.value(day) == java.getNumber)
        }
      },
    ),
    suite("Month")(
      test("reads each number as protobuf-java's value of that number, the unspecified value as None") {
        checkAll(Gen.int(-1, 14)) { number =>
          val read = Try(zga.google.`type`.Month.fromValue(number).map(_.name)).toEither.left.map(_.getClass.getSimpleName)
          val java = Option(gt.Month.forNumber(number)) match {
            case Some(value) => Right(Option.when(value != gt.Month.MONTH_UNSPECIFIED)(value.name))
            case None => Left("InvalidProtocolBufferException")
          }
          assertTrue(read == java)
        }
      },
      test("writes each month as protobuf-java's number for it, None as the unspecified value") {
        checkAll(Gen.fromIterable(None +: Month.values.toList.map(Some(_)))) { month =>
          val java = month.fold(gt.Month.MONTH_UNSPECIFIED)(month => gt.Month.valueOf(month.name))
          assertTrue(zga.google.`type`.Month.value(month) == java.getNumber)
        }
      },
    ),
  )

  /**
   * The generated values, mixed with the edge values that random ones rarely
   * hit.
   */
  private def edgesOr[A](gen: Gen[Any, A], edges: A*) = Gen.oneOf(gen, Gen.elements(edges*))

  private val anyNanos = edgesOr(Gen.int(-2 * MaxNanos, 2 * MaxNanos), -MaxNanos - 1, MaxNanos + 1, Int.MinValue, Int.MaxValue)

  // a valid duration has seconds and nanos of the same sign
  private val validDuration = for {
    seconds <- edgesOr(Gen.long(-MaxDurationSeconds, MaxDurationSeconds), 0L, -MaxDurationSeconds, MaxDurationSeconds)
    nanos <-
      if (seconds > 0) edgesOr(Gen.int(0, MaxNanos), MaxNanos)
      else if (seconds < 0) edgesOr(Gen.int(-MaxNanos, 0), -MaxNanos)
      else edgesOr(Gen.int(-MaxNanos, MaxNanos), -MaxNanos, MaxNanos)
  } yield (seconds, nanos)

  /**
   * The value read from the bytes, or the name of the exception reading them
   * failed with.
   */
  private def read[A](bytes: Array[Byte])(using codec: MessageCodec[A]): Either[String, A] =
    Try(codec.parseFrom(bytes)).toEither.left.map(_.getClass.getSimpleName)

  /**
   * The value read should be, or a failure as an invalid message if it cannot
   * be built.
   */
  private def expected[A](value: => A): Either[String, A] =
    Try(value).toEither.left.map(_ => "InvalidProtocolBufferException")
}
