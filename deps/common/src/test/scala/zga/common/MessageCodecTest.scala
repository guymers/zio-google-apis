package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.KnownLength
import zga.google.api.Endpoint
import zga.google.api.LabelDescriptor
import zga.google.api.MetricDescriptor
import zga.google.protobuf.BytesValue
import zga.google.protobuf.Duration
import zga.google.protobuf.FieldMask
import zga.google.protobuf.FloatValue
import zga.google.protobuf.ListValue
import zga.google.protobuf.NullValue
import zga.google.protobuf.StringValue
import zga.google.protobuf.Struct
import zga.google.protobuf.Value
import zga.google.rpc.ResourceInfo
import zio.Chunk
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.util.Locale
import scala.compiletime.testing.typeCheckErrors
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
      val codec = OneofCodec.derived[Value.Kind](
        OneofCodec.Case.derived[Value.Kind.NullValue](1),
        OneofCodec.Case.derived[Value.Kind.NumberValue](2),
        OneofCodec.Case.derived[Value.Kind.StringValue](999),
        OneofCodec.Case.derived[Value.Kind.BoolValue](4),
        OneofCodec.Case.derived[Value.Kind.StructValue](5),
        OneofCodec.Case.derived[Value.Kind.ListValue](6),
      )
      assertTrue(Try(codec.bind(kindOneof)).failed.toOption.exists(_.getMessage.contains("999")))
    },
    test("oneof cases given out of declaration order still encode each member under its own field") {
      val codec = OneofCodec.derived[Value.Kind](
        OneofCodec.Case.derived[Value.Kind.ListValue](6),
        OneofCodec.Case.derived[Value.Kind.StructValue](5),
        OneofCodec.Case.derived[Value.Kind.BoolValue](4),
        OneofCodec.Case.derived[Value.Kind.StringValue](3),
        OneofCodec.Case.derived[Value.Kind.NumberValue](2),
        OneofCodec.Case.derived[Value.Kind.NullValue](1),
      ).bind(kindOneof)
      val kinds = List(
        Value.Kind.NullValue(NullValue.NULL_VALUE),
        Value.Kind.NumberValue(1.5d),
        Value.Kind.StringValue("value"),
        Value.Kind.BoolValue(true),
        Value.Kind.StructValue(Struct()),
        Value.Kind.ListValue(ListValue()),
      )
      assertTrue(kinds.forall { kind =>
        val builder = DynamicMessage.newBuilder(kindOneof.getContainingType)
        codec.write(Some(kind), builder)
        val message = builder.build()
        message.toByteArray.sameElements(MessageCodec[Value].toByteArray(Value(kind = Some(kind)))) &&
        codec.read(message).contains(kind)
      })
    },
    test("a oneof missing a case for one of its members fails") {
      val result = Try(
        OneofCodec.derived[Value.Kind](
          OneofCodec.Case.derived[Value.Kind.NullValue](1),
          OneofCodec.Case.derived[Value.Kind.NumberValue](2),
          OneofCodec.Case.derived[Value.Kind.StringValue](3),
          OneofCodec.Case.derived[Value.Kind.BoolValue](4),
          OneofCodec.Case.derived[Value.Kind.StructValue](5),
        ),
      )
      assertTrue(result.failed.toOption.exists(_.getMessage.contains("5 cases for 6 members")))
    },
    test("a oneof with two cases for the same member fails") {
      val result = Try(
        OneofCodec.derived[Value.Kind](
          OneofCodec.Case.derived[Value.Kind.NullValue](1),
          OneofCodec.Case.derived[Value.Kind.NumberValue](2),
          OneofCodec.Case.derived[Value.Kind.StringValue](3),
          OneofCodec.Case.derived[Value.Kind.StringValue](4),
          OneofCodec.Case.derived[Value.Kind.StructValue](5),
          OneofCodec.Case.derived[Value.Kind.ListValue](6),
        ),
      )
      assertTrue(result.failed.toOption.exists(_.getMessage.contains("more than one case")))
    },
    test("grpc marshaller") {
      val value = Duration(seconds = 42L, nanos = 1)
      val marshaller = MessageCodec.marshaller[Duration]
      val stream = marshaller.stream(value)
      assertTrue(stream.isInstanceOf[KnownLength]) &&
      assertTrue(marshaller.parse(stream) == value)
    },
    test("safely parses a ByteString") {
      val value = Duration(seconds = 42L, nanos = 1)
      val bytes = ByteString.copyFrom(MessageCodec[Duration].toByteArray(value))
      val truncated = ByteString.copyFrom(Array[Byte](0x0a, 0x05))
      assertTrue(MessageCodec[Duration].safeParseFrom(bytes) == Right(value)) &&
      assertTrue(MessageCodec[Duration].safeParseFrom(truncated).isLeft)
    },
    test("a codec missing a parameter for a descriptor field fails instead of dropping it") {
      val codec = MessageCodec.derived[PartialDuration](MessageCodec[Duration].descriptor)
      val result = Try(codec.toByteArray(PartialDuration(seconds = 1L)))
      assertTrue(result.failed.toOption.exists(_.getMessage.contains("no case class parameter: nanos")))
    },
    test("a codec reading a field through more than one parameter fails") {
      val codec = MessageCodec.derived[TwiceSeconds](MessageCodec[Duration].descriptor)
      val result = Try(codec.toByteArray(TwiceSeconds(SecondsPart(1L), seconds = 1L, nanos = 0)))
      assertTrue(result.failed.toOption.exists(_.getMessage.contains("more than one case class parameter: seconds")))
    },
    test("a parameter with no codec reports why none was found") {
      val errors = typeCheckErrors("MessageCodec.derived[NoCodec](MessageCodec[Duration].descriptor)")
      assertTrue(errors.exists { error =>
        error.message.contains("'seconds'") && error.message.contains("FieldCodec[Thread]")
      })
    },
    test("a codec whose parameter matches no descriptor field fails instead of dropping fields") {
      val codec = MessageCodec.derived[NotDuration](summon[MessageCodec[Duration]].descriptor)
      assertTrue(Try(codec.toByteArray(NotDuration(seconds = 1L))).isFailure)
    },
    test("an unmatched message parameter is rejected instead of being flattened as a group") {
      val codec = MessageCodec.derived[MisspelledDuration](MessageCodec[Duration].descriptor)
      val encoded = Try(codec.toByteArray(MisspelledDuration(Duration(seconds = 42L))))
      val decoded = Try(codec.parseFrom(MessageCodec[Duration].toByteArray(Duration(seconds = 42L))))
      assertTrue(encoded.failed.toOption.exists(_.getMessage.contains("'secnds'"))) &&
      assertTrue(decoded.failed.toOption.exists(_.getMessage.contains("'secnds'")))
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
    test("a message split into chunks round-trips onto its proto fields") {
      val codec = summon[MessageCodec[ChunkedMetrics]]
      val value = ChunkedMetrics(
        part1 = ChunkedMetrics.Part1(activeViewCtr = 1.5d, activeViewImpressions = 7L),
        part2 = ChunkedMetrics.Part2(activeViewMeasurability = 2.5d, other = 9L),
      )
      val message = DynamicMessage.parseFrom(chunkedMetricsDescriptor, codec.toByteArray(value))
      assertTrue(codec.parseFrom(codec.toByteArray(value)) == value) &&
      assertTrue(message.getField(chunkedMetricsDescriptor.findFieldByName("active_view_ctr")) == 1.5d) &&
      assertTrue(message.getField(chunkedMetricsDescriptor.findFieldByName("active_view_impressions")) == 7L) &&
      assertTrue(message.getField(chunkedMetricsDescriptor.findFieldByName("active_view_measurability")) == 2.5d) &&
      assertTrue(message.getField(chunkedMetricsDescriptor.findFieldByName("other")) == 9L)
    },
    test("groups assemble all required fields before validating the complete message") {
      val codec = MessageCodec[RequiredMessage]
      val value = RequiredMessage(RequiredMessage.Part1(Some("a")), RequiredMessage.Part2(Some("b")))
      val expected = DynamicMessage.newBuilder(requiredDescriptor)
        .setField(requiredDescriptor.findFieldByName("x"), "a")
        .setField(requiredDescriptor.findFieldByName("y"), "b")
        .build()
      assertTrue(codec.toByteArray(value).sameElements(expected.toByteArray)) &&
      assertTrue(codec.parseFrom(expected.toByteArray) == value) &&
      assertTrue(Try(codec.toByteArray(value.copy(part2 = RequiredMessage.Part2(None)))).isFailure)
    },
    test("oneofs in nested groups retain their wire encoding and presence") {
      val values = List(None, Some(Value.Kind.StringValue("value")), Some(Value.Kind.StructValue(Struct())))
      assertTrue(values.forall { kind =>
        val value = GroupedValue(ValueGroup(ValuePart(kind)))
        val codec = MessageCodec[GroupedValue]
        val bytes = MessageCodec[Value].toByteArray(Value(kind = kind))
        codec.toByteArray(value).sameElements(bytes) && codec.parseFrom(bytes) == value
      })
    },
    test("a group with another descriptor fails at the message boundary") {
      val codec = MessageCodec.derived[InvalidGroup](MessageCodec[Duration].descriptor)
      val result = Try(codec.toByteArray(InvalidGroup(ChunkedMetrics.Part1())))
      assertTrue(result.failed.toOption.exists(_.getMessage.contains("different descriptor")))
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
  private case class MisspelledDuration(secnds: Duration)
  private case class InvalidGroup(part1: ChunkedMetrics.Part1)
  private case class PartialDuration(seconds: Long = 0L)
  private case class SecondsPart(seconds: Long)
  private object SecondsPart {
    given MessageGroupCodec[SecondsPart] = MessageGroupCodec.derived[SecondsPart](MessageCodec[Duration].descriptor)
  }
  private case class TwiceSeconds(part: SecondsPart, seconds: Long, nanos: Int)
  private case class NoCodec(seconds: Chunk[Thread])

  private lazy val kindOneof = MessageCodec[Value].descriptor.findFieldByName("string_value").getContainingOneof

  private case class ValuePart(kind: Option[Value.Kind])
  private object ValuePart {
    given MessageGroupCodec[ValuePart] = MessageGroupCodec.derived[ValuePart](MessageCodec[Value].descriptor)
  }
  private case class ValueGroup(part: ValuePart)
  private object ValueGroup {
    given MessageGroupCodec[ValueGroup] = MessageGroupCodec.derived[ValueGroup](MessageCodec[Value].descriptor)
  }
  private case class GroupedValue(part: ValueGroup)
  private object GroupedValue {
    given MessageCodec[GroupedValue] = MessageCodec.derived[GroupedValue](MessageCodec[Value].descriptor)
  }

  private case class RequiredMessage(part1: RequiredMessage.Part1, part2: RequiredMessage.Part2)
  private object RequiredMessage {
    case class Part1(x: Option[String])
    object Part1 {
      given MessageGroupCodec[Part1] = MessageGroupCodec.derived[Part1](requiredDescriptor)
    }
    case class Part2(y: Option[String])
    object Part2 {
      given MessageGroupCodec[Part2] = MessageGroupCodec.derived[Part2](requiredDescriptor)
    }
    given MessageCodec[RequiredMessage] = MessageCodec.derived[RequiredMessage](requiredDescriptor)
  }

  private lazy val requiredDescriptor = {
    val message = DescriptorProto.newBuilder.setName("RequiredMessage")
    List("x", "y").zipWithIndex.foreach { case (name, index) =>
      val _ = message.addField(
        FieldDescriptorProto.newBuilder.setName(name).setNumber(index + 1)
          .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED).setType(FieldDescriptorProto.Type.TYPE_STRING),
      )
    }
    val proto = FileDescriptorProto.newBuilder.setName("required.proto").setSyntax("proto2").addMessageType(message).build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty).findMessageTypeByName("RequiredMessage")
  }

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

  // a message too large for one case class, split into consecutive chunks whose
  // codecs map their fields back to the message's own descriptor
  private case class ChunkedMetrics(
    part1: ChunkedMetrics.Part1 = ChunkedMetrics.Part1(),
    part2: ChunkedMetrics.Part2 = ChunkedMetrics.Part2(),
  )
  private object ChunkedMetrics {
    case class Part1(activeViewCtr: Double = 0.0d, activeViewImpressions: Long = 0L)
    object Part1 {
      given groupCodec: MessageGroupCodec[Part1] =
        MessageGroupCodec.derived[Part1](chunkedMetricsDescriptor)
    }
    case class Part2(activeViewMeasurability: Double = 0.0d, other: Long = 0L)
    object Part2 {
      given groupCodec: MessageGroupCodec[Part2] =
        MessageGroupCodec.derived[Part2](chunkedMetricsDescriptor)
    }
    given messageCodec: MessageCodec[ChunkedMetrics] =
      MessageCodec.derived[ChunkedMetrics](chunkedMetricsDescriptor)
  }

  private lazy val chunkedMetricsDescriptor = {
    def field(name: String, number: Int, fieldType: FieldDescriptorProto.Type) =
      FieldDescriptorProto.newBuilder
        .setName(name)
        .setNumber(number)
        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
        .setType(fieldType)

    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .addMessageType(
        DescriptorProto.newBuilder
          .setName("Metrics")
          .addField(field("active_view_ctr", 1, FieldDescriptorProto.Type.TYPE_DOUBLE))
          .addField(field("active_view_impressions", 2, FieldDescriptorProto.Type.TYPE_INT64))
          .addField(field("active_view_measurability", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
          .addField(field("other", 4, FieldDescriptorProto.Type.TYPE_INT64)),
      )
      .build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty).findMessageTypeByName("Metrics")
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
