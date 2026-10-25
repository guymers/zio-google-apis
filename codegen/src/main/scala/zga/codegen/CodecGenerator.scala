package zga.codegen

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import com.google.protobuf.WireFormat
import zga.codegen.Generator.MessageMember

import scala.collection.mutable
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
   *   the name of the variable that `readFrom` reads the leaf into
   * @param variableType
   *   the type of the variable
   * @param initial
   *   the initial value of the variable, of the value being merged into or null
   * @param argument
   *   the constructor argument of the variable
   * @param default
   *   the constructor argument of the default value
   * @param writeBytes
   *   the estimated size of the bytecode of `size`, and of `write`
   */
  private case class LeafFragments(
    variable: String,
    variableType: String,
    initial: String,
    cases: List[ReadCase],
    argument: String,
    default: String,
    size: String,
    write: String,
    writeBytes: Int,
  ) {
    def declaration: String = s"var $variable: $variableType = $initial"

    // a builder starts as null, rather than from the value being merged into
    def copiesValue: Boolean = initial != "null"
  }

  /**
   * The object itself is the codec, rather than an anonymous class of its own,
   * which saves a class per message. Its parts are printed into the message's
   * companion, or into an object of its own when the codec is split across
   * methods.
   *
   * @param parent
   *   the type that the object extends, or `None` when the message has no
   *   fields and its codec is `MessageCodec.empty`
   * @param members
   *   the body of the object, which provides the codec as a `given`
   * @param annotation
   *   the annotations of the object, which suppress deprecation warnings when
   *   the message has deprecated fields
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
      val written = leaves.sortBy((leaf, _) => number(leaf))
      val methods = List(s"override private[zga] val defaultValue: $scalaType = $default") ++
        printSize(scalaType, written, fragments(_).writeBytes) ++
        printWrite(scalaType, written, fragments(_).writeBytes) ++
        printRead(m, parameters, fragments)
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

  private def printSize(scalaType: String, written: List[(MessageMember.Leaf, String)], bytes: MessageMember.Leaf => Int): List[String] = {
    def method(signature: String, leaves: List[(MessageMember.Leaf, String)]) = {
      val (locals, fragments) = withGroupLocals(leaves)
      s"""$signature: _root_.scala.Int = {
        |${(locals :+ "var size = 0").mkString("\n")}
        |${fragments.map(_.size).mkString("\n")}
        |  size
        |}""".stripMargin
    }
    val signature = s"override private[zga] def serializedSize(value: $scalaType, sizes: $Sizes)"
    Utils.chunkBy(written, MaxMethodBytes)((leaf, _) => bytes(leaf)) match {
      case List(leaves) => List(method(signature, leaves))
      case parts =>
        val calls = parts.indices.map(i => s"serializedSize$i(value, sizes)").mkString(" +\n")
        s"$signature: _root_.scala.Int =\n$calls" :: parts.zipWithIndex.map { (leaves, i) =>
          method(s"private def serializedSize$i(value: $scalaType, $PartSizes)", leaves)
        }
    }
  }

  private def printWrite(scalaType: String, written: List[(MessageMember.Leaf, String)], bytes: MessageMember.Leaf => Int): List[String] = {
    def method(signature: String, statements: List[String]) = {
      s"""$signature: _root_.scala.Unit = {
        |${statements.mkString("\n")}
        |}""".stripMargin
    }
    def writes(leaves: List[(MessageMember.Leaf, String)]) = {
      val (locals, fragments) = withGroupLocals(leaves)
      locals ++ fragments.map(_.write)
    }
    val signature = s"override private[zga] def writeTo(output: $CodedOutput, value: $scalaType, sizes: $Sizes)"
    Utils.chunkBy(written, MaxMethodBytes)((leaf, _) => bytes(leaf)) match {
      case List(leaves) => List(method(signature, writes(leaves)))
      case parts =>
        val calls = parts.indices.map(i => s"writeTo$i(output, value, sizes)").toList
        method(signature, calls) :: parts.zipWithIndex.map { (leaves, i) =>
          method(s"private def writeTo$i(output: $CodedOutput, value: $scalaType, $PartSizes)", writes(leaves))
        }
    }
  }

  /**
   * Hoists each group's accessor into a local, and returns the declarations and
   * the fragments rewritten to use them.
   *
   * A leaf of a chunked message reads `value.partN.field`; `partN` is an
   * accessor that the JIT does not inline into a method with many of them, so
   * evaluating it per leaf makes encoding much slower. The generated
   * `serializedSize` and `writeTo` methods declare these locals first.
   */
  private def withGroupLocals(leaves: List[(MessageMember.Leaf, String)]): (List[String], List[LeafFragments]) = {
    val (locals, paths) = groupLocals(leaves.map(_._2))
    (locals, leaves.zip(paths).map { case ((leaf, _), path) => leafFragments(leaf, path) })
  }

  /**
   * Rewrites each path to start from a local of its immediate group:
   * `value.part4.field` becomes `value_part4.field`, declared as
   * `val value_part4 = value.part4`. A path directly under `value` is
   * unchanged. Returns the declarations in the order the groups first appear,
   * and the rewritten paths.
   */
  private def groupLocals(paths: List[String]): (List[String], List[String]) = {
    val locals = mutable.LinkedHashMap.empty[String, String]
    val rewritten = paths.map { path =>
      val i = path.lastIndexOf('.')
      val group = path.substring(0, i)
      if (!group.contains('.')) path
      else locals.getOrElseUpdate(group, group.replace('.', '_')) + path.substring(i)
    }
    (locals.map((group, local) => s"val $local = $group").toList, rewritten)
  }

  /**
   * Reads the fields into local variables, or, when there are too many to read
   * in one method, into the variables of a class whose methods each read a part
   * of the tags.
   */
  private def printRead(
    m: Descriptors.Descriptor,
    parameters: List[MessageMember],
    fragments: MessageMember.Leaf => LeafFragments,
  ): List[String] = {
    val scalaType = m.scalaType
    val leaves = leavesOf(parameters, "value").map((leaf, _) => fragments(leaf))
    val variables = leaves.map(_.declaration).mkString("\n")
    val signature = s"override private[zga] def readFrom(reader: $ProtoReader, value: $scalaType): $scalaType"
    val hasGroups = parameters.exists(_.isInstanceOf[MessageMember.Group])
    val cases = leaves.flatMap(_.cases)
    if (!hasGroups && cases.map(_.bytes).sum + leaves.length * VariableBytes <= MaxMethodBytes) {
      val constructed = construct(m, m.scalaPath, parameters, fragments(_).argument)
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
      val groups = groupsOf(parameters, "value", None)
      val groupOf = groups.flatMap(group => group.leaves.map(_ -> group.name)).toMap
      // a leaf of a group copies the group from the value being merged into when it is first read
      val cases = leavesOf(parameters, "value").flatMap { (leaf, _) =>
        val leafCases = fragments(leaf).cases
        groupOf.get(leaf).fold(leafCases)(group => leafCases.map(c => c.copy(read = s"_touch_$group(); ${c.read}", bytes = c.bytes + TouchBytes)))
      }
      val parts = Utils.chunkBy(cases, MaxMethodBytes)(_.bytes)
      val reads = parts.indices.map(i => s"fields.read$i(tag)").mkString(" || ")
      val readMethods = parts.zipWithIndex.map { (cases, i) =>
        s"""def read$i(tag: _root_.scala.Int): _root_.scala.Boolean = tag match {
          |${cases.map(c => s"case ${c.tag} =>\n  ${c.read}\n  true").mkString("\n")}
          |  case _ => false
          |}""".stripMargin
      }
      val (result, constructMethods) = constructInMethods(m, parameters, fragments(_).argument)
      val (copied, uncopied) = leaves.partition(_.copiesValue)
      // private, so that they are accessed directly rather than through accessor methods
      val declarations = copied.map(l => s"private var ${l.variable}: ${l.variableType} = _root_.scala.compiletime.uninitialized") ++
        uncopied.map(l => s"private ${l.declaration}") ++
        groups.map(group => s"private var _touched_${group.name}: _root_.scala.Boolean = false")
      // the variables of leaves outside of a group start from the value being merged into, copied in methods small
      // enough for the JIT to inline the field accessors they call
      val ungrouped = leavesOf(parameters, "value").collect { case (leaf, _) if !groupOf.contains(leaf) => fragments(leaf) }
      val copyMethods = ungrouped.filter(_.copiesValue).grouped(CopiesPerMethod).toList.zipWithIndex.map { (leaves, i) =>
        s"""private def _copy_$i(): _root_.scala.Unit = {
          |${leaves.map(l => s"${l.variable} = ${l.initial}").mkString("\n")}
          |}""".stripMargin
      }
      val touchMethods = groups.map { group =>
        val copies = group.leaves.map(leaf => (leaf, fragments(leaf))).collect {
          case (leaf, l) if l.copiesValue => s"${l.variable} = previous.${Utils.escapeScalaKeyword(leaf.scalaName)}"
        }
        s"""private def _touch_${group.name}(): _root_.scala.Unit = if (!_touched_${group.name}) {
          |  _touched_${group.name} = true
          |${group.parent.fold("")(parent => s"_touch_$parent()")}
          |  val previous = ${group.path}
          |${copies.mkString("\n")}
          |}""".stripMargin
      }
      // a proto name cannot start with an underscore, so the class cannot collide with a nested type of the companion,
      // and a Scala field name has no underscores, so `_copy_N` and `_construct_PartN` cannot collide with a variable
      List(
        s"""$signature = {
          |  val fields = new _Fields(reader, value)
          |  var done = false
          |  while (!done) {
          |    val tag = reader.input.readTag()
          |    done = tag == 0 || !($reads || reader.input.skipField(tag))
          |  }
          |  fields.result
          |}""".stripMargin,
        s"""private final class _Fields(reader: $ProtoReader, value: $scalaType) {
          |  val input = reader.input
          |${declarations.mkString("\n")}
          |${copyMethods.indices.map(i => s"_copy_$i()").mkString("\n")}
          |
          |${copyMethods.mkString("\n\n")}
          |
          |${touchMethods.mkString("\n\n")}
          |
          |${readMethods.mkString("\n\n")}
          |
          |  def result: $scalaType = $result
          |
          |${constructMethods.mkString("\n\n")}
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
    if (values.isEmpty) scalaPath else newInstance(scalaPath, values)
  }

  // `new` rather than the companion's `apply`, which would pass on every argument again
  private def newInstance(scalaPath: String, values: List[String]) = {
    values.mkString(s"new $scalaPath(\n  ", ",\n  ", ",\n)").replace("\n", "\n    ")
  }

  /**
   * Constructs a message with a method for each of its groups, so that the
   * construction of a message too large for one method is not in one. A group
   * none of whose leaves were read is that of the value being merged into.
   */
  private def constructInMethods(
    m: Descriptors.Descriptor,
    parameters: List[MessageMember],
    argument: MessageMember.Leaf => String,
  ): (String, List[String]) = {
    def values(members: List[MessageMember], path: String): (List[String], List[String]) = {
      val (arguments, methods) = members.map {
        case MessageMember.Group(name, parameterName, groupMembers) =>
          val groupType = s"${m.scalaPath}.$name"
          val method = s"_construct_$name"
          val groupPath = s"$path.${Utils.escapeScalaKeyword(parameterName)}"
          val (groupArguments, nested) = values(groupMembers, groupPath)
          (
            s"${Utils.escapeScalaKeyword(parameterName)} = if (_touched_$name) $method else $groupPath",
            s"private def $method: $groupType = ${newInstance(groupType, groupArguments)}" :: nested,
          )
        case leaf: MessageMember.Leaf => (s"${Utils.escapeScalaKeyword(leaf.scalaName)} = ${argument(leaf)}", Nil)
      }.unzip
      (arguments, methods.flatten)
    }
    val (arguments, methods) = values(parameters, "value")
    (newInstance(m.scalaPath, arguments), methods)
  }

  /**
   * A group of a message too large for one case class, with the leaves directly
   * in it.
   */
  private case class GroupLeaves(name: String, path: String, parent: Option[String], leaves: List[MessageMember.Leaf])

  private def groupsOf(members: List[MessageMember], path: String, parent: Option[String]): List[GroupLeaves] = members.flatMap {
    case MessageMember.Group(name, parameterName, groupMembers) =>
      val groupPath = s"$path.${Utils.escapeScalaKeyword(parameterName)}"
      val leaves = groupMembers.collect { case leaf: MessageMember.Leaf => leaf }
      GroupLeaves(name, groupPath, parent, leaves) :: groupsOf(groupMembers, groupPath, Some(name))
    case _: MessageMember.Leaf => Nil
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
  // the call that copies a leaf's group before a case reads it
  private val TouchBytes = 5

  // the number of variables copied from the value being merged into by each method
  private val CopiesPerMethod = 64

  /**
   * A field without presence, written unless it holds its default value.
   */
  private def singularFragments(f: Descriptors.FieldDescriptor, path: String) = {
    val variable = variableName(f.scalaName)
    LeafFragments(
      variable = variable,
      variableType = f.scalaTypeName,
      initial = path,
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
      variable = variable,
      variableType = f.scalaTypeName,
      initial = path,
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
      variable = variable,
      variableType = builderType,
      initial = "null",
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
      variable = variable,
      variableType = s"_root_.scala.collection.mutable.Builder[(${key.scalaType}, ${value.scalaType}), $mapType]",
      initial = "null",
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
      variable = variable,
      variableType = s"_root_.scala.Option[${o.scalaType}]",
      initial = path,
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
    case Type.ENUM => s"${f.getEnumType.scalaPath}.fromValue(input.readEnum())"
    case Type.STRING if f.needsUtf8Check => "input.readStringRequireUtf8()"
    case Type.GROUP => throw IllegalArgumentException(s"${f.getFullName} is a group field, which is not supported")
    case _ => s"input.read${scalarName(f)}()"
  }

  private def writeValue(f: Descriptors.FieldDescriptor, value: String) = f.getType match {
    case Type.MESSAGE => s"${messageCodec(f)}.writeDelimited(output, $value, sizes)"
    case Type.ENUM => s"output.writeEnumNoTag(${enumNumber(f, value)})"
    case _ => s"output.write${scalarName(f)}NoTag($value)"
  }

  private def valueSize(f: Descriptors.FieldDescriptor, value: String) = f.getType match {
    case Type.MESSAGE => s"${messageCodec(f)}.delimitedSize($value, sizes)"
    case Type.ENUM => s"$CodedOutput.computeEnumSizeNoTag(${enumNumber(f, value)})"
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
    case Type.ENUM => s"${enumNumber(f, value)} != 0"
    case Type.MESSAGE | Type.GROUP => throw IllegalArgumentException(s"${f.getFullName} always has presence")
  }

  /**
   * The number of an enum value. A generated enum has it as its `value`. An
   * enum mapped to `java.time` is held as an `Option`, which the proto enum's
   * object converts with its `value` method, `None` becoming the unspecified
   * number.
   */
  private def enumNumber(f: Descriptors.FieldDescriptor, value: String) =
    JavaEnumType.of(f).fold(s"$value.value")(_ => s"${f.getEnumType.scalaPath}.value($value)")
}
