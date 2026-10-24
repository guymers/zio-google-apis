package zga.common

import com.google.protobuf.util.Durations
import com.google.protobuf.util.Timestamps
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

object JavaTimePropertyTest extends ZIOSpecDefault {
  import com.google.`type`.DayOfWeek as ProtobufDayOfWeek
  import com.google.`type`.Month as ProtobufMonth

  private val MinTimestampSeconds = Timestamps.MIN_VALUE.getSeconds
  private val MaxTimestampSeconds = Timestamps.MAX_VALUE.getSeconds

  private val MaxDurationSeconds = Durations.MAX_VALUE.getSeconds

  private val MaxNanos = Timestamps.MAX_VALUE.getNanos

  override val spec = suite("java.time types match protobuf-java")(
    suite("Timestamp")(
      test("writes and reads a valid value like protobuf-java") {
        check(
          withEdges(Gen.long(MinTimestampSeconds, MaxTimestampSeconds), MinTimestampSeconds, MaxTimestampSeconds),
          withEdges(Gen.int(0, MaxNanos), 0, MaxNanos),
        ) { (seconds, nanos) =>
          val value = Instant.ofEpochSecond(seconds, nanos.toLong)
          val java = pb.Timestamp.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(MessageCodec[Instant].toByteArray(value).sameElements(java)) &&
          assertTrue(MessageCodec[Instant].parseFrom(java) == value)
        }
      },
      test("reads any seconds and nanos like Instant.ofEpochSecond") {
        val seconds = withEdges(Gen.long(Instant.MIN.getEpochSecond, Instant.MAX.getEpochSecond), Long.MinValue, Long.MaxValue)
        check(seconds, anyNanos) { (seconds, nanos) =>
          val java = pb.Timestamp.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(read[Instant](java) == expected(Instant.ofEpochSecond(seconds, nanos.toLong)))
        }
      },
    ),
    suite("Duration")(
      test("writes and reads a valid value like protobuf-java") {
        check(validDuration) { (seconds, nanos) =>
          val value = Duration.ofSeconds(seconds, nanos.toLong)
          val java = pb.Duration.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(MessageCodec[Duration].toByteArray(value).sameElements(java)) &&
          assertTrue(MessageCodec[Duration].parseFrom(java) == value)
        }
      },
      test("reads any seconds and nanos like Duration.ofSeconds") {
        val seconds = withEdges(Gen.long(-MaxDurationSeconds, MaxDurationSeconds), Long.MinValue, Long.MaxValue)
        check(seconds, anyNanos) { (seconds, nanos) =>
          val java = pb.Duration.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
          assertTrue(read[Duration](java) == expected(Duration.ofSeconds(seconds, nanos.toLong)))
        }
      },
    ),
    suite("DayOfWeek")(
      test("reads any number like protobuf-java") {
        checkAll(Gen.int(-1, 9)) { number =>
          val read = Try(zga.google.`type`.DayOfWeek.fromValue(number).map(_.name)).toEither.left.map(_.getClass.getSimpleName)
          val java = Option(ProtobufDayOfWeek.forNumber(number)) match {
            case Some(value) => Right(Option.when(value != ProtobufDayOfWeek.DAY_OF_WEEK_UNSPECIFIED)(value.name))
            case None => Left("InvalidProtocolBufferException")
          }
          assertTrue(read == java)
        }
      },
      test("writes each value like protobuf-java") {
        checkAll(Gen.fromIterable(None +: DayOfWeek.values.toList.map(Some(_)))) { day =>
          val java = day.fold(ProtobufDayOfWeek.DAY_OF_WEEK_UNSPECIFIED)(day => ProtobufDayOfWeek.valueOf(day.name))
          assertTrue(zga.google.`type`.DayOfWeek.value(day) == java.getNumber)
        }
      },
    ),
    suite("Month")(
      test("reads any number like protobuf-java") {
        checkAll(Gen.int(-1, 14)) { number =>
          val read = Try(zga.google.`type`.Month.fromValue(number).map(_.name)).toEither.left.map(_.getClass.getSimpleName)
          val java = Option(ProtobufMonth.forNumber(number)) match {
            case Some(value) => Right(Option.when(value != ProtobufMonth.MONTH_UNSPECIFIED)(value.name))
            case None => Left("InvalidProtocolBufferException")
          }
          assertTrue(read == java)
        }
      },
      test("writes each value like protobuf-java") {
        checkAll(Gen.fromIterable(None +: Month.values.toList.map(Some(_)))) { month =>
          val java = month.fold(ProtobufMonth.MONTH_UNSPECIFIED)(month => ProtobufMonth.valueOf(month.name))
          assertTrue(zga.google.`type`.Month.value(month) == java.getNumber)
        }
      },
    ),
  )

  private lazy val anyNanos = withEdges(
    Gen.int(-2 * MaxNanos, 2 * MaxNanos),
    -MaxNanos - 1,
    MaxNanos + 1,
    Int.MinValue,
    Int.MaxValue,
  )

  // a valid duration has seconds and nanos of the same sign
  private lazy val validDuration = for {
    seconds <- withEdges(Gen.long(-MaxDurationSeconds, MaxDurationSeconds), 0L, -MaxDurationSeconds, MaxDurationSeconds)
    nanos <-
      if (seconds > 0) withEdges(Gen.int(0, MaxNanos), MaxNanos)
      else if (seconds < 0) withEdges(Gen.int(-MaxNanos, 0), -MaxNanos)
      else withEdges(Gen.int(-MaxNanos, MaxNanos), -MaxNanos, MaxNanos)
  } yield (seconds, nanos)

  // the ZIO generator has no bias so ensure edge cases are always tested
  private def withEdges[A](gen: Gen[Any, A], edges: A*) = Gen.oneOf(gen, Gen.elements(edges*))

  private def read[A](bytes: Array[Byte])(using codec: MessageCodec[A]): Either[String, A] =
    Try(codec.parseFrom(bytes)).toEither.left.map(_.getClass.getSimpleName)

  private def expected[A](value: => A): Either[String, A] =
    Try(value).toEither.left.map(_ => "InvalidProtocolBufferException")
}
