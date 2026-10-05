package zga.common

import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message

import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

/**
 * Marker for a generated proto `oneof` type. Its cases are the member fields,
 * at most one of which a message holds.
 */
trait Oneof

sealed trait OneofCodec[T <: Oneof] {

  /**
   * Resolves each case against the member fields of `oneof`, failing if they do
   * not correspond one to one.
   */
  private[common] def bind(oneof: Descriptors.OneofDescriptor): OneofCodec.Bound[T]
}
object OneofCodec {

  inline def derived[T <: Oneof](cases: Case[?]*)(using sum: Mirror.SumOf[T]): OneofCodec[T] =
    sumCodec[T](constValue[sum.MirroredLabel], sum, runtimeClasses[sum.MirroredElemTypes], cases)

  private inline def runtimeClasses[Elements <: Tuple]: List[Class[?]] =
    inline erasedValue[Elements] match {
      case _: EmptyTuple => Nil
      case _: (element *: elements) => summonInline[ClassTag[element]].runtimeClass :: runtimeClasses[elements]
    }

  private def sumCodec[T <: Oneof](
    name: String,
    sum: Mirror.SumOf[T],
    classes: List[Class[?]],
    cases: Seq[Case[?]],
  ): OneofCodec[T] = new SumOneofCodec[T](name, sum, classes, cases)

  private final class SumOneofCodec[T <: Oneof](
    name: String,
    sum: Mirror.SumOf[T],
    classes: List[Class[?]],
    cases: Seq[Case[?]],
  ) extends OneofCodec[T] {

    // indexed by the sum's ordinal
    private val ordered: Array[Case[T]] = {
      val byClass: Map[Class[?], Case[T]] = cases.map(member => member.runtimeClass -> member.asInstanceOf[Case[T]]).toMap
      require(byClass.size == cases.size, s"oneof $name has more than one case for the same member")
      require(cases.size == classes.size, s"oneof $name has ${cases.size} cases for ${classes.size} members")
      classes.map { runtimeClass =>
        byClass.getOrElse(runtimeClass, throw IllegalArgumentException(s"oneof $name has no case for ${runtimeClass.getName}"))
      }.toArray
    }

    override private[common] def bind(oneof: Descriptors.OneofDescriptor) = {
      val fields = ordered.map { member =>
        val field = oneof.getContainingType.findFieldByNumber(member.number)
        if (field == null || (field.getContainingOneof ne oneof)) {
          throw IllegalStateException(s"oneof ${oneof.getFullName} has no field with number ${member.number}")
        }
        field
      }
      val unmatched = oneof.getFields.asScala.filterNot(field => fields.exists(_ eq field))
      if (unmatched.nonEmpty) {
        throw IllegalStateException(
          s"oneof ${oneof.getFullName} has no case in $name for the fields ${unmatched.map(_.getName).mkString(", ")}",
        )
      }
      new Bound[T](oneof, sum, ordered, fields)
    }
  }

  /**
   * A codec whose cases are resolved against the fields of one oneof.
   */
  private[common] final class Bound[T <: Oneof](
    oneof: Descriptors.OneofDescriptor,
    sum: Mirror.SumOf[T],
    cases: Array[Case[T]],
    fields: Array[Descriptors.FieldDescriptor],
  ) {

    // a field's index in its message to the ordinal of its case
    private val ordinals: Array[Int] = {
      val ordinals = Array.fill(oneof.getContainingType.getFields.size)(-1)
      fields.zipWithIndex.foreach { case (field, ordinal) => ordinals(field.getIndex) = ordinal }
      ordinals
    }

    def write(value: Option[T], builder: DynamicMessage.Builder): Unit = value match {
      case None => ()
      case Some(oneofValue) =>
        val ordinal = sum.ordinal(oneofValue)
        val field = fields(ordinal)
        val _ = builder.setField(field, cases(ordinal).encode(field, oneofValue))
    }

    def read(message: Message): Option[T] = {
      val field = message.getOneofFieldDescriptor(oneof)
      if (field == null) {
        None
      } else {
        Some(cases(ordinals(field.getIndex)).decode(field, message.getField(field)))
      }
    }
  }

  final class Case[C] private (
    private[common] val number: Int,
    private[common] val runtimeClass: Class[?],
    product: Mirror.ProductOf[C],
    _codec: => FieldCodec[Any],
  ) {
    private lazy val codec = _codec

    private[common] def encode(field: Descriptors.FieldDescriptor, value: C): AnyRef = {
      codec.toProto(field, value.asInstanceOf[Product].productElement(0))
    }

    private[common] def decode(field: Descriptors.FieldDescriptor, value: Any): C = {
      product.fromProduct(Tuple1(codec.fromProto(field, value, true)))
    }
  }
  object Case {

    // a oneof case carries the single value of its member field, so the mirror
    // has exactly one element and one codec to derive for it
    inline def derived[C](fieldNumber: Int)(using product: Mirror.ProductOf[C]): Case[C] =
      inline erasedValue[product.MirroredElemTypes] match {
        case _: (element *: EmptyTuple) =>
          create[C](
            fieldNumber,
            summonInline[ClassTag[C]].runtimeClass,
            product,
            summonInline[FieldCodec[element]].asInstanceOf[FieldCodec[Any]],
          )
        case _ => error("a oneof case must carry exactly one value")
      }

    private def create[C](
      number: Int,
      runtimeClass: Class[?],
      product: Mirror.ProductOf[C],
      codec: => FieldCodec[Any],
    ): Case[C] = new Case[C](number, runtimeClass, product, codec)
  }
}
