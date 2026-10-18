package zga.codegen

import com.google.api.FieldBehavior
import com.google.api.FieldBehaviorProto
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionLite

import scala.jdk.CollectionConverters.*

extension (f: Descriptors.FileDescriptor) {

  def protoFileName: String = f.getFullName.split('/').filter(_.nonEmpty).last

  def sourcePackage: String = Option(f.getOptions.getJavaPackage).filter(_.nonEmpty).getOrElse(f.getPackage)

  def scalaPackageParts: List[String] = Utils.generatedPackageParts(sourcePackage)

  def scalaPackage: String = scalaPackageParts.map(Utils.escapeScalaKeyword(_)).mkString(".")
}

extension (d: Descriptors.Descriptor) {

  def comment: Option[String] = Comments.comment(d.getFile, sourcePath)

  /**
   * A message's fields, failing codegen for group and proto2 `required` fields.
   * `getType` is used rather than `getLiteType`, which returns the unrelated
   * `WireFormat.FieldType`.
   */
  def fields: Seq[Descriptors.FieldDescriptor] = {
    val fields = d.getFields.asScala.toSeq
    fields.find(_.getType == Descriptors.FieldDescriptor.Type.GROUP).foreach { field =>
      throw IllegalArgumentException(
        s"${d.getFullName}.${field.getName} is a group field, which is not supported",
      )
    }
    fields.find(_.isRequired).foreach { field =>
      throw IllegalArgumentException(
        s"${d.getFullName}.${field.getName} is a proto2 required field, which is not supported",
      )
    }
    fields
  }

  def isTopLevel: Boolean = d.getContainingType == null

  def parent: Option[Descriptors.Descriptor] = Option(d.getContainingType)

  /**
   * True when the message has no fields, and so is generated as a plain object
   * rather than a class.
   */
  def isEmpty: Boolean = d.getFields.isEmpty

  /**
   * The path of the message's generated class, or of its generated object.
   */
  def scalaPath: String = {
    val name = Utils.escapeScalaKeyword(d.getName)
    parent.fold(Utils.rootQualified(d.getFile.scalaPackage))(_.scalaPath) + s".$name"
  }

  /**
   * The Scala type of the message. A message without fields is an object, so
   * its type is the singleton type of that object.
   */
  def scalaType: String = if (d.isEmpty) s"$scalaPath.type" else scalaPath

  def sourcePath: Seq[Int] = {
    if (d.isTopLevel) {
      Seq(FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER, d.getIndex)
    } else {
      d.getContainingType.sourcePath ++ Seq(DescriptorProto.NESTED_TYPE_FIELD_NUMBER, d.getIndex)
    }
  }
}

extension (f: Descriptors.FieldDescriptor) {

  def comment: Option[String] = Comments.comment(f.getFile, sourcePath)

  /**
   * The name of the generated case class parameter for this field.
   */
  def scalaName: String = FieldNames.toScalaName(f)

  /**
   * True when the field carries the `(google.api.field_behavior) = REQUIRED`
   * annotation.
   */
  def isRequiredByFieldBehavior: Boolean = {
    // `getExtension` has three overloads, Scala cannot disambiguate so have to call using the base extension type
    val behaviors = f.getOptions.getExtension(
      FieldBehaviorProto.fieldBehavior: ExtensionLite[FieldOptions, java.util.List[FieldBehavior]],
    )
    behaviors.asScala.exists(_.getNumber == FieldBehavior.REQUIRED.getNumber)
  }

  def isCollection: Boolean = f.isMapField || f.isRepeated

  def isMessage = f.getType == Descriptors.FieldDescriptor.Type.MESSAGE

  /**
   * The name of the generated oneof case class for this field, which is only
   * meaningful when the field belongs to a oneof.
   */
  def oneofCaseName: String = Utils.escapeScalaKeyword(Utils.pascalCase(f.getName))

  def scalaType: String = f.getJavaType match {
    case Descriptors.FieldDescriptor.JavaType.INT => "_root_.scala.Int"
    case Descriptors.FieldDescriptor.JavaType.LONG => "_root_.scala.Long"
    case Descriptors.FieldDescriptor.JavaType.FLOAT => "_root_.scala.Float"
    case Descriptors.FieldDescriptor.JavaType.DOUBLE => "_root_.scala.Double"
    case Descriptors.FieldDescriptor.JavaType.BOOLEAN => "_root_.scala.Boolean"
    case Descriptors.FieldDescriptor.JavaType.BYTE_STRING => "_root_.com.google.protobuf.ByteString"
    case Descriptors.FieldDescriptor.JavaType.STRING => "_root_.scala.Predef.String"
    case Descriptors.FieldDescriptor.JavaType.MESSAGE => f.getMessageType.scalaType
    case Descriptors.FieldDescriptor.JavaType.ENUM => f.getEnumType.scalaType
  }

  def scalaTypeName: String = {
    if (f.isMapField) {
      val entry = f.getMessageType
      val key = entry.findFieldByName("key").scalaType
      val value = entry.findFieldByName("value").scalaType
      s"_root_.scala.collection.immutable.Map[$key, $value]"
    } else if (f.isRepeated) {
      s"_root_.zio.Chunk[$scalaType]"
    } else if (f.hasPresence) {
      s"_root_.scala.Option[$scalaType]"
    } else {
      scalaType
    }
  }

  def scalaDefaultValue: String = {
    if (f.isMapField) {
      "_root_.scala.collection.immutable.Map.empty"
    } else if (f.isRepeated) {
      "_root_.zio.Chunk.empty"
    } else if (f.hasPresence) {
      "_root_.scala.None"
    } else {
      typeDefaultValue
    }
  }

  /**
   * The default value of the field's type, whatever its cardinality or
   * presence: zero, empty, a closed enum's first value, an open enum's zero or
   * a message's default.
   */
  def typeDefaultValue: String = f.getJavaType match {
    case Descriptors.FieldDescriptor.JavaType.INT => "0"
    case Descriptors.FieldDescriptor.JavaType.LONG => "0L"
    case Descriptors.FieldDescriptor.JavaType.FLOAT => "0.0f"
    case Descriptors.FieldDescriptor.JavaType.DOUBLE => "0.0d"
    case Descriptors.FieldDescriptor.JavaType.BOOLEAN => "false"
    case Descriptors.FieldDescriptor.JavaType.BYTE_STRING => "_root_.com.google.protobuf.ByteString.EMPTY"
    case Descriptors.FieldDescriptor.JavaType.STRING => "\"\""
    case Descriptors.FieldDescriptor.JavaType.MESSAGE => s"${f.getMessageType.scalaPath}.messageCodec.defaultValue"
    case Descriptors.FieldDescriptor.JavaType.ENUM =>
      if (f.getEnumType.isClosed) s"$scalaType.fromOrdinal(0)" else s"$scalaType.fromValue(0)"
  }

  def sourcePath: Seq[Int] = {
    f.getContainingType.sourcePath ++ Seq(DescriptorProto.FIELD_FIELD_NUMBER, f.getIndex)
  }
}

extension (o: Descriptors.OneofDescriptor) {

  def comment: Option[String] = Comments.comment(o.getFile, sourcePath)

  def scalaName: String = FieldNames.toScalaName(o)

  def scalaTypeName: String = Utils.escapeScalaKeyword(Utils.pascalCase(o.getName))

  def scalaType: String = o.getContainingType.scalaPath + s".$scalaTypeName"

  /**
   * A oneof is required when every member that is not deprecated is marked
   * required, which is how Google APIs mark a oneof that must be set: a oneof
   * is never `required` in protobuf itself, and has no options of its own, so
   * the annotation can only live on its members.
   */
  def isMarkedRequired: Boolean = {
    val members = o.getFields.asScala.filterNot(_.getOptions.getDeprecated)
    members.nonEmpty && members.forall(_.isRequiredByFieldBehavior)
  }

  def sourcePath: Seq[Int] = {
    o.getContainingType.sourcePath ++ Seq(DescriptorProto.ONEOF_DECL_FIELD_NUMBER, o.getIndex)
  }
}

extension (e: Descriptors.EnumDescriptor) {

  def comment: Option[String] = Comments.comment(e.getFile, sourcePath)

  def isTopLevel: Boolean = e.getContainingType == null

  def parent: Option[Descriptors.Descriptor] = Option(e.getContainingType)

  def scalaType: String = {
    val name = Utils.escapeScalaKeyword(e.getName)
    parent.fold(Utils.rootQualified(e.getFile.scalaPackage))(_.scalaPath) + s".$name"
  }

  def sourcePath: Seq[Int] = {
    if (e.isTopLevel) {
      Seq(FileDescriptorProto.ENUM_TYPE_FIELD_NUMBER, e.getIndex)
    } else {
      e.getContainingType.sourcePath ++ Seq(DescriptorProto.ENUM_TYPE_FIELD_NUMBER, e.getIndex)
    }
  }
}

extension (v: Descriptors.EnumValueDescriptor) {
  def comment: Option[String] = Comments.comment(v.getFile, sourcePath)

  def sourcePath: Seq[Int] = {
    v.getType.sourcePath ++ Seq(EnumDescriptorProto.VALUE_FIELD_NUMBER, v.getIndex)
  }
}

extension (s: Descriptors.ServiceDescriptor) {
  def comment: Option[String] = Comments.comment(s.getFile, sourcePath)

  def sourcePath: Seq[Int] = {
    Seq(FileDescriptorProto.SERVICE_FIELD_NUMBER, s.getIndex)
  }
}

extension (m: Descriptors.MethodDescriptor) {
  def comment: Option[String] = Comments.comment(m.getFile, sourcePath)

  def sourcePath: Seq[Int] = {
    Seq(
      FileDescriptorProto.SERVICE_FIELD_NUMBER,
      m.getService.getIndex,
      ServiceDescriptorProto.METHOD_FIELD_NUMBER,
      m.getIndex,
    )
  }
}
