package zga.google.protobuf

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.InvalidProtocolBufferException
import zga.common.MessageCodec
import zga.common.ProtoReader
import zga.common.Sizes

import java.time.DateTimeException

/**
 * The codec of a well-known type of an `int64 seconds = 1` and an
 * `int32 nanos = 2` field.
 */
private[protobuf] abstract class SecondsNanosCodec[T](protoFullName: String) extends MessageCodec[T] {

  protected def seconds(value: T): Long

  protected def nanos(value: T): Int

  protected def fromProto(seconds: Long, nanos: Int): T

  override private[zga] def serializedSize(value: T, sizes: Sizes): Int = {
    val s = seconds(value)
    val n = nanos(value)
    var size = 0
    if (s != 0L) {
      size += 1 + CodedOutputStream.computeInt64SizeNoTag(s)
    }
    if (n != 0) {
      size += 1 + CodedOutputStream.computeInt32SizeNoTag(n)
    }
    size
  }

  override private[zga] def writeTo(output: CodedOutputStream, value: T, sizes: Sizes): Unit = {
    val s = seconds(value)
    val n = nanos(value)
    if (s != 0L) {
      output.writeTag(1, 0)
      output.writeInt64NoTag(s)
    }
    if (n != 0) {
      output.writeTag(2, 0)
      output.writeInt32NoTag(n)
    }
  }

  /**
   * Reads the fields into a `java.time` value, failing to read the message when
   * `java.time` cannot represent them, unlike protobuf-java which reads any
   * seconds and nanos.
   */
  override private[zga] def readFrom(reader: ProtoReader, value: T): T = {
    val input = reader.input
    var s = seconds(value)
    var n = nanos(value)
    reader.fields {
      case 8 =>
        s = input.readInt64()
        true
      case 16 =>
        n = input.readInt32()
        true
      case _ => false
    }
    try {
      fromProto(s, n)
    } catch {
      case e: (DateTimeException | ArithmeticException) =>
        throw InvalidProtocolBufferException(s"Invalid $protoFullName: seconds=$s nanos=$n", e)
    }
  }
}
