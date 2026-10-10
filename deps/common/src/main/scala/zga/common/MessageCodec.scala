package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.KnownLength
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

  final def safeParseFrom(value: ByteString): Either[Throwable, T] = {
    Try(fromMessage(DynamicMessage.parseFrom(descriptor, value))).toEither
  }

  /**
   * Reads a message parsed against [[descriptor]].
   */
  private[common] def fromMessage(message: DynamicMessage): T
}

object MessageCodec {

  def apply[T](using codec: MessageCodec[T]): MessageCodec[T] = codec

  def marshaller[T](using codec: MessageCodec[T]): MethodDescriptor.Marshaller[T] = new MethodDescriptor.Marshaller[T] {
    override def stream(value: T) = new KnownLengthInputStream(codec.toByteArray(value))
    override def parse(stream: InputStream) = codec.fromMessage(DynamicMessage.parseFrom(codec.descriptor, stream))
  }

  // gRPC frames a stream of known length without first buffering it to find the length
  private final class KnownLengthInputStream(bytes: Array[Byte]) extends ByteArrayInputStream(bytes) with KnownLength

  inline def derived[T](descriptor: Descriptors.Descriptor)(using mirror: Mirror.ProductOf[T]): MessageCodec[T] =
    ${ derivedImpl[T]('descriptor, 'mirror) }

  private def derivedImpl[T: Type](
    descriptor: Expr[Descriptors.Descriptor],
    mirror: Expr[Mirror.ProductOf[T]],
  )(using Quotes): Expr[MessageCodec[T]] = {
    val (labels, codecs) = productArguments[T]
    '{ new ProductMessageCodec[T]($descriptor, $mirror, $labels, $codecs) }
  }

  private[zga] type ParameterCodec = FieldCodec[?] | OneofCodec[?] | MessageGroupCodec[?]

  private[common] def productArguments[T: Type](using Quotes): (Expr[Array[String]], Expr[Array[ParameterCodec]]) = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val fields = tpe.typeSymbol.caseFields
    val labels = fields.map(field => Expr(field.name))
    val labelsExpr = '{ Array[String](${ Varargs(labels) }*) }
    def search(codecType: TypeRepr): Either[String, Expr[ParameterCodec]] = {
      Implicits.search(codecType) match {
        case success: ImplicitSearchSuccess => Right(success.tree.asExprOf[ParameterCodec])
        case failure: ImplicitSearchFailure => Left(failure.explanation)
      }
    }

    val codecs = fields.map { field =>
      val memberType = tpe.memberType(field)
      // a oneof parameter is an `Option` of a `Oneof`, which no `FieldCodec` encodes
      val codec = memberType.asType match {
        case '[Option[x]] if TypeRepr.of[x] <:< TypeRepr.of[Oneof] => search(TypeRepr.of[OneofCodec[x & Oneof]])
        // the common case first; report the `FieldCodec` failure as it is the more useful explanation
        case '[t] =>
          search(TypeRepr.of[FieldCodec[t]]).left.flatMap(explanation =>
            search(TypeRepr.of[MessageGroupCodec[t]]).left.map(_ => explanation),
          )
      }
      codec.fold(
        explanation =>
          report.errorAndAbort(
            s"${Type.show[T]} has no FieldCodec, OneofCodec or MessageGroupCodec for the field '${field.name}': $explanation",
          ),
        identity,
      )
    }
    val codecsExpr = '{ Array[ParameterCodec](${ Varargs(codecs) }*) }
    (labelsExpr, codecsExpr)
  }

  private sealed trait Member {
    def toProto(element: Any, builder: DynamicMessage.Builder): Unit
    def fromProto(message: DynamicMessage): Any

    /**
     * The message fields this member reads and writes.
     */
    def fields: Seq[Descriptors.FieldDescriptor]
  }
  private object Member {

    case class Field(field: Descriptors.FieldDescriptor, codec: FieldCodec[Any]) extends Member {
      override def toProto(element: Any, builder: DynamicMessage.Builder) = {
        val value = codec.toProto(field, element)
        if (value != null) {
          val _ = builder.setField(field, value)
        }
      }
      override def fromProto(message: DynamicMessage) = {
        // a repeated field never has presence
        val present = field.hasPresence && message.hasField(field)
        codec.fromProto(field, message.getField(field), present)
      }
      override def fields = List(field)
    }

    case class Oneof(oneof: Descriptors.OneofDescriptor, codec: OneofCodec.Bound[zga.common.Oneof]) extends Member {
      override def toProto(element: Any, builder: DynamicMessage.Builder) = {
        codec.write(element.asInstanceOf[Option[zga.common.Oneof]], builder)
      }
      override def fromProto(message: DynamicMessage) = {
        codec.read(message)
      }
      override def fields = oneof.getFields.asScala.toList
    }

    /**
     * A nested case class holding a subset of the message's fields, generated
     * when a message has more fields than one case class can have.
     */
    case class Group(codec: MessageGroupCodec[Any]) extends Member {
      override def toProto(element: Any, builder: DynamicMessage.Builder) = {
        codec.writeFields(element, builder)
      }
      override def fromProto(message: DynamicMessage) = {
        codec.readFields(message)
      }
      override def fields = codec.fields
    }
  }

  /**
   * The values of a case class's parameters, without copying them into a tuple.
   */
  private final class ArrayProduct(values: Array[Any]) extends Product {
    override def productArity = values.length
    override def productElement(n: Int) = values(n)
    override def canEqual(that: Any) = false
  }

  /**
   * @param complete
   *   whether the parameters must cover every field of the message, rather than
   *   the subset a group holds
   */
  private[zga] abstract class ProductCodec[T](
    val descriptor: Descriptors.Descriptor,
    mirror: Mirror.ProductOf[T],
    labels: Array[String],
    _codecs: => Array[ParameterCodec],
    complete: Boolean,
  ) {

    private lazy val members: Array[Member] = {
      // a proto3 `optional` field is a synthetic oneof, so only real oneofs replace a field parameter
      val oneofs = descriptor.getRealOneofs.asScala.map(oneof => FieldNames.toScalaName(oneof) -> oneof).toMap
      val fieldsByName = descriptor.getFields.asScala.map(field => FieldNames.toScalaName(field) -> field).toMap
      val codecs = _codecs
      val members = labels.lazyZip(codecs).map { (label, parameterCodec) =>
        parameterCodec match {
          case group: MessageGroupCodec[?] =>
            require(group.descriptor eq descriptor, s"${descriptor.getFullName} has a group '$label' with a different descriptor")
            Member.Group(group.asInstanceOf[MessageGroupCodec[Any]])
          case codec =>
            oneofs.get(label) match {
              case Some(oneof) => Member.Oneof(oneof, codec.asInstanceOf[OneofCodec[zga.common.Oneof]].bind(oneof))
              case None =>
                fieldsByName.get(label) match {
                  case Some(field) => Member.Field(field, codec.asInstanceOf[FieldCodec[Any]])
                  case None =>
                    throw IllegalStateException(
                      s"${descriptor.getFullName} has no field or oneof matching the case class parameter '$label'",
                    )
                }
            }
        }
      }
      if (complete) checkCoverage(members)
      members
    }

    private def checkCoverage(members: Array[Member]): Unit = {
      val covered = members.toList.flatMap(_.fields).groupBy(identity)
      val repeated = covered.collect { case (field, occurrences) if occurrences.size > 1 => field.getName }
      if (repeated.nonEmpty) {
        throw IllegalStateException(
          s"${descriptor.getFullName} has fields read by more than one case class parameter: ${repeated.toList.sorted.mkString(", ")}",
        )
      }
      val uncovered = descriptor.getFields.asScala.filterNot(covered.contains).map(_.getName)
      if (uncovered.nonEmpty) {
        throw IllegalStateException(
          s"${descriptor.getFullName} has fields with no case class parameter: ${uncovered.mkString(", ")}",
        )
      }
    }

    private[common] def fields: Seq[Descriptors.FieldDescriptor] = members.toList.flatMap(_.fields)

    private[common] def writeFields(value: T, builder: DynamicMessage.Builder): Unit = {
      val product = value.asInstanceOf[Product]
      var i = 0
      while (i < members.length) {
        members(i).toProto(product.productElement(i), builder)
        i += 1
      }
    }

    private[common] def readFields(message: DynamicMessage): T = {
      val values = new Array[Any](members.length)
      var i = 0
      while (i < members.length) {
        values(i) = members(i).fromProto(message)
        i += 1
      }
      mirror.fromProduct(ArrayProduct(values))
    }
  }

  private[zga] final class ProductMessageCodec[T](
    descriptor: Descriptors.Descriptor,
    mirror: Mirror.ProductOf[T],
    labels: Array[String],
    codecs: => Array[ParameterCodec],
  ) extends ProductCodec[T](descriptor, mirror, labels, codecs, complete = true) with MessageCodec[T] {

    override def toByteArray(value: T) = toMessage(value).toByteArray

    override def parseFrom(bytes: Array[Byte]) = readFields(DynamicMessage.parseFrom(descriptor, bytes))

    override private[common] def fromMessage(message: DynamicMessage) = readFields(message)

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
        readFields(message)
      } else {
        readFields(DynamicMessage.parseFrom(descriptor, message.toByteArray))
      }
    }

    private def toMessage(value: T): DynamicMessage = {
      val builder = DynamicMessage.newBuilder(descriptor)
      writeFields(value, builder)
      builder.build()
    }
  }
}

/**
 * A product containing a subset of a message's fields, rather than a complete
 * protobuf message.
 */
sealed trait MessageGroupCodec[T] {
  def descriptor: Descriptors.Descriptor
  private[common] def writeFields(value: T, builder: DynamicMessage.Builder): Unit
  private[common] def readFields(message: DynamicMessage): T
  private[common] def fields: Seq[Descriptors.FieldDescriptor]
}

object MessageGroupCodec {
  inline def derived[T](descriptor: Descriptors.Descriptor)(using mirror: Mirror.ProductOf[T]): MessageGroupCodec[T] =
    ${ derivedImpl[T]('descriptor, 'mirror) }

  private def derivedImpl[T: Type](
    descriptor: Expr[Descriptors.Descriptor],
    mirror: Expr[Mirror.ProductOf[T]],
  )(using Quotes): Expr[MessageGroupCodec[T]] = {
    val (labels, codecs) = MessageCodec.productArguments[T]
    '{ new ProductGroupCodec[T]($descriptor, $mirror, $labels, $codecs) }
  }

  private[zga] final class ProductGroupCodec[T](
    descriptor: Descriptors.Descriptor,
    mirror: Mirror.ProductOf[T],
    labels: Array[String],
    codecs: => Array[MessageCodec.ParameterCodec],
  ) extends MessageCodec.ProductCodec[T](descriptor, mirror, labels, codecs, complete = false) with MessageGroupCodec[T]
}
