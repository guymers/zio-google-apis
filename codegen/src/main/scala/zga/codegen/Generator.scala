package zga.codegen

import com.google.protobuf.Descriptors

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

object Generator {

  private val deprecatedAnnotation = """@scala.deprecated(message="in proto file", "")"""

  private val ReservedEnumMemberNames = Set(
    "fromValue",
    "valueMap",
    "fieldCodec",
    "values",
    "valueOf",
    "fromOrdinal",
    "toString",
    "hashCode",
    "clone",
    "finalize",
    "notify",
    "notifyAll",
    "wait",
  )

  private val ReservedFieldNames = Set(
    "clone",
    "finalize",
    "hashCode",
    "notify",
    "notifyAll",
    "productArity",
    "productElementNames",
    "productIterator",
    "productPrefix",
    "toString",
    "wait",
  )

  def fileName(f: Descriptors.FileDescriptor): String = {
    (f.scalaPackageParts :+ s"${f.protoFileName}.scala").mkString("/")
  }

  def printFile(f: Descriptors.FileDescriptor) = {
    val descriptor = printFileDescriptor(f)
    // a proto with neither `package` nor `option java_package` keeps its types in
    // the default package, which has no `package` declaration
    val packageDeclaration = if (f.scalaPackage.isEmpty) "" else s"package ${f.scalaPackage}\n"
    s"""${Comments.header(f).map(Comments.formatAsMultiLineComment(_)).getOrElse("")}
      |$packageDeclaration
      |${f.getEnumTypes.asScala.map(printEnum(_)).mkString("\n")}
      |${f.getMessageTypes.asScala.map(printMessage(_)).mkString("\n")}
      |$descriptor
      |""".stripMargin
  }

  private def printFileDescriptor(f: Descriptors.FileDescriptor) = {
    val proto = f.toProto.toBuilder.clearSourceCodeInfo().build()
    val descriptor = descriptorLiteral(proto.toByteArray)
    val dependencies = f.getDependencies.asScala
      .map(d => s"${Utils.rootQualified(d.scalaPackage)}.${d.descriptorObjectName}.javaDescriptor")
      .mkString(",\n        ")
    s"""object ${f.descriptorObjectName} {
      |  val javaDescriptor: _root_.com.google.protobuf.Descriptors.FileDescriptor = {
      |    val proto = _root_.com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(
      |      _root_.java.util.Base64.getDecoder.decode(
      |        $descriptor
      |      )
      |    )
      |    _root_.com.google.protobuf.Descriptors.FileDescriptor.buildFrom(
      |      proto,
      |      _root_.scala.Array[_root_.com.google.protobuf.Descriptors.FileDescriptor](
      |        $dependencies
      |      ),
      |      true
      |    )
      |  }
      |}""".stripMargin
  }

  private val DescriptorChunkSize = 8192

  /**
   * Encodes the descriptor as a Scala expression producing the Base64 text.
   *
   * The JVM class file format limits a string constant to 65535 bytes, which a
   * large proto's descriptor can exceed, so the text is emitted as chunks that
   * are concatenated at runtime. The chunks are joined with `mkString` rather
   * than `+`, as the compiler folds `+` of literals back into one constant.
   */
  private[codegen] def descriptorLiteral(bytes: Array[Byte]): String = {
    val chunks = java.util.Base64.getEncoder
      .encodeToString(bytes)
      .grouped(DescriptorChunkSize)
      .map(chunk => s""""$chunk"""")
      .toList
    chunks match {
      case chunk :: Nil => chunk
      case _ => chunks.mkString("_root_.scala.Array(\n", ",\n", ",\n).mkString")
    }
  }

  private def printEnum(e: Descriptors.EnumDescriptor) = {
    if (e.isClosed) printClosedEnum(e) else printOpenEnum(e)
  }

  private def printEnumValues(e: Descriptors.EnumDescriptor, reserved: Set[String], parent: String) = {
    def printEnumValue(v: Descriptors.EnumValueDescriptor) = {
      if (reserved.contains(v.getName)) {
        throw IllegalArgumentException(
          s"${e.getFullName} has a value named '${v.getName}', which collides with a member of the generated enum",
        )
      }
      List(
        v.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)),
        Option(v.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
        Some(s"case ${Utils.escapeScalaKeyword(v.getName)} extends $parent(${v.getNumber})"),
      ).flatten
    }

    e.getValues.asScala.flatMap(printEnumValue(_))
  }

  private def enumComment(e: Descriptors.EnumDescriptor) = {
    List(
      e.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)),
      Option(e.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
    ).flatten.mkString("\n")
  }

  /**
   * An open enum (proto3, or an editions enum without `enum_type = CLOSED`)
   * keeps a number it does not know. The known values are a Scala enum,
   * exported so they can be referred to as `E.A`, and an unknown number is an
   * `Unrecognized` case class.
   */
  private def printOpenEnum(e: Descriptors.EnumDescriptor) = {
    if (e.getName == "Unrecognized") {
      throw IllegalArgumentException(s"${e.getFullName} is named 'Unrecognized', which collides with its generated unrecognized value class")
    }
    val enumName = Utils.escapeScalaKeyword(e.getName)
    val enumType = e.scalaType
    val values = printEnumValues(e, ReservedEnumMemberNames ++ Set("Recognized", "Unrecognized"), "Recognized")

    s"""
      |${enumComment(e)}
      |sealed trait $enumName derives _root_.scala.CanEqual {
      |  def value: _root_.scala.Int
      |}
      |object $enumName {
      |  enum Recognized(val value: _root_.scala.Int) extends $enumType {
      |    ${values.mkString("\n    ")}
      |  }
      |  export Recognized.*
      |
      |  case class Unrecognized private[$enumName] (value: _root_.scala.Int) extends $enumType
      |
      |  private val valueMap = Recognized.values.map(v => v.value -> v).toMap
      |  def fromValue(v: _root_.scala.Int): $enumType = valueMap.getOrElse(v, Unrecognized(v))
      |
      |  given fieldCodec: _root_.zga.common.FieldCodec[$enumType] =
      |    _root_.zga.common.FieldCodec.enumCodec(
      |      (value: _root_.scala.Int) => fromValue(value),
      |      (value: $enumType) => value.value,
      |    )
      |}
      |""".stripMargin
  }

  /**
   * A closed enum (proto2, or an editions enum with `enum_type = CLOSED`).
   * protobuf-java stores a number it does not know in the message's unknown
   * fields rather than the enum field, so there is no unknown value to keep.
   */
  private def printClosedEnum(e: Descriptors.EnumDescriptor) = {
    val enumName = Utils.escapeScalaKeyword(e.getName)
    val values = printEnumValues(e, ReservedEnumMemberNames, enumName)

    s"""
      |${enumComment(e)}
      |enum $enumName(val value: _root_.scala.Int) derives _root_.scala.CanEqual {
      |  ${values.mkString("\n  ")}
      |}
      |object $enumName {
      |  private val valueMap = $enumName.values.map(v => v.value -> v).toMap
      |  def fromValue(v: _root_.scala.Int): _root_.scala.Option[$enumName] = valueMap.get(v)
      |
      |  given fieldCodec: _root_.zga.common.FieldCodec[$enumName] =
      |    _root_.zga.common.FieldCodec.enumCodec(
      |      (value: _root_.scala.Int) => $enumName.fromValue(value).getOrElse($enumName.values.head),
      |      (value: $enumName) => value.value,
      |    )
      |}
      |""".stripMargin
  }

  private def printMessage(m: Descriptors.Descriptor): String = {
    validateFieldNames(m)

    def printField(f: Descriptors.FieldDescriptor) = List(
      Option(f.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
      Some {
        val default =
          if (f.isMarkedRequired && !f.isCollection) "" else s" = ${f.scalaDefaultValue}"
        s"${Utils.escapeScalaKeyword(f.scalaName)}: ${f.scalaTypeName}$default,"
      },
    ).flatten

    def printOneofParam(o: Descriptors.OneofDescriptor) = {
      val default = if (o.isMarkedRequired) "" else " = _root_.scala.None"
      List(s"${o.scalaName}: _root_.scala.Option[${o.scalaType}]$default,")
    }

    val messageName = Utils.escapeScalaKeyword(m.getName)
    val oneofs = realOneofs(m)
    val members = messageMembers(m)

    val scaladoc = {
      val comment = m.comment.getOrElse(m.getName)
      val params = members.flatMap {
        case Left(f) => f.comment.map(Utils.escapeScalaKeyword(f.scalaName) -> _)
        case Right(o) => o.comment.map(Utils.escapeScalaKeyword(o.scalaName) -> _)
      }.to(ListMap)
      Comments.formatAsScaladoc(comment, params)
    }

    val memberDoc = List(
      Some(scaladoc),
      Option(m.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
    ).flatten.mkString("\n")

    val parameters = members.flatMap {
      case Left(f) => printField(f)
      case Right(o) => printOneofParam(o)
    }

    s"""
      |${memberDoc}
      |case class $messageName(
      |  ${parameters.mkString("\n  ")}
      |)
      |object $messageName {
      |${oneofs.map(printOneof(_)).mkString("\n")}
      |${m.getEnumTypes.asScala.map(printEnum(_)).mkString("\n")}
      |${m.getNestedTypes.asScala.filterNot(_.getOptions.getMapEntry).map(printMessage(_)).mkString("\n")}
      |${printMessageCodec(m)}
      |}
      |""".stripMargin
  }

  private def realOneofs(m: Descriptors.Descriptor) = m.getRealOneofs.asScala.toList

  /**
   * A message's generated case class parameters in declaration order: each
   * field that is not in a oneof, and each oneof, positioned at its first
   * field.
   */
  private def messageMembers(
    m: Descriptors.Descriptor,
  ): List[Either[Descriptors.FieldDescriptor, Descriptors.OneofDescriptor]] = {
    val emitted = scala.collection.mutable.Set.empty[Int]
    m.fields.toList.flatMap { field =>
      type Member = Either[Descriptors.FieldDescriptor, Descriptors.OneofDescriptor]
      Option(field.getRealContainingOneof) match {
        case None => Some(Left(field): Member)
        case Some(oneof) => Option.when(emitted.add(oneof.getIndex))(Right(oneof): Member)
      }
    }
  }

  /**
   * A proto `oneof` becomes a sealed trait with a case class per member field,
   * plus a single `Option` case class parameter.
   */
  private def printOneof(o: Descriptors.OneofDescriptor): String = {
    val oneofType = o.scalaType
    val fields = o.getFields.asScala.toList

    val cases = fields.map { field =>
      s"case class ${field.oneofCaseName}(value: ${field.scalaType}) extends $oneofType"
    }

    val caseCodecs = fields.map { field =>
      s"_root_.zga.common.OneofCaseCodec.derived[$oneofType.${field.oneofCaseName}](${field.getNumber})"
    }

    s"""
      |${o.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)).getOrElse("")}
      |sealed trait ${o.scalaTypeName} extends _root_.zga.common.Oneof
      |object ${o.scalaTypeName} {
      |  ${cases.mkString("\n  ")}
      |
      |  given oneofCodec: _root_.zga.common.OneofCodec[$oneofType] =
      |    _root_.zga.common.OneofCodec.derived[$oneofType](
      |      ${caseCodecs.mkString(",\n      ")},
      |    )
      |}
      |""".stripMargin
  }

  /**
   * A field's Scala name has to work as a case class parameter. Two fields
   * cannot share one: a proto may declare both `foo_bar` and `fooBar`, which
   * both become `fooBar`.
   */
  private def validateFieldNames(m: Descriptors.Descriptor): Unit = {
    val fields = m.fields

    fields.groupBy(_.scalaName).collectFirst {
      case (name, duplicates) if duplicates.length > 1 =>
        (name, duplicates)
    }.foreach { case (name, duplicates) =>
      val protoNames = duplicates.map(_.getName).sorted.mkString("'", "', '", "'")
      throw IllegalArgumentException(
        s"${m.getFullName} has fields $protoNames, which all map to the Scala name '$name'",
      )
    }

    fields.find(f => ReservedFieldNames.contains(f.scalaName)).foreach { field =>
      throw IllegalArgumentException(
        s"${m.getFullName}.${field.getName} maps to the Scala name '${field.scalaName}', which collides with a member of the generated case class",
      )
    }

    validateOneofs(m)
  }

  /**
   * A oneof becomes a parameter and a nested type, so its name is checked
   * against the other parameters and nested types of the message, and each
   * member field's case name against the other cases of the same oneof.
   */
  private def validateOneofs(m: Descriptors.Descriptor): Unit = {
    val oneofs = realOneofs(m)
    val fieldNames = m.fields.filter(_.getRealContainingOneof == null).map(_.scalaName).toSet

    oneofs.foreach { oneof =>
      if (fieldNames.contains(oneof.scalaName)) {
        throw IllegalArgumentException(
          s"${m.getFullName} has a oneof '${oneof.getName}' that maps to the Scala name '${oneof.scalaName}', which collides with a field",
        )
      }
    }

    oneofs.groupBy(_.scalaName).collectFirst {
      case (name, duplicates) if duplicates.length > 1 => name
    }.foreach { name =>
      throw IllegalArgumentException(s"${m.getFullName} has oneofs that all map to the Scala name '$name'")
    }

    val nestedTypeNames = (
      m.getNestedTypes.asScala.filterNot(_.getOptions.getMapEntry).map(_.getName) ++
        m.getEnumTypes.asScala.map(_.getName)
    ).map(Utils.escapeScalaKeyword(_)).toSet
    oneofs.foreach { oneof =>
      if (nestedTypeNames.contains(oneof.scalaTypeName)) {
        throw IllegalArgumentException(
          s"${m.getFullName} has a oneof '${oneof.getName}' that maps to the Scala name '${oneof.scalaTypeName}', which collides with a nested type",
        )
      }
    }

    oneofs.foreach { oneof =>
      val caseNames = oneof.getFields.asScala.map(_.oneofCaseName)
      caseNames.groupBy(identity).collectFirst {
        case (name, duplicates) if duplicates.length > 1 => name
      }.foreach { name =>
        throw IllegalArgumentException(s"${m.getFullName} has oneof '${oneof.getName}' fields that all map to the Scala case name '$name'")
      }
      if (caseNames.contains(oneof.scalaTypeName)) {
        throw IllegalArgumentException(
          s"${m.getFullName} has a oneof '${oneof.getName}' with a field that maps to the Scala name '${oneof.scalaTypeName}', which collides with the oneof itself",
        )
      }
    }
  }

  private def printMessageCodec(m: Descriptors.Descriptor): String = {
    val scalaType = m.scalaType
    s"""given messageCodec: _root_.zga.common.MessageCodec[$scalaType] =
      |  _root_.zga.common.MessageCodec.derived[$scalaType](${messageDescriptorRef(m)})""".stripMargin
  }

  private def messageDescriptorRef(m: Descriptors.Descriptor): String = {
    val file = m.getFile
    val base = s"${Utils.rootQualified(file.scalaPackage)}.${file.descriptorObjectName}.javaDescriptor.getMessageTypes"

    def path(d: Descriptors.Descriptor): List[Int] =
      if (d.getContainingType == null) List(d.getIndex) else path(d.getContainingType) :+ d.getIndex

    val indices = path(m)
    indices.tail.foldLeft(s"$base.get(${indices.head})")((ref, index) => s"$ref.getNestedTypes.get($index)")
  }

}
