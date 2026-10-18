package zga.codegen

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import com.google.protobuf.WireFormat
import zga.codegen.Generator.MessageMember

import scala.jdk.CollectionConverters.*

private[codegen] object CodecGenerator {

  private val MessageCodec = "_root_.zga.common.MessageCodec"
  private val Oneof = "_root_.zga.common.Oneof"
  private val ProtoReader = "_root_.zga.common.ProtoReader"
  private val Sizes = "_root_.zga.common.Sizes"
  private val CodedOutput = "_root_.com.google.protobuf.CodedOutputStream"
  private val ScalaSome = "_root_.scala.Some"
  private val ScalaNone = "_root_.scala.None"

  // a split part that contains fields that are all scalars does not use `sizes`
  private val PartSizes = s"@_root_.scala.annotation.unused sizes: $Sizes"

  /**
   * HotSpot never JIT compiles a method of more than 8000 bytes of bytecode
   * (`-XX:HugeMethodLimit`), so the code of a message with many fields is split
   * across methods, each estimated to be at most 7000. The estimates measured
   * against the bytecode of the Google Ads messages come out slightly over its
   * actual size, which leaves room for messages that they fit less well.
   */
  private val MaxMethodBytes = 7000

  /**
   * A case of `readFrom`, reading a leaf from a tag.
   *
   * @param bytes
   *   the estimated size of its bytecode
   */
  private case class ReadCase(tag: Int, read: String, bytes: Int)

  /**
   * The fragments of a leaf in each of the codec's methods.
   *
   * @param variable
   *   the declaration of the variable that `readFrom` reads the leaf into,
   *   starting from the value being merged into
   * @param argument
   *   the constructor argument of the variable
   * @param default
   *   the constructor argument of the default value
   * @param writeBytes
   *   the estimated size of the bytecode of `size`, and of `write`
   */
  private case class LeafFragments(
    variable: String,
    cases: List[ReadCase],
    argument: String,
    default: String,
    size: String,
    write: String,
    writeBytes: Int,
  )

  /**
   * A codec as the members of the object that holds it, and the parent and
   * annotation of that object. The object itself is the codec, rather than an
   * anonymous class of its own, which saves a class per message.
   */
  case class Codec(parent: Option[String], members: String, annotation: String)

  def printMessageCodec(m: Descriptors.Descriptor, parameters: List[MessageMember]): Codec = {
    val scalaType = m.scalaType
    val leaves = leavesOf(parameters, "value")
    val fragments = leaves.map((leaf, path) => leaf -> leafFragments(leaf, path)).toMap
    val default = construct(m, m.scalaPath, parameters, fragments(_).default)
    // the codec reads and writes the deprecated fields as well
    val deprecated = leaves.exists {
      case (MessageMember.Field(field), _) => field.getOptions.getDeprecated
      case (MessageMember.Oneof(_), _) => false
    }
    val annotation = if (deprecated) "@_root_.scala.annotation.nowarn(\"cat=deprecation\")\n" else ""
    val declaration = s"given messageCodec: $MessageCodec[$scalaType] ="
    if (leaves.isEmpty) {
      Codec(parent = None, members = s"$declaration $MessageCodec.empty($default)", annotation = "")
    } else {
      val written = leaves.sortBy((leaf, _) => number(leaf)).map((leaf, _) => fragments(leaf))
      val constructed = construct(m, m.scalaPath, parameters, fragments(_).argument)
      val methods = List(s"override private[zga] val defaultValue: $scalaType = $default") ++
        printSize(scalaType, written) ++
        printWrite(scalaType, written) ++
        printRead(scalaType, leaves.map((leaf, _) => fragments(leaf)), constructed)
      Codec(
        parent = Some(s"$MessageCodec[$scalaType]"),
        members = (methods :+ s"inline $declaration this").mkString("\n\n"),
        annotation = annotation,
      )
    }
  }

  /**
   * Whether the codec sizes the message in more than one method, which puts it
   * in a file of its own rather than in the message's companion.
   */
  def isSplit(parameters: List[MessageMember]): Boolean = {
    val written = leavesOf(parameters, "value").sortBy((leaf, _) => number(leaf)).map(leafFragments(_, _))
    Utils.chunkBy(written, MaxMethodBytes)(_.writeBytes).length > 1
  }

  def printOneofCase(field: Descriptors.FieldDescriptor): String = {
    s"""override private[zga] def serializedSize(sizes: $Sizes): _root_.scala.Int = ${tagSize(field)} + ${valueSize(field, "value")}
      |
      |override private[zga] def writeTo(output: $CodedOutput, sizes: $Sizes): _root_.scala.Unit = {
      |  ${writeTag(field)}
      |  ${writeValue(field, "value")}
      |}""".stripMargin
  }

  /**
   * Fields are written in field number order, a oneof at its first member's
   * number.
   */
  private def number(leaf: MessageMember.Leaf) = leaf match {
    case MessageMember.Field(field) => field.getNumber
    case MessageMember.Oneof(oneof) => oneof.getFields.asScala.map(_.getNumber).min
  }

  private def printSize(scalaType: String, written: List[LeafFragments]): List[String] = {
    def method(signature: String, leaves: List[LeafFragments]) = {
      s"""$signature: _root_.scala.Int = {
        |  var size = 0
        |${leaves.map(_.size).mkString("\n")}
        |  size
        |}""".stripMargin
    }
    val signature = s"override private[zga] def serializedSize(value: $scalaType, sizes: $Sizes)"
    Utils.chunkBy(written, MaxMethodBytes)(_.writeBytes) match {
      case List(leaves) => List(method(signature, leaves))
      case parts =>
        val calls = parts.indices.map(i => s"serializedSize$i(value, sizes)").mkString(" +\n")
        s"$signature: _root_.scala.Int =\n$calls" :: parts.zipWithIndex.map { (leaves, i) =>
          method(s"private def serializedSize$i(value: $scalaType, $PartSizes)", leaves)
        }
    }
  }

  private def printWrite(scalaType: String, written: List[LeafFragments]): List[String] = {
    def method(signature: String, statements: List[String]) = {
      s"""$signature: _root_.scala.Unit = {
        |${statements.mkString("\n")}
        |}""".stripMargin
    }
    val signature = s"override private[zga] def writeTo(output: $CodedOutput, value: $scalaType, sizes: $Sizes)"
    Utils.chunkBy(written, MaxMethodBytes)(_.writeBytes) match {
      case List(leaves) => List(method(signature, leaves.map(_.write)))
      case parts =>
        val calls = parts.indices.map(i => s"writeTo$i(output, value, sizes)").toList
        method(signature, calls) :: parts.zipWithIndex.map { (leaves, i) =>
          method(s"private def writeTo$i(output: $CodedOutput, value: $scalaType, $PartSizes)", leaves.map(_.write))
        }
    }
  }

  /**
   * Reads the fields into local variables, or, when there are too many to read
   * in one method, into the variables of a class whose methods each read a part
   * of the tags.
   */
  private def printRead(scalaType: String, leaves: List[LeafFragments], constructed: String): List[String] = {
    val variables = leaves.map(_.variable).mkString("\n")
    val signature = s"override private[zga] def readFrom(reader: $ProtoReader, value: $scalaType): $scalaType"
    val cases = leaves.flatMap(_.cases)
    if (cases.map(_.bytes).sum + leaves.length * VariableBytes <= MaxMethodBytes) {
      List(s"""$signature = {
        |  val input = reader.input
        |$variables
        |  var done = false
        |  while (!done) {
        |    input.readTag() match {
        |      case 0 => done = true
        |${cases.map(c => s"case ${c.tag} => ${c.read}").mkString("\n")}
        |      case tag => done = !input.skipField(tag)
        |    }
        |  }
        |  $constructed
        |}""".stripMargin)
    } else {
      val parts = Utils.chunkBy(cases, MaxMethodBytes)(_.bytes)
      val reads = parts.indices.map(i => s"fields.read$i(tag)").mkString(" || ")
      val readMethods = parts.zipWithIndex.map { (cases, i) =>
        s"""def read$i(tag: _root_.scala.Int): _root_.scala.Boolean = tag match {
          |${cases.map(c => s"case ${c.tag} =>\n  ${c.read}\n  true").mkString("\n")}
          |  case _ => false
          |}""".stripMargin
      }
      List(
        s"""$signature = {
          |  val fields = new Fields(reader, value)
          |  var done = false
          |  while (!done) {
          |    val tag = reader.input.readTag()
          |    done = tag == 0 || !($reads || reader.input.skipField(tag))
          |  }
          |  fields.result
          |}""".stripMargin,
        s"""private final class Fields(reader: $ProtoReader, value: $scalaType) {
          |  val input = reader.input
          |$variables
          |
          |${readMethods.mkString("\n\n")}
          |
          |  def result: $scalaType = $constructed
          |}""".stripMargin,
      )
    }
  }

  private def leavesOf(members: List[MessageMember], path: String): List[(MessageMember.Leaf, String)] = members.flatMap {
    case MessageMember.Group(_, parameterName, groupMembers) => leavesOf(groupMembers, s"$path.${Utils.escapeScalaKeyword(parameterName)}")
    case leaf: MessageMember.Leaf => List(leaf -> s"$path.${Utils.escapeScalaKeyword(leaf.scalaName)}")
  }

  // prefixed so that it cannot collide with the locals of `readFrom`
  private def variableName(name: String) = s"_$name"

  private def construct(
    m: Descriptors.Descriptor,
    scalaPath: String,
    members: List[MessageMember],
    argument: MessageMember.Leaf => String,
  ): String = {
    val values = members.map {
      case MessageMember.Group(name, parameterName, groupMembers) =>
        s"${Utils.escapeScalaKeyword(parameterName)} = ${construct(m, s"${m.scalaPath}.$name", groupMembers, argument)}"
      case leaf: MessageMember.Leaf => s"${Utils.escapeScalaKeyword(leaf.scalaName)} = ${argument(leaf)}"
    }
    if (values.isEmpty) scalaPath else values.mkString(s"$scalaPath(\n  ", ",\n  ", ",\n)").replace("\n", "\n    ")
  }

  private def leafFragments(leaf: MessageMember.Leaf, path: String): LeafFragments = leaf match {
    case MessageMember.Field(field) =>
      if (field.isMapField) mapFragments(field, path)
      else if (field.isRepeated) repeatedFragments(field, path)
      else if (field.hasPresence) optionalFragments(field, path)
      else singularFragments(field, path)
    case MessageMember.Oneof(oneof) => oneofFragments(oneof, path)
  }

  // rough sizes of the bytecode of a leaf's fragments, measured on the generated code of the Google Ads messages:
  // a case of `readFrom`, the declaration and constructor argument of its variable, and its `size` or `write`
  private val ReadBytes = 50
  private val VariableBytes = 15
  private val WriteBytes = 80

  /**
   * A field without presence, written unless it holds its default value.
   */
  private def singularFragments(f: Descriptors.FieldDescriptor, path: String) = {
    val variable = variableName(f.scalaName)
    LeafFragments(
      variable = s"var $variable: ${f.scalaTypeName} = $path",
      cases = List(ReadCase(tag(f), s"$variable = ${readValue(f)}", ReadBytes)),
      argument = variable,
      default = f.scalaDefaultValue,
      size = s"""if (${nonDefault(f, path)}) {
        |  size += ${tagSize(f)} + ${valueSize(f, path)}
        |}""".stripMargin,
      write = s"""if (${nonDefault(f, path)}) {
        |  ${writeTag(f)}
        |  ${writeValue(f, path)}
        |}""".stripMargin,
      writeBytes = WriteBytes,
    )
  }

  /**
   * A field with presence, written whenever it is set.
   */
  private def optionalFragments(f: Descriptors.FieldDescriptor, path: String) = {
    val variable = variableName(f.scalaName)
    val read = f.getType match {
      case Type.MESSAGE => s"$ScalaSome(${messageCodec(f)}.readDelimited(reader, $variable))"
      // an unrecognized number of a closed enum keeps the previous value
      case Type.ENUM if isClosedEnum(f) => s"${readValue(f)} match { case $ScalaNone => $variable; case v => v }"
      case _ => s"$ScalaSome(${readValue(f)})"
    }
    LeafFragments(
      variable = s"var $variable: ${f.scalaTypeName} = $path",
      cases = List(ReadCase(tag(f), s"$variable = $read", ReadBytes)),
      argument = variable,
      default = ScalaNone,
      size = s"""$path match {
        |  case $ScalaSome(v) => size += ${tagSize(f)} + ${valueSize(f, "v")}
        |  case $ScalaNone => ()
        |}""".stripMargin,
      write = s"""$path match {
        |  case $ScalaSome(v) =>
        |    ${writeTag(f)}
        |    ${writeValue(f, "v")}
        |  case $ScalaNone => ()
        |}""".stripMargin,
      writeBytes = WriteBytes,
    )
  }

  /**
   * A repeated field, which reads both packed and unpacked elements of a
   * packable type, as a parser has to accept either. Elements of a primitive
   * type are read into a builder of that type, which does not box them.
   */
  private def repeatedFragments(f: Descriptors.FieldDescriptor, path: String) = {
    val variable = variableName(f.scalaName)
    val (builderType, newBuilder) = primitiveBuilder(f) match {
      case Some(builder) => (builder, s"new $builder")
      case None => (s"_root_.zio.ChunkBuilder[${f.scalaType}]", s"_root_.zio.ChunkBuilder.make[${f.scalaType}]()")
    }
    val create = createBuilder(variable, newBuilder)
    // an unrecognized number of a closed enum is dropped
    val add = if (isClosedEnum(f)) s"$variable ++= ${readValue(f)}" else s"$variable.addOne(${readValue(f)})"
    val packedCase = Option.when(isPackable(f)) {
      ReadCase(tag(f, WireFormat.WIRETYPE_LENGTH_DELIMITED), s"$create; reader.packed($add)", 2 * ReadBytes)
    }
    val dataSize = fixedSize(f) match {
      case Some(size) => s"$path.length * $size"
      case None => s"$MessageCodec.dataSize($path)(v => ${valueSize(f, "v")})"
    }
    val (size, write) = if (f.isPacked) {
      (
        s"""if ($path.nonEmpty) {
          |  size += ${tagSize(f)} + sizes.delimited($dataSize)
          |}""".stripMargin,
        s"""if ($path.nonEmpty) {
          |  output.writeTag(${f.getNumber}, ${WireFormat.WIRETYPE_LENGTH_DELIMITED})
          |  sizes.writeLength(output)
          |  $path.foreach(v => ${writeValue(f, "v")})
          |}""".stripMargin,
      )
    } else {
      (
        s"size += $path.length * ${tagSize(f)} + $dataSize",
        s"""$path.foreach { v =>
          |  ${writeTag(f)}
          |  ${writeValue(f, "v")}
          |}""".stripMargin,
      )
    }
    LeafFragments(
      variable = s"var $variable: $builderType = null",
      cases = ReadCase(tag(f), s"$create; $add", ReadBytes) :: packedCase.toList,
      argument = appended(variable, path),
      default = f.scalaDefaultValue,
      size = size,
      write = write,
      writeBytes = 2 * WriteBytes,
    )
  }

  /**
   * A map field, a repeated field of entry messages with the key as field 1 and
   * the value as field 2. Both are always written.
   */
  private def mapFragments(f: Descriptors.FieldDescriptor, path: String) = {
    val variable = variableName(f.scalaName)
    val entry = f.getMessageType
    val key = entry.findFieldByNumber(1)
    val value = entry.findFieldByNumber(2)
    val (defaultValue, readEntryValue, put) = value.getType match {
      // a message value read again merges into the previous value, as protobuf-java does
      case Type.MESSAGE => (value.typeDefaultValue, s"v => ${messageCodec(value)}.readDelimited(reader, v)", s"(k, v) => $variable += k -> v")
      // an entry whose value is an unrecognized number of a closed enum is dropped
      case Type.ENUM if isClosedEnum(value) =>
        (
          s"($ScalaSome(${value.typeDefaultValue}): _root_.scala.Option[${value.scalaType}])",
          s"_ => ${readValue(value)}",
          s"(k, v) => v match { case $ScalaSome(e) => $variable += k -> e; case $ScalaNone => () }",
        )
      case _ => (value.typeDefaultValue, s"_ => ${readValue(value)}", s"(k, v) => $variable += k -> v")
    }
    val entrySize = s"${tagSize(key)} + ${valueSize(key, "k")} + ${tagSize(value)} + ${valueSize(value, "v")}"
    val mapType = s"_root_.scala.collection.immutable.Map[${key.scalaType}, ${value.scalaType}]"
    val create = createBuilder(variable, s"_root_.scala.collection.immutable.Map.newBuilder[${key.scalaType}, ${value.scalaType}]")
    LeafFragments(
      variable = s"var $variable: _root_.scala.collection.mutable.Builder[(${key.scalaType}, ${value.scalaType}), $mapType] = null",
      cases = List(
        ReadCase(
          tag(f),
          s"$create; reader.mapEntry(${tag(key)}, ${tag(value)}, ${key.typeDefaultValue}, $defaultValue)(${readValue(key)}, $readEntryValue)($put)",
          5 * ReadBytes,
        ),
      ),
      argument = appended(variable, path),
      default = f.scalaDefaultValue,
      size = s"size += $path.size * ${tagSize(f)} + $MessageCodec.mapSize($path, sizes)((k, v) => $entrySize)",
      write = s"""$path.foreachEntry { (k, v) =>
        |  ${writeTag(f)}
        |  sizes.writeLength(output)
        |  ${writeTag(key)}
        |  ${writeValue(key, "k")}
        |  ${writeTag(value)}
        |  ${writeValue(value, "v")}
        |}""".stripMargin,
      writeBytes = 4 * WriteBytes,
    )
  }

  private def createBuilder(builder: String, newBuilder: String) = s"if ($builder == null) $builder = $newBuilder"

  private def appended(builder: String, path: String) =
    s"if ($builder == null) $path else if ($path.isEmpty) $builder.result() else $path ++ $builder.result()"

  /**
   * A oneof, its set member written whenever it is set, by the member's case
   * itself. A member read again replaces another member, and a message member
   * merges into its previous value.
   */
  private def oneofFragments(o: Descriptors.OneofDescriptor, path: String) = {
    val variable = variableName(o.scalaName)
    val cases = o.getFields.asScala.toList.map { field =>
      val caseType = s"${o.scalaType}.${field.oneofCaseName}"
      val (read, bytes) = field.getType match {
        case Type.MESSAGE =>
          val previous = s"$variable match { case $ScalaSome($caseType(v)) => $ScalaSome(v); case _ => $ScalaNone }"
          (s"$ScalaSome($caseType(${messageCodec(field)}.readDelimited(reader, $previous)))", 3 * ReadBytes)
        case Type.ENUM if isClosedEnum(field) =>
          (s"${readValue(field)} match { case $ScalaSome(v) => $ScalaSome($caseType(v)); case $ScalaNone => $variable }", 2 * ReadBytes)
        case _ => (s"$ScalaSome($caseType(${readValue(field)}))", ReadBytes)
      }
      ReadCase(tag(field), s"$variable = $read", bytes)
    }
    LeafFragments(
      variable = s"var $variable: _root_.scala.Option[${o.scalaType}] = $path",
      cases = cases,
      argument = variable,
      default = ScalaNone,
      size = s"size += $Oneof.serializedSize($path, sizes)",
      write = s"$Oneof.writeTo(output, $path, sizes)",
      writeBytes = WriteBytes / 2,
    )
  }

  private def isClosedEnum(f: Descriptors.FieldDescriptor) = f.getType == Type.ENUM && f.getEnumType.isClosed

  private def isPackable(f: Descriptors.FieldDescriptor) = f.getType match {
    case Type.STRING | Type.BYTES | Type.MESSAGE | Type.GROUP => false
    case _ => true
  }

  private def primitiveBuilder(f: Descriptors.FieldDescriptor) = f.getJavaType match {
    case Descriptors.FieldDescriptor.JavaType.INT => Some("_root_.zio.ChunkBuilder.Int")
    case Descriptors.FieldDescriptor.JavaType.LONG => Some("_root_.zio.ChunkBuilder.Long")
    case Descriptors.FieldDescriptor.JavaType.FLOAT => Some("_root_.zio.ChunkBuilder.Float")
    case Descriptors.FieldDescriptor.JavaType.DOUBLE => Some("_root_.zio.ChunkBuilder.Double")
    case Descriptors.FieldDescriptor.JavaType.BOOLEAN => Some("_root_.zio.ChunkBuilder.Boolean")
    case _ => None
  }

  private def fixedSize(f: Descriptors.FieldDescriptor) = f.getType match {
    case Type.DOUBLE | Type.FIXED64 | Type.SFIXED64 => Some(8)
    case Type.FLOAT | Type.FIXED32 | Type.SFIXED32 => Some(4)
    case Type.BOOL => Some(1)
    case _ => None
  }

  private def wireType(f: Descriptors.FieldDescriptor) = f.getLiteType.getWireType

  private def tag(f: Descriptors.FieldDescriptor, wireType: Int): Int = (f.getNumber << 3) | wireType

  private def tag(f: Descriptors.FieldDescriptor): Int = tag(f, wireType(f))

  private def tagSize(f: Descriptors.FieldDescriptor): Int = CodedOutputStream.computeTagSize(f.getNumber)

  private def writeTag(f: Descriptors.FieldDescriptor) = s"output.writeTag(${f.getNumber}, ${wireType(f)})"

  private def messageCodec(f: Descriptors.FieldDescriptor) = s"${f.getMessageType.scalaPath}.messageCodec"

  private def scalarName(f: Descriptors.FieldDescriptor) = f.getType match {
    case Type.DOUBLE => "Double"
    case Type.FLOAT => "Float"
    case Type.INT64 => "Int64"
    case Type.UINT64 => "UInt64"
    case Type.INT32 => "Int32"
    case Type.FIXED64 => "Fixed64"
    case Type.FIXED32 => "Fixed32"
    case Type.BOOL => "Bool"
    case Type.STRING => "String"
    case Type.BYTES => "Bytes"
    case Type.UINT32 => "UInt32"
    case Type.SFIXED32 => "SFixed32"
    case Type.SFIXED64 => "SFixed64"
    case Type.SINT32 => "SInt32"
    case Type.SINT64 => "SInt64"
    case Type.ENUM => "Enum"
    case Type.MESSAGE | Type.GROUP => throw IllegalArgumentException(s"${f.getFullName} is not a scalar field")
  }

  /**
   * Reads a value of the field, which for a closed enum is an `Option`, `None`
   * for an unrecognized number.
   */
  private def readValue(f: Descriptors.FieldDescriptor) = f.getType match {
    case Type.MESSAGE => s"${messageCodec(f)}.readDelimited(reader)"
    case Type.ENUM => s"${f.getEnumType.scalaType}.fromValue(input.readEnum())"
    case Type.STRING if f.needsUtf8Check => "input.readStringRequireUtf8()"
    case Type.GROUP => throw IllegalArgumentException(s"${f.getFullName} is a group field, which is not supported")
    case _ => s"input.read${scalarName(f)}()"
  }

  private def writeValue(f: Descriptors.FieldDescriptor, value: String) = f.getType match {
    case Type.MESSAGE => s"${messageCodec(f)}.writeDelimited(output, $value, sizes)"
    case Type.ENUM => s"output.writeEnumNoTag($value.value)"
    case _ => s"output.write${scalarName(f)}NoTag($value)"
  }

  private def valueSize(f: Descriptors.FieldDescriptor, value: String) = f.getType match {
    case Type.MESSAGE => s"${messageCodec(f)}.delimitedSize($value, sizes)"
    case Type.ENUM => s"$CodedOutput.computeEnumSizeNoTag($value.value)"
    case _ => s"$CodedOutput.compute${scalarName(f)}SizeNoTag($value)"
  }

  /**
   * Whether a field without presence holds a value other than its default.
   * Floating point values compare their bits, so that negative zero is written.
   */
  private def nonDefault(f: Descriptors.FieldDescriptor, value: String) = f.getType match {
    case Type.INT32 | Type.UINT32 | Type.SINT32 | Type.FIXED32 | Type.SFIXED32 => s"$value != 0"
    case Type.INT64 | Type.UINT64 | Type.SINT64 | Type.FIXED64 | Type.SFIXED64 => s"$value != 0L"
    case Type.FLOAT => s"_root_.java.lang.Float.floatToRawIntBits($value) != 0"
    case Type.DOUBLE => s"_root_.java.lang.Double.doubleToRawLongBits($value) != 0L"
    case Type.BOOL => value
    case Type.STRING | Type.BYTES => s"!$value.isEmpty"
    case Type.ENUM => s"$value.value != 0"
    case Type.MESSAGE | Type.GROUP => throw IllegalArgumentException(s"${f.getFullName} always has presence")
  }
}
