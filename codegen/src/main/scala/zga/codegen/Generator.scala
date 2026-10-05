package zga.codegen

import com.google.protobuf.Descriptors

import scala.collection.immutable.ListMap
import scala.collection.mutable
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

  /**
   * The JVM limits a method's parameter list to 254 slots, and a `Long` or a
   * `Double` takes two. A message over the limit is generated as consecutive
   * chunks, each small enough to be one case class, and the message exports the
   * chunks' fields so they read as if the message were one case class. The
   * message's codec flattens the chunks back onto the same proto fields.
   */
  private val MaxParameterSlots = 254

  private def isDoubleSlot(f: Descriptors.FieldDescriptor) = {
    !f.isCollection &&
    !f.hasPresence &&
    (f.getJavaType match {
      case Descriptors.FieldDescriptor.JavaType.LONG | Descriptors.FieldDescriptor.JavaType.DOUBLE => true
      case _ => false
    })
  }

  private def parameterSlots(f: Descriptors.FieldDescriptor) = if (isDoubleSlot(f)) 2 else 1

  private enum MessageMember {
    case Field(field: Descriptors.FieldDescriptor)
    case Oneof(oneof: Descriptors.OneofDescriptor)
    case Group(name: String, parameterName: String, members: List[MessageMember])

    def scalaName: String = this match {
      case Field(field) => field.scalaName
      case Oneof(oneof) => oneof.scalaName
      case Group(_, parameterName, _) => parameterName
    }

    def slots: Int = this match {
      case Field(field) => parameterSlots(field)
      case _ => 1
    }

    def hasDefault: Boolean = this match {
      case Field(field) => !(field.isMarkedRequired && !field.isCollection)
      case Oneof(oneof) => !oneof.isMarkedRequired
      case Group(_, _, members) => members.forall(_.hasDefault)
    }

    def comment: Option[String] = this match {
      case Field(field) => field.comment
      case Oneof(oneof) => oneof.comment
      case Group(name, _, _) => Some(s"Fields in $name.")
    }
  }

  private def printParameter(m: Descriptors.Descriptor, member: MessageMember): List[String] = {
    val (scalaType, defaultValue, deprecated) = member match {
      case MessageMember.Field(field) => (field.scalaTypeName, field.scalaDefaultValue, field.getOptions.getDeprecated)
      case MessageMember.Oneof(oneof) => (s"_root_.scala.Option[${oneof.scalaType}]", "_root_.scala.None", false)
      case MessageMember.Group(name, _, _) => (s"${m.scalaType}.$name", s"${m.scalaType}.$name()", false)
    }
    val default = if (member.hasDefault) s" = $defaultValue" else ""
    List(
      Option.when(deprecated)(deprecatedAnnotation),
      Some(s"${Utils.escapeScalaKeyword(member.scalaName)}: $scalaType$default,"),
    ).flatten
  }

  private def printMessage(m: Descriptors.Descriptor): String = {
    validateFieldNames(m)
    val parameters = messageLayout(m)
    val companionMembers =
      realOneofs(m).map(printOneof(_)) ++
        m.getEnumTypes.asScala.map(printEnum(_)) ++
        m.getNestedTypes.asScala.filterNot(_.getOptions.getMapEntry).map(printMessage(_)) ++
        groups(parameters).map { group =>
          printProduct(
            m = m,
            name = group.name,
            parameters = group.members,
            comment = s"Fields of ${m.getName} in ${group.name}.",
            deprecated = false,
            companionMembers = List(printProductCodec(m, s"${m.scalaType}.${group.name}", isGroup = true)),
          )
        } ++
        List(printProductCodec(m, m.scalaType, isGroup = false))
    printProduct(
      m = m,
      name = Utils.escapeScalaKeyword(m.getName),
      parameters = parameters,
      comment = m.comment.getOrElse(m.getName),
      deprecated = m.getOptions.getDeprecated,
      companionMembers = companionMembers,
    )
  }

  private def printProduct(
    m: Descriptors.Descriptor,
    name: String,
    parameters: List[MessageMember],
    comment: String,
    deprecated: Boolean,
    companionMembers: Seq[String],
  ): String = {
    val params = parameters.flatMap(member => member.comment.map(Utils.escapeScalaKeyword(member.scalaName) -> _)).to(ListMap)
    val memberDoc = List(
      Some(Comments.formatAsScaladoc(comment, params)),
      Option.when(deprecated)(deprecatedAnnotation),
    ).flatten.mkString("\n")
    val exports = parameters.collect { case MessageMember.Group(_, parameterName, _) =>
      s"  export ${Utils.escapeScalaKeyword(parameterName)}.*"
    }
    val body = if (exports.isEmpty) "" else exports.mkString(" {\n", "\n", "\n}")
    s"""
      |$memberDoc
      |case class $name(
      |  ${parameters.flatMap(printParameter(m, _)).mkString("\n  ")}
      |)$body
      |object $name {
      |${companionMembers.mkString("\n")}
      |}
      |""".stripMargin
  }

  private def groups(parameters: List[MessageMember]): List[MessageMember.Group] = parameters.flatMap {
    case group: MessageMember.Group => group :: groups(group.members)
    case _ => Nil
  }

  /**
   * Fit every constructor to the JVM limit, including oneofs and any groups of
   * groups.
   */
  private def messageLayout(m: Descriptors.Descriptor): List[MessageMember] = {
    val usedTypes = mutable.Set.empty[String] ++
      m.getEnumTypes.asScala.map(_.getName) ++
      m.getNestedTypes.asScala.filterNot(_.getOptions.getMapEntry).map(_.getName) ++
      realOneofs(m).map(_.scalaTypeName) ++ List(m.getName)
    val usedParameters = mutable.Set.empty[String] ++ m.fields.map(_.scalaName) ++ realOneofs(m).map(_.scalaName)

    def uniqueName(base: String, used: mutable.Set[String]): String = {
      var name = base
      while (used.contains(name)) name += "Group"
      used += name
      name
    }

    var groupIndex = 0
    def fit(members: List[MessageMember]): List[MessageMember] = {
      if (members.map(_.slots).sum <= MaxParameterSlots) members
      else {
        val grouped = chunkBySlots(members).map { chunk =>
          groupIndex += 1
          val name = uniqueName(s"Part$groupIndex", usedTypes)
          val parameterName = uniqueName(name.head.toLower.toString + name.tail, usedParameters)
          MessageMember.Group(name = name, parameterName = parameterName, members = chunk)
        }
        fit(grouped)
      }
    }
    fit(messageMembers(m))
  }

  private def chunkBySlots(members: List[MessageMember]): List[List[MessageMember]] = {
    val chunks = mutable.ListBuffer.empty[List[MessageMember]]
    val current = mutable.ListBuffer.empty[MessageMember]
    var slots = 0
    members.foreach { member =>
      if (current.nonEmpty && slots + member.slots > MaxParameterSlots) {
        chunks += current.toList
        current.clear()
        slots = 0
      }
      current += member
      slots += member.slots
    }
    if (current.nonEmpty) chunks += current.toList
    chunks.toList
  }

  private def realOneofs(m: Descriptors.Descriptor) = m.getRealOneofs.asScala.toList

  /**
   * Fields and real oneofs in declaration order, each oneof positioned at its
   * first field.
   */
  private def messageMembers(m: Descriptors.Descriptor): List[MessageMember] = {
    val emitted = mutable.Set.empty[Int]
    m.fields.toList.flatMap { field =>
      Option(field.getRealContainingOneof) match {
        case None => Some(MessageMember.Field(field))
        case Some(oneof) => Option.when(emitted.add(oneof.getIndex))(MessageMember.Oneof(oneof))
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
      s"_root_.zga.common.OneofCodec.Case.derived[$oneofType.${field.oneofCaseName}](${field.getNumber})"
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

  private def printProductCodec(m: Descriptors.Descriptor, scalaType: String, isGroup: Boolean): String = {
    val codec = if (isGroup) "MessageGroupCodec" else "MessageCodec"
    val name = if (isGroup) "groupCodec" else "messageCodec"
    s"""given $name: _root_.zga.common.$codec[$scalaType] =
      |  _root_.zga.common.$codec.derived[$scalaType](${messageDescriptorRef(m)})""".stripMargin
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
