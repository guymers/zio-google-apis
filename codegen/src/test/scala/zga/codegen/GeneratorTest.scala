package zga.codegen

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DescriptorProtos.SourceCodeInfo
import com.google.protobuf.Descriptors
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.jdk.CollectionConverters.*
import scala.util.Try

object GeneratorTest extends ZIOSpecDefault {
  import TestDescriptors.*

  override val spec = suite("Generator")(
    suite("field defaults")(
      test("required field behavior has no default") {
        val output = generate(List(stringField(options = Some(requiredOptions))))
        assertTrue(output.contains("  name: _root_.scala.Predef.String,"))
      },
      test("field without a required marker has a default") {
        val output = generate(List(stringField()))
        assertTrue(output.contains("""  name: _root_.scala.Predef.String = "","""))
      },
      test("required message field has no default") {
        val output = generate(List(messageField(Some(requiredOptions))))
        assertTrue(output.contains("  nested: _root_.scala.Option[_root_.test.Message],"))
      },
      test("required repeated field has an empty default") {
        val output = generate(List(stringField(options = Some(requiredOptions), label = Label.LABEL_REPEATED)))
        assertTrue(output.contains("  name: _root_.zio.Chunk[_root_.scala.Predef.String] = _root_.zio.Chunk.empty,"))
      },
    ),
    suite("package mapping")(
      test("generates messages in the remapped package") {
        val output = generate(List(stringField()), javaPackage = Some("com.google.rpc"))
        assertTrue(output.contains("package zga.google.rpc")) &&
        assertTrue(output.contains("_root_.zga.google.rpc.Message"))
      },
      test("uses the proto package when java_package is not set") {
        val output = generate(List(stringField()), javaPackage = None)
        assertTrue(output.contains("package test")) &&
        assertTrue(output.contains("_root_.test.Message"))
      },
      test("omits the package declaration for a proto without a package") {
        val output = generate(List(stringField()), pkg = "", javaPackage = None)
        assertTrue(!output.contains("\npackage ")) &&
        assertTrue(output.contains("_root_.Message"))
      },
    ),
    suite("file names")(
      test("includes the package path") {
        val file = fileDescriptor("google/api/annotations.proto", javaPackage = Some("com.google.api"))
        assertTrue(Generator.fileName(file) == "zga/google/api/annotations.proto.scala")
      },
      test("handles a proto without a package") {
        val file = fileDescriptor("test.proto", javaPackage = None)
        assertTrue(Generator.fileName(file) == "test.proto.scala")
      },
    ),
    suite("keyword field names")(
      test("escapes hard keywords") {
        val output = generate(List(
          stringField(name = "val"),
          stringField(name = "match", number = 2),
          stringField(name = "export", number = 3),
        ))
        assertTrue(output.contains("""  `val`: _root_.scala.Predef.String = "",""")) &&
        assertTrue(output.contains("""  `match`: _root_.scala.Predef.String = "",""")) &&
        assertTrue(output.contains("""  `export`: _root_.scala.Predef.String = "","""))
      },
      test("soft keywords are unescaped") {
        val output = generate(List(stringField(name = "extension")))
        assertTrue(output.contains("""  extension: _root_.scala.Predef.String = "","""))
      },
    ),
    suite("field names")(
      test("converts a proto field name to camelCase") {
        val output = generate(List(stringField(name = "resource_type")))
        assertTrue(output.contains("""  resourceType: _root_.scala.Predef.String = "","""))
      },
      test("converts every word of a proto field name") {
        val output = generate(List(stringField(name = "next_page_token")))
        assertTrue(output.contains("""  nextPageToken: _root_.scala.Predef.String = "","""))
      },
      test("keeps a single word proto field name") {
        val output = generate(List(stringField(name = "name")))
        assertTrue(output.contains("""  name: _root_.scala.Predef.String = "","""))
      },
      test("escapes a keyword that a proto field name converts to") {
        val output = generate(List(stringField(name = "_type")))
        assertTrue(output.contains("""  `type`: _root_.scala.Predef.String = "","""))
      },
      test("uses the converted name as the scaladoc parameter") {
        val sourceInfo = SourceCodeInfo.newBuilder
          .addLocation(
            SourceCodeInfo.Location.newBuilder
              .addPath(FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER)
              .addPath(0)
              .addPath(DescriptorProto.FIELD_FIELD_NUMBER)
              .addPath(0)
              .setLeadingComments("The type of the resource."),
          )
          .build()
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
          .addMessageType(DescriptorProto.newBuilder.setName("Message").addField(stringField(name = "resource_type")))
          .setSourceCodeInfo(sourceInfo)
          .build()
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
        assertTrue(output.contains("  * @param resourceType"))
      },
      test("removes underscores and capitalizes the following character") {
        assertTrue(scalaNames("resource_type", "next_page_token", "name", "foo__bar", "foo_") == List("resourceType", "nextPageToken", "name", "fooBar", "foo"))
      },
      test("lower cases the first character") {
        assertTrue(scalaNames("Disk_Quota", "Name", "nat_ip") == List("diskQuota", "name", "natIp"))
      },
      test("lower cases an all upper case name entirely") {
        assertTrue(scalaNames("RAM", "ID", "URL") == List("ram", "id", "url"))
      },
      test("keeps the proto name when the camel cased name would not be a valid identifier") {
        assertTrue(scalaNames("_1foo") == List("_1foo"))
      },
      test("rejects fields that convert to the same Scala name") {
        val generated = Try(generate(List(
          stringField(name = "foo_bar"),
          stringField(name = "fooBar", number = 2),
        )))
        val message = generated.failed.toOption.map(_.getMessage)
        assertTrue(generated.isFailure) &&
        assertTrue(message.exists(_.contains("all map to the Scala name 'fooBar'"))) &&
        assertTrue(message.exists(m => m.contains("'fooBar'") && m.contains("'foo_bar'")))
      },
      test("rejects a field that converts to a member of the case class") {
        val names = List(
          "wait",
          "to_string",
          "hash_code",
          "product_arity",
          "notify_all",
          "copy",
          "product_element",
          "can_equal",
          "product_element_name",
        )
        assertTrue(names.zipWithIndex.forall { case (name, index) =>
          Try(generate(List(stringField(name = name, number = index + 1)))).isFailure
        })
      },
    ),
    suite("codecs")(
      test("read, write and size each proto type with protobuf-java") {
        val types = List(
          (FieldDescriptorProto.Type.TYPE_DOUBLE, "Double", 1),
          (FieldDescriptorProto.Type.TYPE_FLOAT, "Float", 5),
          (FieldDescriptorProto.Type.TYPE_INT64, "Int64", 0),
          (FieldDescriptorProto.Type.TYPE_UINT64, "UInt64", 0),
          (FieldDescriptorProto.Type.TYPE_INT32, "Int32", 0),
          (FieldDescriptorProto.Type.TYPE_FIXED64, "Fixed64", 1),
          (FieldDescriptorProto.Type.TYPE_FIXED32, "Fixed32", 5),
          (FieldDescriptorProto.Type.TYPE_BOOL, "Bool", 0),
          (FieldDescriptorProto.Type.TYPE_BYTES, "Bytes", 2),
          (FieldDescriptorProto.Type.TYPE_UINT32, "UInt32", 0),
          (FieldDescriptorProto.Type.TYPE_SFIXED32, "SFixed32", 5),
          (FieldDescriptorProto.Type.TYPE_SFIXED64, "SFixed64", 1),
          (FieldDescriptorProto.Type.TYPE_SINT32, "SInt32", 0),
          (FieldDescriptorProto.Type.TYPE_SINT64, "SInt64", 0),
        )
        val output = generate(types.zipWithIndex.map { case ((fieldType, _, _), index) =>
          field(name = s"field${index + 1}", number = index + 1, fieldType = fieldType)
        })
        assertTrue(types.zipWithIndex.forall { case ((_, name, wireType), index) =>
          val number = index + 1
          output.contains(s"case ${(number << 3) | wireType} => _field$number = input.read$name()") &&
          trimmed(output).contains(s"output.writeTag($number, $wireType)\noutput.write${name}NoTag(value.field$number)") &&
          output.contains(s"_root_.com.google.protobuf.CodedOutputStream.compute${name}SizeNoTag(value.field$number)")
        })
      },
      test("skip a field without presence that holds its default value") {
        val output = generate(
          List(
            field("count", 1, FieldDescriptorProto.Type.TYPE_INT64),
            field("ratio", 2, FieldDescriptorProto.Type.TYPE_FLOAT),
            field("enabled", 3, FieldDescriptorProto.Type.TYPE_BOOL),
            stringField(number = 4),
          ),
        )
        assertTrue(output.contains("if (value.count != 0L) {")) &&
        // negative zero is not the default
        assertTrue(output.contains("if (_root_.java.lang.Float.floatToRawIntBits(value.ratio) != 0) {")) &&
        assertTrue(output.contains("if (value.enabled) {")) &&
        assertTrue(output.contains("if (!value.name.isEmpty) {"))
      },
      test("validate a proto3 string but not a proto2 one") {
        assertTrue(generate(List(stringField()), syntax = "proto3").contains("input.readStringRequireUtf8()")) &&
        assertTrue(generate(List(stringField()), syntax = "proto2").contains("_name = _root_.scala.Some(input.readString())"))
      },
      test("use the enum's values and the message's own codec") {
        val proto = FileDescriptorProto.newBuilder(enumFile("VALUE"))
          .addMessageType(
            DescriptorProto.newBuilder
              .setName("Message")
              .addField(field("kind", 1, FieldDescriptorProto.Type.TYPE_ENUM, typeName = Some(".test.Kind")))
              .addField(messageField(None)),
          )
          .build()
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
        assertTrue(output.contains("case 8 => _kind = _root_.test.Kind.fromValue(input.readEnum())")) &&
        assertTrue(output.contains("output.writeEnumNoTag(value.kind.value)")) &&
        // a message read again merges into the previous value
        assertTrue(output.contains("var _nested: _root_.scala.Option[_root_.test.Message] = value.nested")) &&
        assertTrue(output.contains("case 18 => _nested = _root_.scala.Some(_root_.test.Message.messageCodec.readDelimited(reader, _nested))")) &&
        assertTrue(output.contains("_root_.test.Message.messageCodec.writeDelimited(output, v, sizes)"))
      },
      test("a field with presence is written whenever it is set") {
        val output = generate(List(stringField()), syntax = "proto2")
        assertTrue(trimmed(output).contains(
          """value.name match {
            |case _root_.scala.Some(v) =>
            |output.writeTag(1, 2)
            |output.writeStringNoTag(v)
            |case _root_.scala.None => ()
            |}""".stripMargin,
        ))
      },
      test("a required field behavior does not change the wire format") {
        val output = generate(List(stringField(options = Some(requiredOptions))))
        assertTrue(output.contains("if (!value.name.isEmpty) {")) &&
        assertTrue(!output.contains("Required("))
      },
      test("proto3 packs repeated scalars, and reads them either way") {
        val output = generate(
          List(
            field("numbers", 1, FieldDescriptorProto.Type.TYPE_SINT32, FieldDescriptorProto.Label.LABEL_REPEATED),
            stringField(name = "names", number = 2, label = FieldDescriptorProto.Label.LABEL_REPEATED),
          ),
        )
        // a primitive is read into a builder of its type, created once an element is read
        assertTrue(output.contains("var _numbers: _root_.zio.ChunkBuilder.Int = null")) &&
        assertTrue(output.contains("case 8 => if (_numbers == null) _numbers = new _root_.zio.ChunkBuilder.Int; _numbers.addOne(input.readSInt32())")) &&
        assertTrue(
          output.contains("case 10 => if (_numbers == null) _numbers = new _root_.zio.ChunkBuilder.Int; reader.packed(_numbers.addOne(input.readSInt32()))"),
        ) &&
        assertTrue(
          trimmed(output).contains(
            "if (value.numbers.nonEmpty) {\nsize += 1 + sizes.delimited(_root_.zga.common.MessageCodec.dataSize(value.numbers)(v => _root_.com.google.protobuf.CodedOutputStream.computeSInt32SizeNoTag(v)))\n}",
          ),
        ) &&
        assertTrue(trimmed(output).contains("\nif (value.numbers.nonEmpty) {\noutput.writeTag(1, 2)\n")) &&
        assertTrue(output.contains("var _names: _root_.zio.ChunkBuilder[_root_.scala.Predef.String] = null")) &&
        assertTrue(
          output.contains(
            "case 18 => if (_names == null) _names = _root_.zio.ChunkBuilder.make[_root_.scala.Predef.String](); _names.addOne(input.readStringRequireUtf8())",
          ),
        ) &&
        // no element read keeps the value being merged into
        assertTrue(output.contains(
          "numbers = if (_numbers == null) value.numbers else if (value.numbers.isEmpty) _numbers.result() else value.numbers ++ _numbers.result()",
        )) &&
        assertTrue(!output.contains("reader.packed(_names")) &&
        assertTrue(trimmed(output).contains("\nvalue.names.foreach { v =>\noutput.writeTag(2, 2)\n"))
      },
      test("proto2 packs repeated scalars only when asked to") {
        val packed = FieldOptions.newBuilder.setPacked(true).build()
        val output = generate(
          List(
            field("unpacked", 1, FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Label.LABEL_REPEATED),
            field("packed", 2, FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Label.LABEL_REPEATED, options = Some(packed)),
          ),
          syntax = "proto2",
        )
        assertTrue(trimmed(output).contains("\nvalue.unpacked.foreach { v =>\noutput.writeTag(1, 0)\n")) &&
        assertTrue(trimmed(output).contains("\nif (value.packed.nonEmpty) {\noutput.writeTag(2, 2)\n"))
      },
      test("size repeated fixed width values by their count") {
        val output = generate(
          List(
            field("ratios", 1, FieldDescriptorProto.Type.TYPE_DOUBLE, FieldDescriptorProto.Label.LABEL_REPEATED),
            field("flags", 2, FieldDescriptorProto.Type.TYPE_BOOL, FieldDescriptorProto.Label.LABEL_REPEATED),
            field("ids", 3, FieldDescriptorProto.Type.TYPE_SFIXED32, FieldDescriptorProto.Label.LABEL_REPEATED),
          ),
          syntax = "proto2",
        )
        assertTrue(output.contains("size += value.ratios.length * 1 + value.ratios.length * 8")) &&
        assertTrue(output.contains("size += value.flags.length * 1 + value.flags.length * 1")) &&
        assertTrue(output.contains("size += value.ids.length * 1 + value.ids.length * 4")) &&
        assertTrue(output.contains("var _ratios: _root_.zio.ChunkBuilder.Double = null")) &&
        assertTrue(output.contains("var _flags: _root_.zio.ChunkBuilder.Boolean = null")) &&
        assertTrue(!output.contains("MessageCodec.dataSize"))
      },
      test("a map field reads and writes entry messages") {
        val entry = DescriptorProto.newBuilder
          .setName("LabelsEntry")
          .setOptions(MessageOptions.newBuilder.setMapEntry(true))
          .addField(field("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
          .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_SINT64))
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .addMessageType(
            DescriptorProto.newBuilder
              .setName("Message")
              .addNestedType(entry)
              .addField(
                field("labels", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, FieldDescriptorProto.Label.LABEL_REPEATED, Some(".test.Message.LabelsEntry")),
              ),
          )
          .build()
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
        assertTrue(
          output.contains("""case 10 => if (_labels == null) _labels = _root_.scala.collection.immutable.Map.newBuilder[_root_.scala.Predef.String, _root_.scala.Long]; reader.mapEntry(10, 16, "", 0L)(input.readStringRequireUtf8(), _ => input.readSInt64())((k, v) => _labels += k -> v)"""),
        ) &&
        assertTrue(output.contains("_root_.zga.common.MessageCodec.mapSize(value.labels, sizes)((k, v) => 1 +")) &&
        assertTrue(output.contains("sizes.writeLength(output)")) &&
        // entries read are appended to those of the value being merged into
        assertTrue(
          output.contains("labels = if (_labels == null) value.labels else if (value.labels.isEmpty) _labels.result() else value.labels ++ _labels.result()"),
        ) &&
        assertTrue(!output.contains("LabelsEntry"))
      },
      test("a closed enum drops unrecognized numbers") {
        val proto = FileDescriptorProto.newBuilder(enumFile("VALUE", "proto2"))
          .addMessageType(
            DescriptorProto.newBuilder
              .setName("Message")
              .addField(field("kind", 1, FieldDescriptorProto.Type.TYPE_ENUM, typeName = Some(".test.Kind")))
              .addField(field("kinds", 2, FieldDescriptorProto.Type.TYPE_ENUM, FieldDescriptorProto.Label.LABEL_REPEATED, Some(".test.Kind"))),
          )
          .build()
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
        assertTrue(output.contains("case 8 => _kind = _root_.test.Kind.fromValue(input.readEnum()) match { case _root_.scala.None => _kind; case v => v }")) &&
        assertTrue(output.contains("case 16 => if (_kinds == null) _kinds = _root_.zio.ChunkBuilder.make[_root_.test.Kind](); _kinds ++= _root_.test.Kind.fromValue(input.readEnum())"))
      },
      test("write fields in field number order, a oneof at its first member's number") {
        val output = generateOneof(
          List(
            stringField(name = "second", number = 2),
            oneofField("first", 1, 0),
            oneofField("third", 3, 0),
          ),
          List("choice"),
        )
        assertTrue(
          trimmed(output).contains(
            """override private[zga] def serializedSize(value: _root_.test.Message, sizes: _root_.zga.common.Sizes): _root_.scala.Int = {
              |var size = 0
              |size += _root_.zga.common.Oneof.serializedSize(value.choice, sizes)
              |if (!value.second.isEmpty) {
              |size += 1 + _root_.com.google.protobuf.CodedOutputStream.computeStringSizeNoTag(value.second)
              |}
              |size
              |}""".stripMargin,
          ),
        ) &&
        assertTrue(
          trimmed(output).contains(
            """override private[zga] def writeTo(output: _root_.com.google.protobuf.CodedOutputStream, value: _root_.test.Message, sizes: _root_.zga.common.Sizes): _root_.scala.Unit = {
              |_root_.zga.common.Oneof.writeTo(output, value.choice, sizes)
              |if (!value.second.isEmpty) {
              |output.writeTag(2, 2)
              |output.writeStringNoTag(value.second)
              |}
              |}""".stripMargin,
          ),
        )
      },
      test("a large message reads and writes its chunks' fields") {
        val fields = (1 to 255).map(index => stringField(name = s"field$index", number = index)).toList
        val codec = generateCodecFiles(fields)("test/MessageMessageCodec.scala")
        assertTrue(codec.contains("output.writeStringNoTag(value.part2.field255)")) &&
        assertTrue(trimmed(codec).contains("\npart2 = _root_.test.Message.Part2(\n")) &&
        assertTrue(!codec.contains("groupCodec"), !generate(fields).contains("groupCodec"))
      },
      test("a codec of deprecated fields does not warn about them") {
        val deprecated = FieldOptions.newBuilder.setDeprecated(true).build()
        assertTrue(generate(List(stringField(options =
          Some(deprecated),
        ))).contains("@_root_.scala.annotation.nowarn(\"cat=deprecation\")\ngiven messageCodec")) &&
        assertTrue(!generate(List(stringField())).contains("nowarn"))
      },
    ),
    suite("enums")(
      test("an open enum keeps unrecognized values") {
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile("VALUE"), Array.empty))
        assertTrue(output.contains("sealed trait Kind derives _root_.scala.CanEqual {")) &&
        assertTrue(output.contains("enum Recognized extends _root_.test.Kind {")) &&
        assertTrue(output.contains("case VALUE\n")) &&
        assertTrue(output.contains("def value: _root_.scala.Int = ordinal")) &&
        assertTrue(output.contains("export Recognized.*")) &&
        assertTrue(output.contains("case class Unrecognized private[Kind] (value: _root_.scala.Int) extends _root_.test.Kind")) &&
        assertTrue(output.contains("def fromValue(v: _root_.scala.Int): _root_.test.Kind = valueMap.getOrElse(v, Unrecognized(v))"))
      },
      test("a closed enum is a plain enum") {
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile("VALUE", "proto2"), Array.empty))
        assertTrue(output.contains("enum Kind derives _root_.scala.CanEqual {")) &&
        assertTrue(output.contains("case VALUE\n")) &&
        assertTrue(output.contains("def value: _root_.scala.Int = ordinal")) &&
        assertTrue(!output.contains("Unrecognized")) &&
        assertTrue(output.contains("def fromValue(v: _root_.scala.Int): _root_.scala.Option[Kind] = valueMap.get(v)"))
      },
      test("values numbered other than by ordinal look up their numbers") {
        val proto = enumFile("VALUE").toBuilder
        proto.getEnumTypeBuilder(0).addValue(EnumValueDescriptorProto.newBuilder.setName("OTHER").setNumber(5))
        val open = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto.build(), Array.empty))
        proto.setSyntax("proto2")
        val closed = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto.build(), Array.empty))
        assertTrue(open.contains("def value: _root_.scala.Int = numbers(ordinal)")) &&
        assertTrue(open.contains("private val numbers = _root_.scala.Array[_root_.scala.Int](0, 5)")) &&
        assertTrue(closed.contains("def value: _root_.scala.Int = Kind.numbers(ordinal)")) &&
        assertTrue(closed.contains("private val numbers = _root_.scala.Array[_root_.scala.Int](0, 5)"))
      },
      test("an open enum rejects a value named after its nested types") {
        assertTrue(List("Recognized", "Unrecognized").forall { name =>
          Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile(name), Array.empty))).isFailure
        })
      },
      test("rejects an open enum named Unrecognized") {
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .addEnumType(
            EnumDescriptorProto.newBuilder
              .setName("Unrecognized")
              .addValue(EnumValueDescriptorProto.newBuilder.setName("VALUE").setNumber(0)),
          )
          .build()
        assertTrue(Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))).isFailure)
      },
      test("a closed enum accepts a value named like the open enum's nested types") {
        assertTrue(List("Recognized", "Unrecognized").forall { name =>
          Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile(name, "proto2"), Array.empty))).isSuccess
        })
      },
    ),
    suite("oneofs")(
      test("generates a sealed trait, cases and an optional parameter") {
        val output = generateOneof(List(oneofField("error", 1, 0), oneofField("response", 2, 0)), List("result"))
        assertTrue(output.contains("  result: _root_.scala.Option[_root_.test.Message.Result] = _root_.scala.None,")) &&
        assertTrue(output.contains("sealed trait Result extends _root_.zga.common.Oneof\n")) &&
        assertTrue(output.contains("case class Error(value: _root_.scala.Predef.String) extends _root_.test.Message.Result {")) &&
        assertTrue(output.contains("case class Response(value: _root_.scala.Predef.String) extends _root_.test.Message.Result {")) &&
        assertTrue(!output.contains("oneofCodec"))
      },
      test("reads each member into the oneof and writes the set member") {
        val output = generateOneof(List(oneofField("error", 1, 0), oneofField("response", 2, 0)), List("result"))
        assertTrue(output.contains("case 10 => _result = _root_.scala.Some(_root_.test.Message.Result.Error(input.readStringRequireUtf8()))")) &&
        assertTrue(output.contains("case 18 => _result = _root_.scala.Some(_root_.test.Message.Result.Response(input.readStringRequireUtf8()))")) &&
        assertTrue(output.contains("size += _root_.zga.common.Oneof.serializedSize(value.result, sizes)")) &&
        assertTrue(output.contains("_root_.zga.common.Oneof.writeTo(output, value.result, sizes)"))
      },
      test("each member's case sizes and writes the member") {
        val output = generateOneof(List(oneofField("error", 1, 0), oneofField("response", 2, 0)), List("result"))
        val error = output.substring(output.indexOf("case class Error("), output.indexOf("case class Response("))
        assertTrue(
          error.contains(
            "override private[zga] def serializedSize(sizes: _root_.zga.common.Sizes): _root_.scala.Int = 1 + _root_.com.google.protobuf.CodedOutputStream.computeStringSizeNoTag(value)",
          ),
        ) &&
        assertTrue(trimmed(error).contains("output.writeTag(1, 2)\noutput.writeStringNoTag(value)\n"))
      },
      test("does not generate a parameter per member field") {
        val output = generateOneof(List(oneofField("error", 1, 0), oneofField("response", 2, 0)), List("result"))
        assertTrue(!output.contains("  error:")) &&
        assertTrue(!output.contains("  response:"))
      },
      test("positions the parameter where the oneof's first field is declared") {
        val output = generateOneof(
          List(stringField(name = "name", number = 1), oneofField("error", 2, 0), oneofField("response", 3, 0)),
          List("result"),
        )
        assertTrue(output.indexOf("  name:") < output.indexOf("  result:"))
      },
      test("generates one type per oneof declaration") {
        val output = generateOneof(List(oneofField("a", 1, 0), oneofField("b", 2, 1)), List("first", "second"))
        assertTrue(output.contains("sealed trait First")) &&
        assertTrue(output.contains("sealed trait Second")) &&
        assertTrue(output.contains("first: _root_.scala.Option[_root_.test.Message.First]")) &&
        assertTrue(output.contains("second: _root_.scala.Option[_root_.test.Message.Second]"))
      },
      test("names a case after its field in PascalCase") {
        val output = generateOneof(List(oneofField("revision_reason", 1, 0)), List("reasons"))
        assertTrue(output.contains("case class RevisionReason(value:")) &&
        assertTrue(output.contains("reasons: _root_.scala.Option[_root_.test.Message.Reasons]"))
      },
      test("converts a snake_case oneof name") {
        val output = generateOneof(List(oneofField("a", 1, 0)), List("value_type"))
        assertTrue(output.contains("valueType: _root_.scala.Option[_root_.test.Message.ValueType]"))
      },
      test("escapes a keyword oneof name") {
        val output = generateOneof(List(oneofField("fixed_fee", 1, 0)), List("type"))
        assertTrue(output.contains("  `type`: _root_.scala.Option[_root_.test.Message.Type] = _root_.scala.None,"))
      },
      test("treats a proto3 optional field as an optional field") {
        val field = FieldDescriptorProto.newBuilder(stringField(name = "name", number = 1))
          .setProto3Optional(true)
          .setOneofIndex(0)
          .build()
        val output = generateOneof(List(field), List("_name"))
        assertTrue(output.contains("  name: _root_.scala.Option[_root_.scala.Predef.String] = _root_.scala.None,")) &&
        assertTrue(!output.contains("sealed trait"))
      },
      test("a oneof whose members are all required removes the None default") {
        val output = generateOneof(
          List(
            oneofField("error", 1, 0, Some(requiredOptions)),
            oneofField("response", 2, 0, Some(requiredOptions)),
          ),
          List("result"),
        )
        assertTrue(output.contains("  result: _root_.scala.Option[_root_.test.Message.Result],"))
      },
      test("a oneof with only some required members keeps the None default") {
        val output = generateOneof(
          List(oneofField("error", 1, 0, Some(requiredOptions)), oneofField("response", 2, 0)),
          List("result"),
        )
        assertTrue(output.contains("  result: _root_.scala.Option[_root_.test.Message.Result] = _root_.scala.None,"))
      },
      test("a deprecated member does not stop a oneof being required") {
        val deprecated = requiredOptions.toBuilder.setDeprecated(true).build()
        val output = generateOneof(
          List(oneofField("error", 1, 0, Some(requiredOptions)), oneofField("response", 2, 0, Some(deprecated))),
          List("result"),
        )
        assertTrue(output.contains("  result: _root_.scala.Option[_root_.test.Message.Result],"))
      },
      test("a oneof whose only member is deprecated is not required") {
        val deprecated = requiredOptions.toBuilder.setDeprecated(true).build()
        val output = generateOneof(List(oneofField("error", 1, 0, Some(deprecated))), List("result"))
        assertTrue(output.contains("  result: _root_.scala.Option[_root_.test.Message.Result] = _root_.scala.None,"))
      },
      test("rejects a oneof whose type name collides with a nested type") {
        val message = messageWithOneofs(List(oneofField("error", 1, 0)), List("result"))
          .addNestedType(DescriptorProto.newBuilder.setName("Result").addField(stringField()))
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
          .addMessageType(message)
          .build()
        assertTrue(Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))).isFailure)
      },
    ),
    suite("large messages")(
      test("split the codec's methods, so the JVM can JIT compile them") {
        val output = generateCodecFiles((1 to 300).map(index => stringField(name = s"field$index", number = index)).toList)("test/MessageMessageCodec.scala")
        val size = output.substring(output.indexOf("def serializedSize("), output.indexOf("private def serializedSize0("))
        assertTrue(trimmed(size).contains("=\nserializedSize0(value, sizes) +\nserializedSize1(value, sizes) +\n")) &&
        assertTrue(trimmed(output).contains("\nwriteTo0(output, value, sizes)\nwriteTo1(output, value, sizes)\n")) &&
        // a part of only scalar fields does not use the sizes
        assertTrue(
          output.contains("private def writeTo1(output: _root_.com.google.protobuf.CodedOutputStream, value: _root_.test.Message, @_root_.scala.annotation.unused sizes: _root_.zga.common.Sizes)"),
        ) &&
        assertTrue(output.contains("done = tag == 0 || !(fields.read0(tag) || fields.read1(tag) || ")) &&
        assertTrue(output.contains("private final class Fields(reader: _root_.zga.common.ProtoReader, value: _root_.test.Message) {")) &&
        assertTrue(
          trimmed(output).contains("def read0(tag: _root_.scala.Int): _root_.scala.Boolean = tag match {\ncase 10 =>\n_field1 = input.readStringRequireUtf8()\ntrue\n"),
        )
      },
      test("a message with fewer fields keeps each of the codec's methods whole") {
        val fields = (1 to 20).map(index => stringField(name = s"field$index", number = index)).toList
        val output = generate(fields)
        assertTrue(!output.contains("serializedSize0"), !output.contains("writeTo0"), !output.contains("class Fields")) &&
        assertTrue(
          output.contains("given messageCodec: _root_.zga.common.MessageCodec[_root_.test.Message] = new _root_.zga.common.MessageCodec[_root_.test.Message] {"),
        ) &&
        assertTrue(generateCodecFiles(fields).isEmpty)
      },
      test("a split codec is defined in a file of its own") {
        val fields = (1 to 300).map(index => stringField(name = s"field$index", number = index)).toList
        val output = generate(fields)
        val codecFiles = generateCodecFiles(fields)
        val codec = codecFiles("test/MessageMessageCodec.scala")
        assertTrue(codecFiles.keySet == Set("test/MessageMessageCodec.scala")) &&
        assertTrue(
          trimmed(output).contains("\ngiven messageCodec: _root_.zga.common.MessageCodec[_root_.test.Message] = _root_.test.MessageMessageCodec.messageCodec\n"),
        ) &&
        assertTrue(!output.contains("serializedSize0"), !output.contains("new _root_.zga.common.MessageCodec")) &&
        assertTrue(codec.startsWith("\npackage test\n\nprivate[test] object MessageMessageCodec {\n")) &&
        assertTrue(
          trimmed(codec).contains("\ngiven messageCodec: _root_.zga.common.MessageCodec[_root_.test.Message] = new _root_.zga.common.MessageCodec[_root_.test.Message] {"),
        ) &&
        assertTrue(codec.contains("private def serializedSize0("))
      },
      test("a split codec of a nested message is named after the messages it is nested in") {
        val inner = DescriptorProto.newBuilder
          .setName("Inner")
          .addAllField((1 to 300).map(index => stringField(name = s"field$index", number = index)).asJava)
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .addMessageType(DescriptorProto.newBuilder.setName("Outer").addNestedType(inner))
          .build()
        val file = Descriptors.FileDescriptor.buildFrom(proto, Array.empty)
        assertTrue(Generator.codecFiles(file).map(_._1) == List("test/OuterInnerMessageCodec.scala")) &&
        assertTrue(Generator.printFile(file).contains("= _root_.test.OuterInnerMessageCodec.messageCodec\n"))
      },
      test("a split codec in the default package is public") {
        val fields = (1 to 300).map(index => stringField(name = s"field$index", number = index)).toList
        val codec = generateCodecFiles(fields, pkg = "", javaPackage = None)("MessageMessageCodec.scala")
        assertTrue(codec.startsWith("\n\nobject MessageMessageCodec {\n")) &&
        assertTrue(generate(fields, pkg = "", javaPackage = None).contains("= _root_.MessageMessageCodec.messageCodec\n"))
      },
      test("chunks oneof parameters as well as ordinary fields") {
        val fields = (0 until 255).map(index => oneofField(s"value$index", index + 1, index)).toList
        val output = generateOneof(fields, (0 until 255).map(index => s"choice$index").toList)
        val outer = output.substring(output.indexOf("case class Message("), output.indexOf("object Message {"))
        val part1 = output.substring(output.indexOf("case class Part1("), output.indexOf("case class Part2("))
        val part2 = output.substring(output.indexOf("case class Part2("), output.indexOf("given messageCodec"))
        assertTrue(outer.contains("part1:"), outer.contains("part2:"), !outer.contains("choice0:")) &&
        assertTrue(part1.contains("choice253:"), !part1.contains("choice254:"), part2.contains("choice254:")) &&
        assertTrue(output.contains("sealed trait Choice254 extends _root_.zga.common.Oneof\n"))
      },
      test("preserves mixed member order and slot widths at a group boundary") {
        val doubles = (1 to 126).map(index => field(name = s"field$index", number = index, fieldType = FieldDescriptorProto.Type.TYPE_DOUBLE)).toList
        val fields = doubles ++ List(oneofField("selected", 127, 0), stringField(name = "last", number = 128)) ++
          List(field(name = "overflow", number = 129, fieldType = FieldDescriptorProto.Type.TYPE_DOUBLE))
        val output = generateOneof(fields, List("choice"))
        val part1 = output.substring(output.indexOf("case class Part1("), output.indexOf("case class Part2("))
        val part2 = output.substring(output.indexOf("case class Part2("), output.indexOf("given messageCodec"))
        assertTrue(part1.indexOf("field126:") < part1.indexOf("choice:")) &&
        assertTrue(part1.indexOf("choice:") < part1.indexOf("last:")) &&
        assertTrue(!part1.contains("overflow:"), part2.contains("overflow:"))
      },
      test("a required member removes its group's default") {
        val fields = (1 to 255).map(index => stringField(name = s"field$index", number = index, options = Option.when(index == 1)(requiredOptions))).toList
        val output = generate(fields)
        assertTrue(output.contains("  part1: _root_.test.Message.Part1,")) &&
        assertTrue(output.contains("  part2: _root_.test.Message.Part2 = _root_.test.Message.Part2(),"))
      },
      test("group names avoid fields, oneofs and nested types") {
        val fields = (1 to 254).map(index => stringField(name = s"field$index", number = index)).toList ++
          List(stringField(name = "part1Group", number = 255), oneofField("selected", 256, 0))
        val message = messageWithOneofs(fields, List("part2"))
          .addNestedType(DescriptorProto.newBuilder.setName("Part1"))
        val output = generateTopLevel(message)
        assertTrue(output.contains("  part1GroupGroup: _root_.test.Message.Part1Group")) &&
        assertTrue(output.contains("  part2Group: _root_.test.Message.Part2Group"))
      },
      test("leaves a message at the parameter limit flat") {
        val fields = (1 to 254).map(index => stringField(name = s"field$index", number = index)).toList
        val output = generate(fields)
        assertTrue(output.contains("""  field1: _root_.scala.Predef.String = "",""")) &&
        assertTrue(output.contains("""  field254: _root_.scala.Predef.String = "",""")) &&
        assertTrue(!output.contains("case class Part"))
      },
      test("chunks a message over the parameter limit and exports each chunk") {
        val fields = (1 to 300).map(index => stringField(name = s"field$index", number = index)).toList
        val output = generate(fields)
        val part1 = output.substring(output.indexOf("case class Part1("), output.indexOf("case class Part2("))
        val part2 = output.substring(output.indexOf("case class Part2("), output.indexOf("given messageCodec"))
        assertTrue(output.contains("  part1: _root_.test.Message.Part1 = _root_.test.Message.Part1(),")) &&
        assertTrue(output.contains("  part2: _root_.test.Message.Part2 = _root_.test.Message.Part2(),")) &&
        // reads look flat, because the message exports the chunks
        assertTrue(output.contains("  export part1.*")) &&
        assertTrue(output.contains("  export part2.*")) &&
        // the chunks hold consecutive fields, up to the limit
        assertTrue(part1.contains("""  field254: _root_.scala.Predef.String = "",""")) &&
        assertTrue(!part1.contains("field255")) &&
        assertTrue(part2.contains("""  field255: _root_.scala.Predef.String = "",""")) &&
        assertTrue(part2.contains("""  field300: _root_.scala.Predef.String = "",""")) &&
        assertTrue(!part2.contains("field254"))
      },
      test("counts a long or double field as two of the 254 slots") {
        val fields = (1 to 200)
          .map(index => field(name = s"field$index", number = index, fieldType = FieldDescriptorProto.Type.TYPE_DOUBLE))
          .toList
        val output = generate(fields)
        val part1 = output.substring(output.indexOf("case class Part1("), output.indexOf("case class Part2("))
        val part2 = output.substring(output.indexOf("case class Part2("), output.indexOf("given messageCodec"))
        assertTrue(part1.contains("  field127: _root_.scala.Double = 0.0d,")) &&
        assertTrue(!part1.contains("field128")) &&
        assertTrue(part2.contains("  field128: _root_.scala.Double = 0.0d,"))
      },
    ),
    suite("validation")(
      test("rejects a group field") {
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto2")
          .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
          .addMessageType(
            DescriptorProto.newBuilder
              .setName("Message")
              .addNestedType(DescriptorProto.newBuilder.setName("MyGroup").addField(stringField()))
              .addField(
                FieldDescriptorProto.newBuilder
                  .setName("mygroup")
                  .setNumber(1)
                  .setLabel(Label.LABEL_OPTIONAL)
                  .setType(FieldDescriptorProto.Type.TYPE_GROUP)
                  .setTypeName(".test.Message.MyGroup"),
              ),
          )
          .build()
        assertTrue(Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))).isFailure)
      },
      test("rejects a proto2 required field") {
        assertTrue(Try(generate(List(stringField(label = Label.LABEL_REQUIRED)), syntax = "proto2")).isFailure)
      },
      test("rejects an enum value that collides with a generated member") {
        val names = List("values", "valueMap", "numbers", "valueOf", "fromOrdinal", "toString", "wait")
        assertTrue(names.forall { name =>
          Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile(name), Array.empty))).isFailure
        })
      },
      test("accepts an enum value named like a member that does not collide") {
        val names = List("ordinal", "equals", "getClass")
        assertTrue(names.forall { name =>
          Try(Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile(name), Array.empty))).isSuccess
        })
      },
      test("escapes keyword type names") {
        val proto = FileDescriptorProto.newBuilder
          .setName("test.proto")
          .setPackage("test")
          .setSyntax("proto3")
          .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
          .addMessageType(DescriptorProto.newBuilder.setName("type").addField(stringField()))
          .build()
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
        assertTrue(output.contains("case class `type`(")) &&
        assertTrue(output.contains("_root_.test.`type`"))
      },
    ),
  )

  private def generate(
    fields: List[FieldDescriptorProto],
    syntax: String = "proto3",
    pkg: String = "test",
    javaPackage: Option[String] = Some("test"),
  ) = {
    Generator.printFile(messageFile(fields, syntax, pkg, javaPackage))
  }

  private def generateCodecFiles(
    fields: List[FieldDescriptorProto],
    pkg: String = "test",
    javaPackage: Option[String] = Some("test"),
  ) = {
    Generator.codecFiles(messageFile(fields, "proto3", pkg, javaPackage)).toMap
  }

  private def messageFile(fields: List[FieldDescriptorProto], syntax: String, pkg: String, javaPackage: Option[String]) = {
    val builder = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage(pkg)
      .setSyntax(syntax)
      .addMessageType(DescriptorProto.newBuilder.setName("Message").addAllField(fields.asJava))
    javaPackage.foreach { value =>
      val _ = builder.setOptions(FileOptions.newBuilder.setJavaPackage(value))
    }
    Descriptors.FileDescriptor.buildFrom(builder.build(), Array.empty)
  }

  // generated code is formatted afterwards, so its indentation does not matter
  private def trimmed(code: String) = code.linesIterator.map(_.trim).mkString("\n")

  private def scalaNames(names: String*) =
    fieldDescriptors(names.toList.zipWithIndex.map { case (name, index) => stringField(name, number = index + 1) })
      .map(_.scalaName)

  private def generateTopLevel(message: DescriptorProto.Builder) = {
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .addMessageType(message)
      .build()
    Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
  }

  private def generateOneof(fields: List[FieldDescriptorProto], oneofs: List[String]) = {
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
      .addMessageType(messageWithOneofs(fields, oneofs))
      .build()
    Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
  }
}
