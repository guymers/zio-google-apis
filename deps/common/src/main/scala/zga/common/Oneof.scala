package zga.common

import com.google.protobuf.CodedOutputStream

/**
 * Parent of the sealed trait generated for each proto `oneof`. Each case of
 * that trait holds one member field and implements sizing and writing it.
 *
 * A message codec sizes and writes a oneof with a single call through this
 * trait, rather than matching on every member, which would make its methods
 * grow with the number of members.
 */
trait Oneof {

  private[zga] def serializedSize(sizes: Sizes): Int

  private[zga] def writeTo(output: CodedOutputStream, sizes: Sizes): Unit
}

private[zga] object Oneof {

  def serializedSize(value: Option[Oneof], sizes: Sizes): Int = value match {
    case Some(oneof) => oneof.serializedSize(sizes)
    case None => 0
  }

  def writeTo(output: CodedOutputStream, value: Option[Oneof], sizes: Sizes): Unit = value match {
    case Some(oneof) => oneof.writeTo(output, sizes)
    case None => ()
  }
}
