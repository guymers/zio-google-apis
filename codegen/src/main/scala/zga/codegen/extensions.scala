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
import zga.common.FieldNames

import scala.jdk.CollectionConverters.*

extension (f: Descriptors.FileDescriptor) {

  def protoFileName: String = f.getFullName.split('/').filter(_.nonEmpty).last

  def scalaPackageParts: List[String] = {
    val pkg = Option(f.getOptions.getJavaPackage).filter(_.nonEmpty).getOrElse(f.getPackage)
    Utils.generatedPackageParts(pkg)
  }

  def scalaPackage: String = {
    scalaPackageParts.map(Utils.escapeScalaKeyword(_)).mkString(".")
  }

  def descriptorObjectName: String = FileNames.descriptorObjectName(f)

}

private object FileNames {

  // referenced by every generated message's codec, so computed once per file
  val descriptorObjectName: Descriptors.FileDescriptor => String = Memoize { f =>
    val base = Option(f.getOptions.getJavaOuterClassname).filter(_.nonEmpty).getOrElse {
      Utils.snakeCaseToCamelCase(f.protoFileName.stripSuffix(".proto"))
    }
    // the object sits alongside the file's top level messages and enums
    val used = f.getMessageTypes.asScala.map(_.getName) ++ f.getEnumTypes.asScala.map(_.getName)
    val name = s"${base}Descriptors"
    if (used.contains(name)) s"${base}OuterClassDescriptors" else name
  }
}

extension (d: Descriptors.Descriptor) {

  def comment: Option[String] = Comments.comment(d.getFile, sourcePath)

  /**
   * A message's fields, failing codegen for group fields. `getType` (rather
   * than `getLiteType`, which returns the unrelated `WireFormat.FieldType`)
   * reports `GROUP` for both a proto2 group and an editions `DELIMITED` field.
   */
  def fields: Seq[Descriptors.FieldDescriptor] = {
    val fields = d.getFields.asScala.toSeq
    fields.find(_.getType == Descriptors.FieldDescriptor.Type.GROUP).foreach { field =>
      throw IllegalArgumentException(
        s"${d.getFullName}.${field.getName} is a group field, which is not supported",
      )
    }
    fields
  }

  def isTopLevel: Boolean = d.getContainingType == null

  def parent: Option[Descriptors.Descriptor] = Option(d.getContainingType)

  def scalaType: String = {
    val name = Utils.escapeScalaKeyword(d.getName)
    parent.fold(Utils.rootQualified(d.getFile.scalaPackage))(_.scalaType) + s".$name"
  }

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
   * The name of the generated case class parameter for this field, following
   * the same rule the codec uses to map a parameter back to its field.
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

  def isMarkedRequired: Boolean = f.isRequired || f.isRequiredByFieldBehavior

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
      f.getJavaType match {
        case Descriptors.FieldDescriptor.JavaType.INT => "0"
        case Descriptors.FieldDescriptor.JavaType.LONG => "0L"
        case Descriptors.FieldDescriptor.JavaType.FLOAT => "0.0f"
        case Descriptors.FieldDescriptor.JavaType.DOUBLE => "0.0d"
        case Descriptors.FieldDescriptor.JavaType.BOOLEAN => "false"
        case Descriptors.FieldDescriptor.JavaType.BYTE_STRING => "_root_.com.google.protobuf.ByteString.EMPTY"
        case Descriptors.FieldDescriptor.JavaType.STRING => "\"\""
        case Descriptors.FieldDescriptor.JavaType.MESSAGE => "_root_.scala.None"
        case Descriptors.FieldDescriptor.JavaType.ENUM =>
          if (f.getEnumType.isClosed) s"${scalaType}.fromValue(0).getOrElse(${scalaType}.values.head)"
          else s"${scalaType}.fromValue(0)"
      }
    }
  }

  def sourcePath: Seq[Int] = {
    f.getContainingType.sourcePath ++ Seq(DescriptorProto.FIELD_FIELD_NUMBER, f.getIndex)
  }
}

extension (o: Descriptors.OneofDescriptor) {

  def comment: Option[String] = Comments.comment(o.getFile, sourcePath)

  def scalaName: String = FieldNames.toScalaName(o)

  def scalaTypeName: String = Utils.escapeScalaKeyword(Utils.pascalCase(o.getName))

  def scalaType: String = o.getContainingType.scalaType + s".$scalaTypeName"

  /**
   * A oneof is required when every member that is not deprecated is marked
   * required, which is how Google APIs mark a oneof that must be set: a oneof
   * is never `required` in protobuf itself, and has no options of its own, so
   * the annotation can only live on its members.
   */
  def isMarkedRequired: Boolean = {
    val members = o.getFields.asScala.filterNot(_.getOptions.getDeprecated)
    members.nonEmpty && members.forall(_.isMarkedRequired)
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
    parent.fold(Utils.rootQualified(e.getFile.scalaPackage))(_.scalaType) + s".$name"
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
