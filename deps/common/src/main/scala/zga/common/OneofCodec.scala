package zga.common

import com.google.protobuf.Descriptors
import com.google.protobuf.Message

import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonInline
import scala.deriving.Mirror

/**
 * Marker for a generated proto `oneof` type. Its cases are the member fields,
 * at most one of which a message holds.
 */
trait Oneof

trait OneofCodec[T <: Oneof] {

  /**
   * The field to set and its encoded value, or `None` when the oneof is unset.
   */
  def toProto(oneof: Descriptors.OneofDescriptor, value: Option[T]): Option[(Descriptors.FieldDescriptor, AnyRef)]

  def fromProto(message: Message, oneof: Descriptors.OneofDescriptor): Option[T]
}
object OneofCodec {

  def derived[T <: Oneof](cases: Case[?]*)(using sum: Mirror.SumOf[T]): OneofCodec[T] = new SumOneofCodec[T](sum, cases)

  final class SumOneofCodec[T <: Oneof](
    sum: Mirror.SumOf[T],
    cases: Seq[Case[?]],
  ) extends OneofCodec[T] {

    private val byNumber = cases.map(member => member.number -> member).toMap

    override def toProto(oneof: Descriptors.OneofDescriptor, value: Option[T]) = {
      value.map { oneofValue =>
        cases(sum.ordinal(oneofValue)).asInstanceOf[Case[T]].encode(oneof, oneofValue)
      }
    }

    override def fromProto(message: Message, oneof: Descriptors.OneofDescriptor) = {
      Option(message.getOneofFieldDescriptor(oneof)).flatMap { field =>
        byNumber.get(field.getNumber).map { member =>
          member.asInstanceOf[Case[T]].decode(field, message.getField(field))
        }
      }
    }
  }

  trait Case[C] {
    def number: Int
    def encode(oneof: Descriptors.OneofDescriptor, value: C): (Descriptors.FieldDescriptor, AnyRef)
    def decode(field: Descriptors.FieldDescriptor, value: Any): C
  }
  object Case {

    // a oneof case carries the single value of its member field, so the mirror
    // has exactly one element and one codec to derive for it
    inline def derived[C](fieldNumber: Int)(using product: Mirror.ProductOf[C]): Case[C] =
      inline erasedValue[product.MirroredElemTypes] match {
        case _: (element *: EmptyTuple) =>
          new Derived[C](fieldNumber, product, summonInline[FieldCodec[element]].asInstanceOf[FieldCodec[Any]])
        case _ => error("a oneof case must carry exactly one value")
      }

    final class Derived[C](
      val number: Int,
      product: Mirror.ProductOf[C],
      _codec: => FieldCodec[Any],
    ) extends Case[C] {
      private lazy val codec = _codec

      override def encode(oneof: Descriptors.OneofDescriptor, value: C) = {
        val field = oneof.getContainingType.findFieldByNumber(number)
        if (field == null || (field.getContainingOneof ne oneof)) {
          throw IllegalStateException(s"oneof ${oneof.getFullName} has no field with number $number")
        }
        (field, codec.toProto(field, value.asInstanceOf[Product].productElement(0)))
      }

      override def decode(field: Descriptors.FieldDescriptor, value: Any) = {
        product.fromProduct(Tuple1(codec.fromProto(field, value, true)))
      }
    }
  }
}
