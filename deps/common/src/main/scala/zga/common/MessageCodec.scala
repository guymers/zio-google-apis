package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.MethodDescriptor

import java.io.ByteArrayInputStream
import java.io.InputStream
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.quoted.*
import scala.util.Try

/**
 * Ability to serialize a message to/from protobuf bytes.
 */
sealed trait MessageCodec[T] extends FieldCodec[T] {

  def descriptor: Descriptors.Descriptor

  def toByteArray(value: T): Array[Byte]

  def parseFrom(bytes: Array[Byte]): T

  final def safeParseFrom[A](value: ByteString): Either[Throwable, T] = {
    Try(parseFrom(value.toByteArray)).toEither
  }

  override def toProto(field: Descriptors.FieldDescriptor, value: T) = {
    DynamicMessage.parseFrom(field.getMessageType, toByteArray(value))
  }

  override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
    parseFrom(value.asInstanceOf[DynamicMessage].toByteArray)
  }
}

object MessageCodec {

  def apply[T](using codec: MessageCodec[T]): MessageCodec[T] = codec

  def marshaller[T](using codec: MessageCodec[T]) = new MethodDescriptor.Marshaller[T] {
    override def stream(value: T) = new ByteArrayInputStream(codec.toByteArray(value))
    override def parse(stream: InputStream) = codec.parseFrom(stream.readAllBytes())
  }

  inline def derived[T](descriptor: Descriptors.Descriptor)(using mirror: Mirror.ProductOf[T]): MessageCodec[T] =
    ${ derivedImpl[T]('descriptor, 'mirror) }

  private def derivedImpl[T: Type](
    descriptor: Expr[Descriptors.Descriptor],
    mirror: Expr[Mirror.ProductOf[T]],
  )(using Quotes): Expr[MessageCodec[T]] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val fields = tpe.typeSymbol.caseFields
    val labels = fields.map(field => Expr(field.name))
    val codecs = fields.map { field =>
      val memberType = tpe.memberType(field)
      // a oneof parameter is an `Option` of a `Oneof`, which no `FieldCodec` encodes
      val fieldCodec = memberType.asType match {
        case '[t] => Expr.summon[FieldCodec[t]].map(_.asInstanceOf[Expr[FieldCodec[?] | OneofCodec[?]]])
      }
      fieldCodec.orElse {
        memberType.asType match {
          case '[Option[x]] => Expr.summon[OneofCodec[x & Oneof]].map(_.asInstanceOf[Expr[FieldCodec[?] | OneofCodec[?]]])
          case _ => None
        }
      }.getOrElse {
        report.errorAndAbort(s"${Type.show[T]} has no FieldCodec or OneofCodec for the field '${field.name}'")
      }
    }
    val labelsExpr = '{ Array[String](${ Varargs(labels) }*) }
    val codecsExpr = '{ Array[FieldCodec[?] | OneofCodec[?]](${ Varargs(codecs) }*) }
    '{ new ProductMessageCodec[T]($descriptor, $mirror, $labelsExpr, $codecsExpr) }
  }

  private sealed trait Member {

    /**
     * The field to set and its encoded value, or `None` when the element
     * carries no value.
     */
    def toProto(element: Any): Option[(Descriptors.FieldDescriptor, AnyRef)]
    def fromProto(message: DynamicMessage): Any
  }
  private object Member {

    final case class Field(field: Descriptors.FieldDescriptor, codec: FieldCodec[Any]) extends Member {
      override def toProto(element: Any) = Option(codec.toProto(field, element)).map(value => field -> value)
      override def fromProto(message: DynamicMessage) = {
        val present = !field.isRepeated && field.hasPresence && message.hasField(field)
        codec.fromProto(field, message.getField(field), present)
      }
    }

    final case class Oneof(oneof: Descriptors.OneofDescriptor, codec: OneofCodec[zga.common.Oneof]) extends Member {
      override def toProto(element: Any) = {
        codec.toProto(oneof, element.asInstanceOf[Option[zga.common.Oneof]])
      }
      override def fromProto(message: DynamicMessage) = {
        codec.fromProto(message, oneof)
      }
    }
  }

  private[zga] final class ProductMessageCodec[T](
    val descriptor: Descriptors.Descriptor,
    mirror: Mirror.ProductOf[T],
    labels: Array[String],
    _codecs: => Array[FieldCodec[?] | OneofCodec[?]],
  ) extends MessageCodec[T] {

    private lazy val members: Array[Member] = {
      // a proto3 `optional` field is a synthetic oneof, so only real oneofs replace a field parameter
      val oneofs = descriptor.getRealOneofs.asScala.map(oneof => FieldNames.toScalaName(oneof) -> oneof).toMap
      val fields = descriptor.getFields.asScala.map(field => FieldNames.toScalaName(field) -> field).toMap
      val codecs = _codecs
      labels.zipWithIndex.map { case (label, index) =>
        oneofs.get(label) match {
          case Some(oneof) => Member.Oneof(oneof, codecs(index).asInstanceOf[OneofCodec[zga.common.Oneof]])
          case None =>
            fields.get(label) match {
              case Some(field) => Member.Field(field, codecs(index).asInstanceOf[FieldCodec[Any]])
              case None => throw IllegalStateException(s"${descriptor.getFullName} has no field or oneof matching the case class parameter '$label'")
            }
        }
      }
    }

    override def toByteArray(value: T) = toMessage(value).toByteArray

    override def parseFrom(bytes: Array[Byte]) = fromMessage(DynamicMessage.parseFrom(descriptor, bytes))

    override def toProto(field: Descriptors.FieldDescriptor, value: T) = {
      val message = toMessage(value)
      if (message.getDescriptorForType eq field.getMessageType) {
        message
      } else {
        DynamicMessage.parseFrom(field.getMessageType, message.toByteArray)
      }
    }

    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
      val message = value.asInstanceOf[DynamicMessage]
      if (message.getDescriptorForType eq descriptor) {
        fromMessage(message)
      } else {
        fromMessage(DynamicMessage.parseFrom(descriptor, message.toByteArray))
      }
    }

    private def toMessage(value: T): DynamicMessage = {
      val product = value.asInstanceOf[Product]
      val builder = DynamicMessage.newBuilder(descriptor)
      var i = 0
      while (i < members.length) {
        members(i).toProto(product.productElement(i)).foreach { case (field, value) =>
          val _ = builder.setField(field, value)
        }
        i += 1
      }
      builder.build()
    }

    private def fromMessage(message: DynamicMessage): T = {
      val values = new Array[Any](members.length)
      var i = 0
      while (i < members.length) {
        values(i) = members(i).fromProto(message)
        i += 1
      }
      mirror.fromProduct(Tuple.fromArray(values))
    }
  }
}
