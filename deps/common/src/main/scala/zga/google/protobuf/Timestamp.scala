package zga.google.protobuf

import zga.common.MessageCodec

import java.time.Instant

/**
 * The codec of a `google.protobuf.Timestamp` represented as a
 * `java.time.Instant`.
 */
object Timestamp extends SecondsNanosCodec[Instant]("google.protobuf.Timestamp") {

  override private[zga] val defaultValue = Instant.EPOCH

  override protected def seconds(value: Instant) = value.getEpochSecond

  override protected def nanos(value: Instant) = value.getNano

  override protected def fromProto(seconds: Long, nanos: Int) = Instant.ofEpochSecond(seconds, nanos.toLong)

  inline given messageCodec: MessageCodec[Instant] = this
}
