package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import zio.Chunk

import scala.jdk.CollectionConverters.*

/**
 * Codec for a single message field value.
 *
 * The `present` flag is only meaningful for types with presence (`Option`) and
 * is ignored by the other instances.
 */
trait FieldCodec[T] {
  def toProto(field: Descriptors.FieldDescriptor, value: T): AnyRef
  def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean): T
}

object FieldCodec {

  given intCodec: FieldCodec[Int] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Int) = Int.box(value)
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[Int]
  }

  given longCodec: FieldCodec[Long] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Long) = Long.box(value)
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[Long]
  }

  given floatCodec: FieldCodec[Float] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Float) = Float.box(value)
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[Float]
  }

  given doubleCodec: FieldCodec[Double] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Double) = Double.box(value)
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[Double]
  }

  given booleanCodec: FieldCodec[Boolean] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Boolean) = Boolean.box(value)
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[Boolean]
  }

  given stringCodec: FieldCodec[String] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: String) = value
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[String]
  }

  given bytesCodec: FieldCodec[ByteString] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: ByteString) = value
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = value.asInstanceOf[ByteString]
  }

  given optionCodec[T](using codec: FieldCodec[T]): FieldCodec[Option[T]] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Option[T]) = value match {
      case None => null
      case Some(v) => codec.toProto(field, v)
    }
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
      Option.when(present)(codec.fromProto(field, value, true))
    }
  }

  given chunkCodec[T](using codec: FieldCodec[T]): FieldCodec[Chunk[T]] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Chunk[T]) = {
      value.map(element => codec.toProto(field, element)).asJava
    }
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
      value.asInstanceOf[java.util.List[?]].asScala.map(element => codec.fromProto(field, element, true)).to(Chunk)
    }
  }

  given mapCodec[K, V](using keyCodec: FieldCodec[K], valueCodec: FieldCodec[V]): FieldCodec[Map[K, V]] with {
    override def toProto(field: Descriptors.FieldDescriptor, value: Map[K, V]) = {
      val entryDescriptor = field.getMessageType
      val keyField = entryDescriptor.findFieldByName("key")
      val valueField = entryDescriptor.findFieldByName("value")
      // sort by key so that two equal maps always serialize to the same bytes
      value.toList
        .sortWith { case ((left, _), (right, _)) => compareMapKeys(keyField, left, right) < 0 }
        .map { case (key, entryValue) =>
          val entry = DynamicMessage.newBuilder(entryDescriptor)
          val _ = entry.setField(keyField, keyCodec.toProto(keyField, key))
          val _ = entry.setField(valueField, valueCodec.toProto(valueField, entryValue))
          entry.build()
        }
        .asJava
    }
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
      val entryDescriptor = field.getMessageType
      val keyField = entryDescriptor.findFieldByName("key")
      val valueField = entryDescriptor.findFieldByName("value")
      value.asInstanceOf[java.util.List[?]].asScala.map { entry =>
        val message = entry.asInstanceOf[DynamicMessage]
        keyCodec.fromProto(keyField, message.getField(keyField), true) ->
          valueCodec.fromProto(valueField, message.getField(valueField), true)
      }.toMap
    }
  }

  private def compareMapKeys[K](keyField: Descriptors.FieldDescriptor, left: K, right: K) = {
    val fieldType = keyField.getType
    keyField.getJavaType match {
      case Descriptors.FieldDescriptor.JavaType.INT =>
        if (fieldType == Descriptors.FieldDescriptor.Type.UINT32 || fieldType == Descriptors.FieldDescriptor.Type.FIXED32) {
          java.lang.Integer.compareUnsigned(left.asInstanceOf[Int], right.asInstanceOf[Int])
        } else {
          java.lang.Integer.compare(left.asInstanceOf[Int], right.asInstanceOf[Int])
        }
      case Descriptors.FieldDescriptor.JavaType.LONG =>
        if (fieldType == Descriptors.FieldDescriptor.Type.UINT64 || fieldType == Descriptors.FieldDescriptor.Type.FIXED64) {
          java.lang.Long.compareUnsigned(left.asInstanceOf[Long], right.asInstanceOf[Long])
        } else {
          java.lang.Long.compare(left.asInstanceOf[Long], right.asInstanceOf[Long])
        }
      case Descriptors.FieldDescriptor.JavaType.BOOLEAN =>
        java.lang.Boolean.compare(left.asInstanceOf[Boolean], right.asInstanceOf[Boolean])
      case Descriptors.FieldDescriptor.JavaType.STRING =>
        left.asInstanceOf[String].compareTo(right.asInstanceOf[String])
      case javaType =>
        throw IllegalStateException(s"${keyField.getFullName} has key type $javaType, which is not a valid map key")
    }
  }

  def enumCodec[E](fromValue: Int => E, toValue: E => Int): FieldCodec[E] = new FieldCodec[E] {
    // an open enum's unrecognized value has no descriptor of its own, so one is
    // created for it rather than the field being silently dropped
    override def toProto(field: Descriptors.FieldDescriptor, value: E) = {
      field.getEnumType.findValueByNumberCreatingIfUnknown(toValue(value))
    }
    override def fromProto(field: Descriptors.FieldDescriptor, value: Any, present: Boolean) = {
      fromValue(value.asInstanceOf[Descriptors.EnumValueDescriptor].getNumber)
    }
  }
}
