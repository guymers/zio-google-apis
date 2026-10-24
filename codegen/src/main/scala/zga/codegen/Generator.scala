package zga.codegen

import com.google.protobuf.Descriptors

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object Generator {

  private val deprecatedAnnotation = """@scala.deprecated(message="in proto file", "")"""

  private val ReservedEnumMemberNames = Set(
    "fromValue",
    "name",
    "valueMap",
    "numbers",
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
    "canEqual",
    "clone",
    "copy",
    "finalize",
    "hashCode",
    "notify",
    "notifyAll",
    "productArity",
    "productElement",
    "productElementName",
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
    s"""${fileHeader(f)}
      |${f.getEnumTypes.asScala.map(printEnum(_)).mkString("\n")}
      |${f.getMessageTypes.asScala.map(printMessage(_)).mkString("\n")}
      |""".stripMargin
  }

  /**
   * The file name and content of when a codec is defined in a separate file to
   * keep the file of its message smaller.
   */
  def codecFiles(f: Descriptors.FileDescriptor): List[(String, String)] = {
    def messages(m: Descriptors.Descriptor): List[Descriptors.Descriptor] =
      m :: m.getNestedTypes.asScala.toList.filterNot(_.getOptions.getMapEntry).flatMap(messages)

    // the object is only reached through its message's companion
    val visibility = f.scalaPackageParts.lastOption.fold("")(part => s"private[${Utils.escapeScalaKeyword(part)}] ")
    f.getMessageTypes.asScala.toList.flatMap(messages).flatMap { m =>
      val parameters = messageLayout(m)
      Option.when(CodecGenerator.isSplit(parameters)) {
        val name = codecObjectName(m)
        val codec = CodecGenerator.printMessageCodec(m, parameters)
        val content =
          s"""${fileHeader(f)}
            |${codec.annotation}${visibility}object $name${extendsClause(codec.parent)} {
            |${codec.members}
            |}
            |""".stripMargin
        (f.scalaPackageParts :+ s"$name.scala").mkString("/") -> content
      }
    }
  }

  private def fileHeader(f: Descriptors.FileDescriptor) = {
    // a proto with neither `package` nor `option java_package` keeps its types in
    // the default package, which has no `package` declaration
    val packageDeclaration = if (f.scalaPackage.isEmpty) "" else s"package ${f.scalaPackage}\n"
    s"""${Comments.header(f).map(Comments.formatAsMultiLineComment(_)).getOrElse("")}
      |$packageDeclaration""".stripMargin
  }

  /**
   * The object of a codec in a file of its own, named after the message and the
   * messages it is nested in: `Outer.Inner` has `OuterInnerMessageCodec`.
   */
  private def codecObjectName(m: Descriptors.Descriptor): String = {
    def names(d: Descriptors.Descriptor): List[String] = d.parent.fold(Nil)(names) :+ d.getName

    names(m).mkString + "MessageCodec"
  }

  private def printEnum(e: Descriptors.EnumDescriptor) = {
    JavaEnumType.of(e) match {
      case Some(t) => JavaEnumType.printCodec(e, t)
      case None => if (e.isClosed) printClosedEnum(e) else printOpenEnum(e)
    }
  }

  private def printEnumValues(e: Descriptors.EnumDescriptor, reserved: Set[String]) = {
    def printEnumValue(v: Descriptors.EnumValueDescriptor) = {
      if (reserved.contains(v.getName)) {
        throw IllegalArgumentException(
          s"${e.getFullName} has a value named '${v.getName}', which collides with a member of the generated enum",
        )
      }
      List(
        v.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)),
        Option(v.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
        Some(s"case ${Utils.escapeScalaKeyword(v.getName)}"),
      ).flatten
    }

    e.getValues.asScala.flatMap(printEnumValue(_))
  }

  /**
   * Each value is a plain case, as a case with constructor arguments compiles
   * to a class of its own, and looks up its number by ordinal: the ordinal
   * itself when the numbers count up from zero, otherwise the `numbers` array.
   */
  private def printEnumNumbers(e: Descriptors.EnumDescriptor, numbersRef: String) = {
    val numbers = e.getValues.asScala.map(_.getNumber)
    if (numbers.zipWithIndex.forall(_ == _)) ("def value: _root_.scala.Int = ordinal", "")
    else (
      s"def value: _root_.scala.Int = $numbersRef(ordinal)",
      s"\n  private val numbers = _root_.scala.Array[_root_.scala.Int](${numbers.mkString(", ")})",
    )
  }

  /**
   * The value's name as written in the proto file, e.g. `ENABLED`. Each case
   * has that exact name, so it is the case's `toString`. An unknown number has
   * no name, so an open enum's `Unrecognized` does not have this.
   */
  private val nameMethod = "def name: _root_.scala.Predef.String = toString"

  private def enumComment(e: Descriptors.EnumDescriptor) = {
    List(
      e.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)),
      Option(e.getOptions.getDeprecated).filter(identity(_)).map(_ => deprecatedAnnotation),
    ).flatten.mkString("\n")
  }

  /**
   * An open enum (proto3) keeps a number it does not know. The known values are
   * a Scala enum, exported so they can be referred to as `E.A`, and an unknown
   * number is an `Unrecognized` case class.
   */
  private def printOpenEnum(e: Descriptors.EnumDescriptor) = {
    if (e.getName == "Unrecognized") {
      throw IllegalArgumentException(s"${e.getFullName} is named 'Unrecognized', which collides with its generated unrecognized value class")
    }
    val enumName = Utils.escapeScalaKeyword(e.getName)
    val enumType = e.scalaType
    val values = printEnumValues(e, ReservedEnumMemberNames ++ Set("Recognized", "Unrecognized"))
    val (valueMethod, numbers) = printEnumNumbers(e, "numbers")

    s"""
      |${enumComment(e)}
      |sealed trait $enumName derives _root_.scala.CanEqual {
      |  def value: _root_.scala.Int
      |}
      |object $enumName {
      |  enum Recognized extends $enumType {
      |    ${values.mkString("\n    ")}
      |
      |    $valueMethod
      |    $nameMethod
      |  }
      |  export Recognized.*
      |
      |  case class Unrecognized private[$enumName] (value: _root_.scala.Int) extends $enumType
      |$numbers
      |  // the first of values sharing a number wins, as in protobuf-java
      |  private val valueMap = Recognized.values.reverseIterator.map(v => v.value -> v).toMap
      |  def fromValue(v: _root_.scala.Int): $enumType = valueMap.getOrElse(v, Unrecognized(v))
      |}
      |""".stripMargin
  }

  /**
   * A closed enum (proto2). A number it does not know is dropped when read, as
   * protobuf-java moves it to the message's unknown fields, so there is no
   * unknown value to keep. The first value is the default.
   */
  private def printClosedEnum(e: Descriptors.EnumDescriptor) = {
    val enumName = Utils.escapeScalaKeyword(e.getName)
    val values = printEnumValues(e, ReservedEnumMemberNames)
    val (valueMethod, numbers) = printEnumNumbers(e, s"$enumName.numbers")

    s"""
      |${enumComment(e)}
      |enum $enumName derives _root_.scala.CanEqual {
      |  ${values.mkString("\n  ")}
      |
      |  $valueMethod
      |  $nameMethod
      |}
      |object $enumName {$numbers
      |  // the first of values sharing a number wins, as in protobuf-java
      |  private val valueMap = $enumName.values.reverseIterator.map(v => v.value -> v).toMap
      |  def fromValue(v: _root_.scala.Int): _root_.scala.Option[$enumName] = valueMap.get(v)
      |}
      |""".stripMargin
  }

  /**
   * The maximum number of parameter slots in a generated case class.
   *
   * The JVM allows 254, a `Long` or a `Double` taking two, but HotSpot's C2
   * compiler gives up on a method with too many arguments ("unsupported calling
   * sequence") well before that, and then the generated constructor is neither
   * compiled nor inlined into its callers. Using 64 slots ensures that the
   * generated classes stay clear of that limit. A message with more members is
   * generated as consecutive `PartN` case classes: the message holds them and
   * exports their fields so they seem as if they are all in one case class.
   */
  private val MaxParameterSlots = 64

  private def isDoubleSlot(f: Descriptors.FieldDescriptor) = {
    !f.isCollection &&
    !f.hasPresence &&
    (f.getJavaType match {
      case Descriptors.FieldDescriptor.JavaType.LONG | Descriptors.FieldDescriptor.JavaType.DOUBLE => true
      case _ => false
    })
  }

  private def parameterSlots(f: Descriptors.FieldDescriptor) = if (isDoubleSlot(f)) 2 else 1

  /**
   * A parameter of a message's case class: a leaf holding a value of the
   * message, or a group of parameters split out of a message too large for one
   * case class.
   */
  private[codegen] sealed trait MessageMember {
    import MessageMember.*

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
      case Field(field) => !(field.isRequiredByFieldBehavior && !field.isCollection)
      case Oneof(oneof) => !oneof.isMarkedRequired
      case Group(_, _, members) => members.forall(_.hasDefault)
    }

    def comment: Option[String] = this match {
      case Field(field) => field.comment
      case Oneof(oneof) => oneof.comment
      case Group(_, _, _) => None
    }
  }
  private[codegen] object MessageMember {
    sealed trait Leaf extends MessageMember
    case class Field(field: Descriptors.FieldDescriptor) extends Leaf
    case class Oneof(oneof: Descriptors.OneofDescriptor) extends Leaf
    case class Group(name: String, parameterName: String, members: List[MessageMember]) extends MessageMember
  }

  private def printParameter(m: Descriptors.Descriptor, member: MessageMember): List[String] = {
    val (scalaType, defaultValue, deprecated) = member match {
      case MessageMember.Field(field) => (field.scalaTypeName, field.scalaDefaultValue, field.getOptions.getDeprecated)
      case MessageMember.Oneof(oneof) => (s"_root_.scala.Option[${oneof.scalaType}]", "_root_.scala.None", false)
      case MessageMember.Group(name, _, _) => (s"${m.scalaPath}.$name", s"${m.scalaPath}.$name()", false)
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
    val codec =
      if (CodecGenerator.isSplit(parameters)) {
        val codec = s"${Utils.rootQualified(m.getFile.scalaPackage)}.${codecObjectName(m)}.messageCodec"
        CodecGenerator.Codec(
          parent = None,
          members = s"given messageCodec: _root_.zga.common.MessageCodec[${m.scalaType}] = $codec",
          annotation = "",
        )
      } else CodecGenerator.printMessageCodec(m, parameters)
    val companionMembers =
      realOneofs(m).map(printOneof(_)) ++
        m.getEnumTypes.asScala.map(printEnum(_)) ++
        m.getNestedTypes.asScala.filterNot(_.getOptions.getMapEntry).map(printMessage(_)) ++
        groups(parameters).map { group =>
          printProduct(
            m = m,
            name = group.name,
            parameters = group.members,
            comment = None,
            deprecated = false,
            companionParent = None,
            companionAnnotation = "",
            companionMembers = Nil,
          )
        } ++
        List(codec.members)
    printProduct(
      m = m,
      name = Utils.escapeScalaKeyword(m.getName),
      parameters = parameters,
      comment = Some(m.comment.getOrElse(m.getName)),
      deprecated = m.getOptions.getDeprecated,
      companionParent = codec.parent,
      companionAnnotation = codec.annotation,
      companionMembers = companionMembers,
    )
  }

  private def extendsClause(parent: Option[String]) = parent.fold("")(p => s" extends $p")

  private def printProduct(
    m: Descriptors.Descriptor,
    name: String,
    parameters: List[MessageMember],
    comment: Option[String],
    deprecated: Boolean,
    companionParent: Option[String],
    companionAnnotation: String,
    companionMembers: Seq[String],
  ): String = {
    val params = parameters.flatMap(member => member.comment.map(Utils.escapeScalaKeyword(member.scalaName) -> _)).to(ListMap)
    val memberDoc = List(
      Option.when(comment.nonEmpty || params.nonEmpty)(Comments.formatAsScaladoc(comment.getOrElse(""), params)),
      Option.when(deprecated)(deprecatedAnnotation),
    ).flatten.mkString("\n")
    val exports = parameters.collect { case MessageMember.Group(_, parameterName, _) =>
      s"  export ${Utils.escapeScalaKeyword(parameterName)}.*"
    }
    val body = if (exports.isEmpty) "" else exports.mkString(" {\n", "\n", "\n}")
    val product =
      if (parameters.isEmpty) {
        ""
      } else {
        s"""case class $name(
          |  ${parameters.flatMap(printParameter(m, _)).mkString("\n  ")}
          |)$body""".stripMargin
      }
    val objectDeclaration = s"${companionAnnotation}object $name${extendsClause(companionParent)} {"
    s"""
      |$memberDoc
      |${if (product.isEmpty) objectDeclaration else s"$product\n$objectDeclaration"}
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
        val grouped = Utils.chunkBy(members, MaxParameterSlots)(_.slots).map { chunk =>
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
      s"""case class ${field.oneofCaseName}(value: ${field.scalaType}) extends $oneofType {
        |${CodecGenerator.printOneofCase(field)}
        |}""".stripMargin
    }

    s"""
      |${o.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)).getOrElse("")}
      |sealed trait ${o.scalaTypeName} extends _root_.zga.common.Oneof
      |object ${o.scalaTypeName} {
      |${cases.mkString("\n\n")}
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
}
