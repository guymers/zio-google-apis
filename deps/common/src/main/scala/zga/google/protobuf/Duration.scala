package zga.google.protobuf

import zga.common.MessageCodec

/**
 * The codec of a `google.protobuf.Duration` represented as a
 * `java.time.Duration`.
 *
 * Protobuf stores a negative duration with negative nanos, while `java.time`
 * keeps the nanos positive and the seconds one less, so a negative duration is
 * written in the sign-normalized form that the proto requires.
 */
object Duration extends SecondsNanosCodec[java.time.Duration]("google.protobuf.Duration") {

  override private[zga] val defaultValue = java.time.Duration.ZERO

  override protected def seconds(value: java.time.Duration) = {
    val seconds = value.getSeconds
    if (seconds < 0 && value.getNano > 0) seconds + 1 else seconds
  }

  override protected def nanos(value: java.time.Duration) = {
    val nanos = value.getNano
    if (value.getSeconds < 0 && nanos > 0) nanos - 1000000000 else nanos
  }

  override protected def fromProto(seconds: Long, nanos: Int) = java.time.Duration.ofSeconds(seconds, nanos.toLong)

  inline given messageCodec: MessageCodec[java.time.Duration] = this
}
