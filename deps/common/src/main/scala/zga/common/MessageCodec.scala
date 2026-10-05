package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.MethodDescriptor

import java.io.ByteArrayInputStream
import java.io.InputStream
import scala.compiletime.constValueTuple
import scala.compiletime.summonAll
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Ability to serialize a message to/from protobuf bytes.
 */
trait MessageCodec[T] extends FieldCodec[T] {

  def descriptor: Descriptors.Descriptor

  def toByteArray(value: T): Array[Byte]

  def parseFrom(bytes: Array[Byte]): T

  final def safeParseFrom[A](value: ByteString): Either[Throwable, T] = {
    Try(parseFrom(value.toByteArray)).toEither
  }

  override def toProto(field: Descriptors.FieldDescriptor, value: T): AnyRef = {
    DynamicMessage.parseFrom(field.getMessageType, toByteArray(value))
  }

  override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean): T = {
    parseFrom(value.asInstanceOf[DynamicMessage].toByteArray)
  }
}

object MessageCodec {

  def apply[T](using codec: MessageCodec[T]): MessageCodec[T] = codec

  def forMessage[T](using codec: MessageCodec[T]) = new MethodDescriptor.Marshaller[T] {
    override def stream(value: T) = new ByteArrayInputStream(codec.toByteArray(value))
    override def parse(stream: InputStream) = codec.parseFrom(stream.readAllBytes())
  }

  inline def derived[T](descriptor: Descriptors.Descriptor)(using mirror: Mirror.ProductOf[T]): MessageCodec[T] = {
    val labels = constValueTuple[mirror.MirroredElemLabels].productIterator.map(_.toString).toArray
    lazy val members = summonAll[Tuple.Map[mirror.MirroredElemTypes, MemberCodec]].productIterator.map(_.asInstanceOf[MemberCodec[Any]]).toArray
    new ProductMessageCodec[T](descriptor, mirror, labels, members)
  }

  /** The proto a generated case class parameter maps to: a field or a oneof. */
  private sealed trait Target
  private object Target {
    final case class Field(field: Descriptors.FieldDescriptor) extends Target
    final case class Oneof(oneof: Descriptors.OneofDescriptor) extends Target
  }

  final class ProductMessageCodec[T](
    val descriptor: Descriptors.Descriptor,
    mirror: Mirror.ProductOf[T],
    labels: Array[String],
    _members: => Array[MemberCodec[Any]],
  ) extends MessageCodec[T] {

    private lazy val members: Array[MemberCodec[Any]] = _members

    private lazy val targets: Array[Target] = {
      // a proto3 `optional` field is a synthetic oneof, so only real oneofs replace a field parameter
      val oneofs = descriptor.getRealOneofs.asScala.map(oneof => FieldNames.toScalaName(oneof) -> oneof).toMap
      val fields = descriptor.getFields.asScala.map(field => FieldNames.toScalaName(field) -> field).toMap
      labels.map { label =>
        oneofs.get(label) match {
          case Some(oneof) => Target.Oneof(oneof)
          case None =>
            fields.get(label) match {
              case Some(field) => Target.Field(field)
              case None => throw IllegalStateException(s"${descriptor.getFullName} has no field or oneof matching the case class parameter '$label'")
            }
        }
      }
    }

    override def toByteArray(value: T) = toMessage(value).toByteArray

    override def parseFrom(bytes: Array[Byte]) = fromMessage(DynamicMessage.parseFrom(descriptor, bytes))

    override def toProto(field: Descriptors.FieldDescriptor, value: T): AnyRef = {
      val message = toMessage(value)
      if (message.getDescriptorForType eq field.getMessageType) {
        message
      } else {
        DynamicMessage.parseFrom(field.getMessageType, message.toByteArray)
      }
    }

    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean): T = {
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
      while (i < targets.length) {
        (targets(i), members(i)) match {
          case (Target.Field(field), FieldMemberCodec(codec)) =>
            val converted = codec.toProto(field, product.productElement(i))
            if (converted != null) {
              val _ = builder.setField(field, converted)
            }
          case (Target.Oneof(oneof), OneofMemberCodec(codec)) =>
            codec
              .asInstanceOf[OneofCodec[Oneof]]
              .toProto(builder, oneof, product.productElement(i).asInstanceOf[Option[Oneof]])
          case (target, member) =>
            throw IllegalStateException(s"${descriptor.getFullName} cannot encode $target with $member")
        }
        i += 1
      }
      builder.build()
    }

    private def fromMessage(message: DynamicMessage): T = {
      val values = new Array[Any](targets.length)
      var i = 0
      while (i < targets.length) {
        (targets(i), members(i)) match {
          case (Target.Field(field), FieldMemberCodec(codec)) =>
            val present = !field.isRepeated && field.hasPresence && message.hasField(field)
            values(i) = codec.fromProto(field, message.getField(field), present)
          case (Target.Oneof(oneof), OneofMemberCodec(codec)) =>
            values(i) = codec.fromProto(message, oneof)
          case (target, member) =>
            throw IllegalStateException(s"${descriptor.getFullName} cannot decode $target with $member")
        }
        i += 1
      }
      mirror.fromProduct(Tuple.fromArray(values))
    }
  }
}
