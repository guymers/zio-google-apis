package zga.codegen

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.Edition
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FeatureSet
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
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
      test("a required field behavior removes the default") {
        val output = generate(List(stringField(options = Some(requiredOptions))))
        assertTrue(output.contains("  name: _root_.scala.Predef.String,"))
      },
      test("a proto2 required field removes the default") {
        val output = generate(List(stringField(label = Label.LABEL_REQUIRED)), syntax = "proto2")
        assertTrue(output.contains("  name: _root_.scala.Option[_root_.scala.Predef.String],"))
      },
      test("a field without a required marker keeps the default") {
        val output = generate(List(stringField()))
        assertTrue(output.contains("""  name: _root_.scala.Predef.String = "","""))
      },
      test("a required message field removes the None default") {
        val output = generate(List(messageField(Some(requiredOptions))))
        assertTrue(output.contains("  nested: _root_.scala.Option[_root_.test.Message],"))
      },
      test("a required repeated field keeps the empty default") {
        val output = generate(List(stringField(options = Some(requiredOptions), label = Label.LABEL_REPEATED)))
        assertTrue(output.contains("  name: _root_.zio.Chunk[_root_.scala.Predef.String] = _root_.zio.Chunk.empty,"))
      },
    ),
    suite("editions")(
      test("implicit presence keeps the default") {
        val field = FieldDescriptorProto.newBuilder
          .setName("name")
          .setNumber(1)
          .setLabel(Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_STRING)
          .setOptions(
            FieldOptions.newBuilder.setFeatures(FeatureSet.newBuilder.setFieldPresence(FeatureSet.FieldPresence.IMPLICIT)),
          )
        val output = generateEditions(field.build())
        assertTrue(output.contains("""  name: _root_.scala.Predef.String = "","""))
      },
      test("legacy required removes the default") {
        val field = FieldDescriptorProto.newBuilder
          .setName("name")
          .setNumber(1)
          .setLabel(Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_STRING)
          .setOptions(
            FieldOptions.newBuilder
              .setFeatures(FeatureSet.newBuilder.setFieldPresence(FeatureSet.FieldPresence.LEGACY_REQUIRED)),
          )
        val output = generateEditions(field.build())
        assertTrue(output.contains("  name: _root_.scala.Option[_root_.scala.Predef.String],"))
      },
      test("a delimited field is a group field") {
        val field = FieldDescriptorProto.newBuilder
          .setName("other")
          .setNumber(2)
          .setLabel(Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
          .setTypeName(".test.Message")
          .setOptions(
            FieldOptions.newBuilder
              .setFeatures(FeatureSet.newBuilder.setMessageEncoding(FeatureSet.MessageEncoding.DELIMITED)),
          )
        assertTrue(Try(generateEditions(field.build())).isFailure)
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
      test("escapes a Scala 2 keyword") {
        val output = generate(List(stringField(name = "val")))
        assertTrue(output.contains("""  `val`: _root_.scala.Predef.String = "","""))
      },
      test("escapes Scala 3 hard keywords") {
        val output = generate(List(stringField(name = "export"), stringField(name = "match", number = 2)))
        assertTrue(output.contains("""  `export`: _root_.scala.Predef.String = "",""")) &&
        assertTrue(output.contains("""  `match`: _root_.scala.Predef.String = "","""))
      },
      test("leaves a soft keyword unescaped") {
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
      test("keeps the proto name when the JSON name would not be a valid identifier") {
        assertTrue(scalaNames("_1foo") == List("_1foo"))
      },
      test("uses the json_name option") {
        val fields = List(
          FieldDescriptorProto.newBuilder(stringField(name = "resource_type", number = 1)).setJsonName("customName").build(),
          FieldDescriptorProto.newBuilder(stringField(name = "nat_ip", number = 2)).setJsonName("natIP").build(),
        )
        assertTrue(fieldDescriptors(fields).map(_.scalaName) == List("customName", "natIP"))
      },
      test("lower cases an all upper case json_name entirely") {
        val field = FieldDescriptorProto.newBuilder(stringField(name = "ram")).setJsonName("RAM").build()
        assertTrue(fieldDescriptors(List(field)).map(_.scalaName) == List("ram"))
      },
      test("keeps the proto name when the json_name option is not a valid identifier") {
        assertTrue(List("1custom", "custom name", "").forall { jsonName =>
          val field = FieldDescriptorProto.newBuilder(stringField(name = "resource_type")).setJsonName(jsonName).build()
          fieldDescriptors(List(field)).map(_.scalaName) == List("resource_type")
        })
      },
      test("rejects fields that convert to the same Scala name") {
        val generated = Try(generate(List(stringField(name = "foo_bar"), stringField(name = "fooBar", number = 2))))
        val message = generated.failed.toOption.map(_.getMessage)
        assertTrue(generated.isFailure) &&
        assertTrue(message.exists(_.contains("all map to the Scala name 'fooBar'"))) &&
        assertTrue(message.exists(m => m.contains("'fooBar'") && m.contains("'foo_bar'")))
      },
      test("rejects a field that converts to a member of the case class") {
        val names = List("wait", "to_string", "hash_code", "product_arity", "notify_all")
        assertTrue(names.zipWithIndex.forall { case (name, index) =>
          Try(generate(List(stringField(name = name, number = index + 1)))).isFailure
        })
      },
      test("accepts a field that overloads a member of the case class") {
        val names = List("copy", "product_element", "can_equal", "product_element_name")
        assertTrue(names.zipWithIndex.forall { case (name, index) =>
          Try(generate(List(stringField(name = name, number = index + 1)))).isSuccess
        })
      },
    ),
    suite("descriptor literal")(
      test("chunks a descriptor too large for one JVM string constant") {
        val bytes = Array.fill(20000)('A'.toByte)
        val chunks = "\"([A-Za-z0-9+/=]+)\"".r
          .findAllMatchIn(Generator.descriptorLiteral(bytes))
          .map(_.group(1))
          .toList
        assertTrue(chunks.length > 1) &&
        assertTrue(chunks.forall(_.length <= 8192)) &&
        // `+` of literals is constant folded back into one string constant
        assertTrue(!Generator.descriptorLiteral(bytes).contains("+\n")) &&
        assertTrue(Generator.descriptorLiteral(bytes).startsWith("_root_.scala.Array(")) &&
        assertTrue(Generator.descriptorLiteral(bytes).endsWith(").mkString")) &&
        assertTrue(java.util.Base64.getDecoder.decode(chunks.mkString).sameElements(bytes))
      },
      test("emits one literal for a small descriptor") {
        val bytes = Array[Byte](1, 2, 3, 4, 5)
        val expected = "\"" + java.util.Base64.getEncoder.encodeToString(bytes) + "\""
        assertTrue(Generator.descriptorLiteral(bytes) == expected)
      },
    ),
    suite("descriptor object name")(
      test("is named after the file") {
        val output = generateTopLevel(DescriptorProto.newBuilder.setName("Test").addField(stringField()))
        assertTrue(output.contains("object TestDescriptors {"))
      },
      test("avoids a message with the same name") {
        val output = generateTopLevel(DescriptorProto.newBuilder.setName("TestDescriptors").addField(stringField()))
        assertTrue(output.contains("object TestOuterClassDescriptors {")) &&
        assertTrue(output.contains("_root_.test.TestOuterClassDescriptors.javaDescriptor"))
      },
    ),
    suite("enums")(
      test("qualifies Int with _root_") {
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile("VALUE"), Array.empty))
        assertTrue(output.contains("def fromValue(v: _root_.scala.Int): "))
      },
      test("an open enum keeps unrecognized values") {
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile("VALUE"), Array.empty))
        assertTrue(output.contains("sealed trait Kind derives _root_.scala.CanEqual {")) &&
        assertTrue(output.contains("enum Recognized(val value: _root_.scala.Int) extends _root_.test.Kind {")) &&
        assertTrue(output.contains("case VALUE extends Recognized(0)")) &&
        assertTrue(output.contains("export Recognized.*")) &&
        assertTrue(output.contains("case class Unrecognized private[Kind] (value: _root_.scala.Int) extends _root_.test.Kind")) &&
        assertTrue(output.contains("def fromValue(v: _root_.scala.Int): _root_.test.Kind = valueMap.getOrElse(v, Unrecognized(v))"))
      },
      test("a closed enum is a plain enum") {
        val output = Generator.printFile(Descriptors.FileDescriptor.buildFrom(enumFile("VALUE", "proto2"), Array.empty))
        assertTrue(output.contains("enum Kind(val value: _root_.scala.Int) derives _root_.scala.CanEqual {")) &&
        assertTrue(output.contains("case VALUE extends Kind(0)")) &&
        assertTrue(!output.contains("Unrecognized"))
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
        assertTrue(output.contains("sealed trait Result extends _root_.zga.common.Oneof")) &&
        assertTrue(output.contains("case class Error(value: _root_.scala.Predef.String) extends _root_.test.Message.Result")) &&
        assertTrue(output.contains("case class Response(value: _root_.scala.Predef.String) extends _root_.test.Message.Result")) &&
        assertTrue(output.contains("given oneofCodec: _root_.zga.common.OneofCodec[_root_.test.Message.Result]"))
      },
      test("derives the codec from a codec per case") {
        val output = generateOneof(List(oneofField("error", 1, 0), oneofField("response", 2, 0)), List("result"))
        assertTrue(output.contains("_root_.zga.common.OneofCodec.derived[_root_.test.Message.Result](")) &&
        assertTrue(output.contains("_root_.zga.common.OneofCaseCodec.derived[_root_.test.Message.Result.Error](1)")) &&
        assertTrue(output.contains("_root_.zga.common.OneofCaseCodec.derived[_root_.test.Message.Result.Response](2)"))
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
      test("rejects an enum value that collides with a generated member") {
        val names = List("values", "fieldCodec", "valueOf", "fromOrdinal", "toString", "wait")
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
    val builder = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage(pkg)
      .setSyntax(syntax)
      .addMessageType(DescriptorProto.newBuilder.setName("Message").addAllField(fields.asJava))
    javaPackage.foreach { value =>
      val _ = builder.setOptions(FileOptions.newBuilder.setJavaPackage(value))
    }
    Generator.printFile(Descriptors.FileDescriptor.buildFrom(builder.build(), Array.empty))
  }

  private def scalaNames(names: String*) =
    fieldDescriptors(names.toList.zipWithIndex.map { case (name, index) => stringField(name = name, number = index + 1) })
      .map(_.scalaName)

  private def generateEditions(field: FieldDescriptorProto) = {
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setEdition(Edition.EDITION_2023)
      .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
      .addMessageType(DescriptorProto.newBuilder.setName("Message").addField(field))
      .build()
    Generator.printFile(Descriptors.FileDescriptor.buildFrom(proto, Array.empty))
  }

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
