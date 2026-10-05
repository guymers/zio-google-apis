package zga.common

import com.google.protobuf.Descriptors
import com.google.protobuf.Message

import scala.compiletime.summonAll
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*

/**
 * Marker for a generated proto `oneof` type. Its cases are the member fields,
 * at most one of which a message holds.
 */
trait Oneof

/**
 * Codec for a proto `oneof`, which holds at most one of its member fields.
 *
 * `None` is a oneof with none of its members set, which is distinct from a
 * member set to its default value.
 */
trait OneofCodec[T <: Oneof] {
  def toProto(builder: Message.Builder, oneof: Descriptors.OneofDescriptor, value: Option[T]): Unit
  def fromProto(message: Message, oneof: Descriptors.OneofDescriptor): Option[T]
}

object OneofCodec {

  /**
   * Derives a codec from the oneof's cases. `cases` holds one
   * [[OneofCaseCodec]] per case, in the order the cases are declared, which is
   * the order `Mirror.SumOf.ordinal` numbers them.
   */
  def derived[T <: Oneof](cases: OneofCaseCodec[?]*)(using sum: Mirror.SumOf[T]): OneofCodec[T] = {
    val byNumber = cases.map(member => member.number -> member).toMap
    new OneofCodec[T] {

      override def toProto(builder: Message.Builder, oneof: Descriptors.OneofDescriptor, value: Option[T]): Unit = {
        value.foreach { oneofValue =>
          cases(sum.ordinal(oneofValue)).asInstanceOf[OneofCaseCodec[T]].encode(builder, oneof, oneofValue)
        }
      }

      override def fromProto(message: Message, oneof: Descriptors.OneofDescriptor): Option[T] = {
        val number = OneofCodec.setFieldNumber(message, oneof)
        byNumber.get(number).map { member =>
          val field = OneofCodec.field(oneof, number)
          member.asInstanceOf[OneofCaseCodec[T]].decode(field, message.getField(field))
        }
      }
    }
  }

  /** The member field of `oneof` with the given number. */
  def field(oneof: Descriptors.OneofDescriptor, number: Int): Descriptors.FieldDescriptor = {
    oneof.getFields.asScala.find(_.getNumber == number).getOrElse {
      throw IllegalStateException(s"oneof ${oneof.getFullName} has no field with number $number")
    }
  }

  /**
   * The number of the member field of `oneof` that is set, or 0 when none is.
   */
  def setFieldNumber(message: Message, oneof: Descriptors.OneofDescriptor): Int = {
    oneof.getFields.asScala.find(field => message.hasField(field)).fold(0)(_.getNumber)
  }
}

/**
 * Codec for one case of a oneof: the proto field it maps to, and how to read
 * and write the single value the case carries.
 */
trait OneofCaseCodec[C] {
  def number: Int
  def encode(builder: Message.Builder, oneof: Descriptors.OneofDescriptor, value: C): Unit
  def decode(field: Descriptors.FieldDescriptor, value: Any): C
}

object OneofCaseCodec {

  /**
   * Derives a codec from the case's single element, whose [[FieldCodec]] is
   * summoned from the case's own type.
   */
  inline def derived[C](fieldNumber: Int)(using product: Mirror.ProductOf[C]): OneofCaseCodec[C] = {
    lazy val codecs = summonAll[Tuple.Map[product.MirroredElemTypes, FieldCodec]]
      .productIterator
      .map(_.asInstanceOf[FieldCodec[Any]])
      .toArray
    new Derived[C](fieldNumber, product, codecs)
  }

  // public because `derived`, being inline, constructs it at the call site
  final class Derived[C](
    val number: Int,
    product: Mirror.ProductOf[C],
    codecs: Array[FieldCodec[Any]],
  ) extends OneofCaseCodec[C] {

    override def encode(builder: Message.Builder, oneof: Descriptors.OneofDescriptor, value: C): Unit = {
      val field = OneofCodec.field(oneof, number)
      val encoded = codecs(0).toProto(field, value.asInstanceOf[Product].productElement(0))
      val _ = builder.setField(field, encoded)
    }

    override def decode(field: Descriptors.FieldDescriptor, value: Any): C = {
      product.fromProduct(Tuple1(codecs(0).fromProto(field, value, true)))
    }
  }
}
