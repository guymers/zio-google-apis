package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import zga.google.api.Endpoint
import zga.google.api.LabelDescriptor
import zga.google.api.MetricDescriptor
import zga.google.protobuf.BytesValue
import zga.google.protobuf.Duration
import zga.google.protobuf.FieldMask
import zga.google.protobuf.FloatValue
import zga.google.protobuf.NullValue
import zga.google.protobuf.StringValue
import zga.google.protobuf.Struct
import zga.google.protobuf.Value
import zga.google.rpc.ResourceInfo
import zio.Chunk
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.util.Locale
import scala.util.Try

object MessageCodecTest extends ZIOSpecDefault {

  override val spec = suite("MessageCodec")(
    test("scalar") {
      val value = Duration(seconds = 42L, nanos = 1)
      assertTrue(roundTrip(value) == value)
    },
    test("float") {
      val value = FloatValue(value = 1.5f)
      assertTrue(roundTrip(value) == value)
    },
    test("bytes") {
      val value = BytesValue(value = ByteString.copyFromUtf8("value"))
      assertTrue(roundTrip(value) == value)
    },
    test("single field message") {
      val value = StringValue(value = "value")
      assertTrue(roundTrip(value) == value)
    },
    test("default values") {
      val value = StringValue()
      assertTrue(roundTrip(value) == value)
    },
    test("multiple scalar fields") {
      val value = Endpoint(name = "name", target = "target", allowCors = true)
      assertTrue(roundTrip(value) == value)
    },
    test("fields whose proto names contain an underscore") {
      val value = ResourceInfo(
        resourceType = "type",
        resourceName = "name",
        owner = "owner",
        description = "description",
      )
      assertTrue(roundTrip(value) == value)
    },
    test("enums, presence and nested messages") {
      val value = MetricDescriptor(
        name = "name",
        labels = Chunk(LabelDescriptor(key = "key", valueType = LabelDescriptor.ValueType.STRING, description = "description")),
        metricKind = MetricDescriptor.MetricKind.GAUGE,
        valueType = MetricDescriptor.ValueType.INT64,
        metadata = Some(MetricDescriptor.MetricDescriptorMetadata(samplePeriod = Some(Duration(seconds = 1L, nanos = 2)))),
      )
      assertTrue(roundTrip(value) == value)
    },
    test("unrecognized enum values") {
      val value = MetricDescriptor(metricKind = MetricDescriptor.MetricKind.fromValue(99))
      assertTrue(roundTrip(value) == value)
    },
    test("repeated fields") {
      val value = FieldMask(paths = Chunk("a", "b.c", "d"))
      assertTrue(roundTrip(value) == value)
    },
    test("map fields and oneofs") {
      val value = Struct(
        fields = Map(
          "string" -> Value(kind = Some(Value.Kind.StringValue("value"))),
          "number" -> Value(kind = Some(Value.Kind.NumberValue(1.5d))),
          "null" -> Value(kind = Some(Value.Kind.NullValue(NullValue.NULL_VALUE))),
        ),
      )
      assertTrue(roundTrip(value) == value)
    },
    test("a oneof with a message member") {
      val value = Value(kind = Some(Value.Kind.StructValue(Struct(fields = Map("key" -> Value())))))
      assertTrue(roundTrip(value) == value)
    },
    test("a oneof with no member set") {
      val value = Value(kind = None)
      assertTrue(roundTrip(value) == value)
    },
    test("a oneof case whose field number the oneof does not contain fails instead of encoding an unrelated field") {
      val oneof = summon[MessageCodec[Value]].descriptor.findFieldByName("string_value").getContainingOneof
      val bogus = OneofCodec.Case.derived[Value.Kind.StringValue](999)
      assertTrue(Try(bogus.encode(oneof, Value.Kind.StringValue("value"))).isFailure)
    },
    test("grpc marshaller") {
      val value = Duration(seconds = 42L, nanos = 1)
      val marshaller = MessageCodec.marshaller[Duration]
      assertTrue(marshaller.parse(marshaller.stream(value)) == value)
    },
    test("a codec whose parameter matches no descriptor field fails instead of dropping fields") {
      val codec = MessageCodec.derived[NotDuration](summon[MessageCodec[Duration]].descriptor)
      assertTrue(Try(codec.toByteArray(NotDuration(seconds = 1L))).isFailure)
    },
    test("lower cases an all upper case field name entirely") {
      val codec = MessageCodec.derived[Ram](ramDescriptor)
      assertTrue(roundTrip(Ram(ram = "value"))(using codec) == Ram(ram = "value"))
    },
    test("converts a nested value against the field's descriptor when the codec carries another one") {
      // a structurally equal but distinct descriptor, as a hand-written codec could carry
      val otherDescriptor = Descriptors.FileDescriptor
        .buildFrom(zga.google.protobuf.SourceContextProtoDescriptors.javaDescriptor.toProto, Array.empty)
        .findMessageTypeByName("SourceContext")
      val codec = MessageCodec.derived[zga.google.protobuf.SourceContext](otherDescriptor)
      val sourceContextField = summon[MessageCodec[zga.google.protobuf.Api]].descriptor.findFieldByName("source_context")
      val value = zga.google.protobuf.SourceContext(fileName = "file.proto")

      val message = codec.toProto(sourceContextField, value).asInstanceOf[DynamicMessage]

      assertTrue(sourceContextField.getMessageType != otherDescriptor) &&
      assertTrue(message.getDescriptorForType == sourceContextField.getMessageType) &&
      assertTrue(codec.fromProto(sourceContextField, message, true) == value)
    },
    suite("serializes a map in a key order that does not depend on the map's own order")(
      test("int32 keys") {
        val codec = summon[FieldCodec[Map[Int, String]]]
        assertTrue(stableBytes("ints", codec, Map(-1 -> "a", 2 -> "b"), Map(2 -> "b", -1 -> "a")))
      },
      test("uint32 keys") {
        val codec = summon[FieldCodec[Map[Int, String]]]
        assertTrue(stableBytes("uints", codec, Map(-1 -> "a", 2 -> "b"), Map(2 -> "b", -1 -> "a")))
      },
      test("int64 keys") {
        val codec = summon[FieldCodec[Map[Long, String]]]
        assertTrue(stableBytes("longs", codec, Map(-1L -> "a", 2L -> "b"), Map(2L -> "b", -1L -> "a")))
      },
      test("uint64 keys") {
        val codec = summon[FieldCodec[Map[Long, String]]]
        assertTrue(stableBytes("ulongs", codec, Map(-1L -> "a", 2L -> "b"), Map(2L -> "b", -1L -> "a")))
      },
      test("bool keys") {
        val codec = summon[FieldCodec[Map[Boolean, String]]]
        assertTrue(stableBytes("bools", codec, Map(true -> "a", false -> "b"), Map(false -> "b", true -> "a")))
      },
      test("string keys") {
        val codec = summon[FieldCodec[Map[String, String]]]
        assertTrue(stableBytes("strings", codec, Map("b" -> "b", "a" -> "a"), Map("a" -> "a", "b" -> "b")))
      },
    ),
  )

  // a case class that matches none of Duration's fields
  private case class NotDuration(seconds: Long = 0L, absent: String = "")

  // the case class parameter is the all upper case `RAM` field, lower cased
  private case class Ram(ram: String = "")

  private lazy val ramDescriptor = {
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .addMessageType(
        DescriptorProto.newBuilder.setName("Ram").addField(
          FieldDescriptorProto.newBuilder
            .setName("RAM")
            .setNumber(1)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(FieldDescriptorProto.Type.TYPE_STRING),
        ),
      )
      .build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty).findMessageTypeByName("Ram")
  }

  // one map field per valid key type, so that the serialization order of each
  // is exercised
  private lazy val mapsDescriptor = {
    val keyTypes = List(
      "IntsEntry" -> FieldDescriptorProto.Type.TYPE_INT32,
      "UintsEntry" -> FieldDescriptorProto.Type.TYPE_UINT32,
      "LongsEntry" -> FieldDescriptorProto.Type.TYPE_INT64,
      "UlongsEntry" -> FieldDescriptorProto.Type.TYPE_UINT64,
      "BoolsEntry" -> FieldDescriptorProto.Type.TYPE_BOOL,
      "StringsEntry" -> FieldDescriptorProto.Type.TYPE_STRING,
    )

    def entry(name: String, keyType: FieldDescriptorProto.Type) = {
      def field(fieldName: String, number: Int, fieldType: FieldDescriptorProto.Type) =
        FieldDescriptorProto.newBuilder
          .setName(fieldName)
          .setNumber(number)
          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(fieldType)

      DescriptorProto.newBuilder
        .setName(name)
        .setOptions(MessageOptions.newBuilder.setMapEntry(true))
        .addField(field("key", 1, keyType))
        .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_STRING))
        .build()
    }

    val message = DescriptorProto.newBuilder.setName("Maps")
    keyTypes.zipWithIndex.foreach { case ((entryName, keyType), index) =>
      val fieldName = entryName.stripSuffix("Entry").toLowerCase(Locale.ROOT)
      val _ = message
        .addNestedType(entry(entryName, keyType))
        .addField(
          FieldDescriptorProto.newBuilder
            .setName(fieldName)
            .setNumber(index + 1)
            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(s".test.Maps.$entryName"),
        )
    }

    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .addMessageType(message)
      .build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty).findMessageTypeByName("Maps")
  }

  private def serializeMap[K](fieldName: String, codec: FieldCodec[Map[K, String]], value: Map[K, String]): Array[Byte] = {
    val field = mapsDescriptor.findFieldByName(fieldName)
    val builder = DynamicMessage.newBuilder(mapsDescriptor)
    val _ = builder.setField(field, codec.toProto(field, value))
    builder.build().toByteArray
  }

  private def stableBytes[K](
    fieldName: String,
    codec: FieldCodec[Map[K, String]],
    forward: Map[K, String],
    reverse: Map[K, String],
  ): Boolean = serializeMap(fieldName, codec, forward).sameElements(serializeMap(fieldName, codec, reverse))

  private def roundTrip[A](value: A)(using messageCodec: MessageCodec[A]) = {
    messageCodec.parseFrom(messageCodec.toByteArray(value))
  }
}
