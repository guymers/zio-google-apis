package zga.common

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat
import com.google.protobuf as pb
import io.grpc.KnownLength
import io.grpc.Status
import io.grpc.StatusRuntimeException
import zga.codectest.ClosedEnums
import zga.codectest.Color
import zga.codectest.Interleaved
import zga.codectest.Large
import zga.codectest.LargeJava
import zga.codectest.Level
import zga.codectest.Levels
import zga.codectest.Maps
import zga.codectest.Node
import zga.codectest.Optionals
import zga.codectest.Proto2Java
import zga.codectest.Proto3Java
import zga.codectest.Repeated
import zga.codectest.Scalars
import zga.codectest.Size
import zga.codectest.Sparse
import zga.codectest.Sparses
import zga.codectest.Unpacked
import zga.google.protobuf.BytesValue
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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

object MessageCodecTest extends ZIOSpecDefault {

  override val spec = suite("MessageCodec")(
    suite("round trips")(
      test("scalar") {
        val value = Duration.ofSeconds(42L, 1L)
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
        val value = Scalars(int64Value = 1L, boolValue = true, stringValue = "value")
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
        val optionals = Optionals(count = Some(0), color = Some(Color.GREEN))
        val repeated = Repeated(colors = Chunk(Color.RED), messages = Chunk(Scalars(int32Value = 1, stringValue = "value")))
        assertTrue(roundTrip(optionals) == optionals) &&
        assertTrue(roundTrip(repeated) == repeated)
      },
      test("unrecognized enum values") {
        val value = Sparses(single = Sparse.fromValue(99))
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
    ),
    suite("java time types")(
      suite("timestamp")(
        test("epoch") {
          val value = Instant.EPOCH
          assertTrue(roundTrip(value) == value)
        },
        test("after the epoch") {
          val value = Instant.ofEpochSecond(42L, 1L)
          assertTrue(roundTrip(value) == value)
        },
        test("before the epoch") {
          val value = Instant.ofEpochSecond(-1L, 999_999_999L)
          assertTrue(roundTrip(value) == value)
        },
        suite("timestamps match protobuf-java")({
          def timestampMatchesJava(value: Instant) = {
            val java = pb.Timestamp.newBuilder.setSeconds(value.getEpochSecond).setNanos(value.getNano).build().toByteArray
            assertTrue(MessageCodec[Instant].toByteArray(value).sameElements(java)) &&
            assertTrue(MessageCodec[Instant].parseFrom(java) == value)
          }

          test("epoch") {
            timestampMatchesJava(Instant.EPOCH)
          } ::
          test("after the epoch") {
            timestampMatchesJava(Instant.ofEpochSecond(42L, 1L))
          } ::
          test("before the epoch") {
            timestampMatchesJava(Instant.ofEpochSecond(-1L, 999_999_999L))
          } ::
          test("the maximum valid timestamp") {
            timestampMatchesJava(Instant.ofEpochSecond(253_402_300_799L, 999_999_999L))
          } :: Nil
        }),
        test("reads a timestamp with negative nanos as their sum") {
          val java = pb.Timestamp.newBuilder.setSeconds(1L).setNanos(-1).build()
          assertTrue(MessageCodec[Instant].parseFrom(java.toByteArray) == Instant.ofEpochSecond(0L, 999_999_999L))
        },
        test("rejects a timestamp that java.time.Instant cannot represent as an invalid message") {
          val java = pb.Timestamp.newBuilder.setSeconds(Long.MaxValue).build()
          assertTrue(isInvalid(Try(MessageCodec[Instant].parseFrom(java.toByteArray))))
        },
      ),
      suite("duration")(
        test("zero") {
          val value = Duration.ZERO
          assertTrue(roundTrip(value) == value)
        },
        test("positive") {
          val value = Duration.ofSeconds(42L, 1L)
          assertTrue(roundTrip(value) == value)
        },
        test("negative, under a second") {
          val value = Duration.ofMillis(-500L)
          assertTrue(roundTrip(value) == value)
        },
        test("negative, over a second") {
          val value = Duration.ofSeconds(-2L, 250_000_000L)
          assertTrue(roundTrip(value) == value)
        },
        suite("match protobuf-java's normalized encoding")({
          def durationMatchesJava(value: Duration, seconds: Long, nanos: Int) = {
            val java = pb.Duration.newBuilder.setSeconds(seconds).setNanos(nanos).build().toByteArray
            assertTrue(MessageCodec[Duration].toByteArray(value).sameElements(java)) &&
            assertTrue(MessageCodec[Duration].parseFrom(java) == value)
          }

          test("zero") {
            durationMatchesJava(Duration.ZERO, 0L, 0)
          } ::
          test("positive") {
            durationMatchesJava(Duration.ofSeconds(42L, 1L), 42L, 1)
          } ::
          test("negative, under a second") {
            durationMatchesJava(Duration.ofMillis(-500L), 0L, -500_000_000)
          } ::
          test("negative, over a second") {
            durationMatchesJava(Duration.ofSeconds(-2L, 250_000_000L), -1L, -750_000_000)
          } ::
          test("whole seconds") {
            durationMatchesJava(Duration.ofSeconds(3L), 3L, 0)
          } :: Nil
        }),
        test("reads a duration whose seconds and nanos differ in sign as their sum") {
          val java = pb.Duration.newBuilder.setSeconds(-1L).setNanos(500_000_000).build()
          assertTrue(MessageCodec[Duration].parseFrom(java.toByteArray) == Duration.ofMillis(-500L))
        },
        test("rejects a duration that java.time.Duration cannot represent as an invalid message") {
          val java = pb.Duration.newBuilder.setSeconds(Long.MaxValue).setNanos(1_000_000_000).build()
          assertTrue(isInvalid(Try(MessageCodec[Duration].parseFrom(java.toByteArray))))
        },
      ),
      test("default values are not written") {
        assertTrue(MessageCodec[Instant].toByteArray(Instant.EPOCH).isEmpty) &&
        assertTrue(MessageCodec[Duration].toByteArray(Duration.ZERO).isEmpty)
      },
      test("day of week") {
        assertTrue(zga.google.`type`.DayOfWeek.fromValue(1).contains(DayOfWeek.MONDAY)) &&
        assertTrue(zga.google.`type`.DayOfWeek.fromValue(0).isEmpty) &&
        assertTrue(zga.google.`type`.DayOfWeek.value(Some(DayOfWeek.SUNDAY)) == 7) &&
        assertTrue(zga.google.`type`.DayOfWeek.value(None) == 0)
      },
      test("time of day keeps 24:00, which java.time.LocalTime cannot hold") {
        val value = zga.google.`type`.TimeOfDay(hours = 24)
        assertTrue(roundTrip(value) == value)
      },
      test("date time without an offset stays a local time") {
        val bytes = encode { out =>
          out.writeInt32(1, 2024)
          out.writeInt32(2, 1)
          out.writeInt32(3, 2)
          out.writeInt32(4, 3)
        }
        val codec = MessageCodec[zga.google.`type`.DateTime]
        assertTrue(codec.toByteArray(codec.parseFrom(bytes)).sameElements(bytes))
      },
    ),
    suite("matches protobuf-java")(
      suite("every scalar type")({
        def scalarsMatchJava(value: Scalars) = {
          val bytes = javaScalars(value).toByteArray
          val decoded = MessageCodec[Scalars].parseFrom(bytes)
          assertTrue(MessageCodec[Scalars].toByteArray(value).sameElements(bytes)) &&
          // NaN is not equal to itself, so compare the re-encoded bytes
          assertTrue(MessageCodec[Scalars].toByteArray(decoded).sameElements(bytes))
        }

        test("maximum integers") {
          scalarsMatchJava(
            Scalars(
              int32Value = Int.MaxValue,
              int64Value = Long.MaxValue,
              uint32Value = Int.MaxValue,
              uint64Value = Long.MaxValue,
              sint32Value = Int.MaxValue,
              sint64Value = Long.MaxValue,
              fixed32Value = Int.MaxValue,
              fixed64Value = Long.MaxValue,
              sfixed32Value = Int.MaxValue,
              sfixed64Value = Long.MaxValue,
              floatValue = 1.5f,
              doubleValue = 2.5d,
              boolValue = true,
              stringValue = "ünïcødé ✓",
              bytesValue = ByteString.copyFrom(Array[Byte](0, -1, 127)),
            ),
          )
        } ::
        test("minimum values") {
          scalarsMatchJava(
            Scalars(
              int32Value = Int.MinValue,
              int64Value = Long.MinValue,
              uint32Value = -1,
              uint64Value = -1L,
              sint32Value = Int.MinValue,
              sint64Value = Long.MinValue,
              fixed32Value = -1,
              fixed64Value = -1L,
              sfixed32Value = Int.MinValue,
              sfixed64Value = Long.MinValue,
              floatValue = Float.MinValue,
              doubleValue = Double.NegativeInfinity,
              boolValue = true,
              stringValue = "s",
              bytesValue = ByteString.copyFromUtf8("b"),
            ),
          )
        } ::
        test("negative one, NaN and the smallest positive double") {
          scalarsMatchJava(
            Scalars(int32Value = -1, sint32Value = -1, sint64Value = -1L, floatValue = Float.NaN, doubleValue = Double.MinPositiveValue),
          )
        } :: Nil
      }),
      test("packed repeated fields") {
        val value = Repeated(
          ints = Chunk(1, -1, Int.MaxValue),
          zigzags = Chunk(-1L, Long.MinValue),
          fixeds = Chunk(-1, 2),
          doubles = Chunk(1.5d, -0.0d),
          bools = Chunk(true, false),
          strings = Chunk("a", ""),
          colors = Chunk(Color.RED, Color.fromValue(99)),
          messages = Chunk(Scalars(int32Value = 1), Scalars()),
        )
        val java = Proto3Java.Repeated.newBuilder
          .addAllInts(value.ints.map(Int.box).asJava)
          .addAllZigzags(value.zigzags.map(Long.box).asJava)
          .addAllFixeds(value.fixeds.map(Int.box).asJava)
          .addAllDoubles(value.doubles.map(Double.box).asJava)
          .addAllBools(value.bools.map(Boolean.box).asJava)
          .addAllStrings(value.strings.asJava)
          .addAllColorsValue(value.colors.map(color => Int.box(color.value)).asJava)
          .addAllMessages(value.messages.map(javaScalars).asJava)
          .build()
        assertTrue(MessageCodec[Repeated].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Repeated].parseFrom(java.toByteArray) == value)
      },
      test("unpacked repeated fields") {
        val value = Unpacked(
          ints = Chunk(1, -1, Int.MaxValue),
          zigzags = Chunk(-1L, Long.MinValue),
          fixeds = Chunk(-1, 2),
          doubles = Chunk(1.5d, -0.0d),
          bools = Chunk(true, false),
          strings = Chunk("a", ""),
          sizes = Chunk(Size.LARGE, Size.SIZE_UNSPECIFIED),
        )
        val java = Proto2Java.Unpacked.newBuilder
          .addAllInts(value.ints.map(Int.box).asJava)
          .addAllZigzags(value.zigzags.map(Long.box).asJava)
          .addAllFixeds(value.fixeds.map(Int.box).asJava)
          .addAllDoubles(value.doubles.map(Double.box).asJava)
          .addAllBools(value.bools.map(Boolean.box).asJava)
          .addAllStrings(value.strings.asJava)
          .addAllSizes(value.sizes.map(size => Proto2Java.Size.forNumber(size.value)).asJava)
          .build()
        assertTrue(MessageCodec[Unpacked].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Unpacked].parseFrom(java.toByteArray) == value)
      },
      test("proto3 optional fields") {
        val value = Optionals(count = Some(0), name = Some(""), color = Some(Color.COLOR_UNSPECIFIED))
        val java = Proto3Java.Optionals.newBuilder.setCount(0).setName("").setColor(Proto3Java.Color.COLOR_UNSPECIFIED).build()
        assertTrue(MessageCodec[Optionals].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Optionals].parseFrom(java.toByteArray) == value) &&
        assertTrue(MessageCodec[Optionals].toByteArray(Optionals()).isEmpty)
      },
      test("map fields of each kind of value") {
        val value = Maps(
          colors = Map("green" -> Color.GREEN),
          scalars = Map(-1L -> Scalars(stringValue = "value")),
          blobs = Map(-1 -> ByteString.copyFromUtf8("blob")),
        )
        val java = Proto3Java.Maps.newBuilder
          .putColors("green", Proto3Java.Color.GREEN)
          .putScalars(-1L, javaScalars(Scalars(stringValue = "value")))
          .putBlobs(-1, ByteString.copyFromUtf8("blob"))
          .build()
        assertTrue(MessageCodec[Maps].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Maps].parseFrom(java.toByteArray) == value)
      },
      test("enum values have protobuf-java's numbers, whether numbered by ordinal or not") {
        def numbers(descriptor: pb.Descriptors.EnumDescriptor) = descriptor.getValues.asScala.map(v => v.getName -> v.getNumber).toList
        assertTrue(Color.Recognized.values.map(v => v.toString -> v.value).toList == numbers(Proto3Java.Color.getDescriptor)) &&
        assertTrue(Sparse.Recognized.values.map(v => v.toString -> v.value).toList == numbers(Proto3Java.Sparse.getDescriptor)) &&
        assertTrue(Size.values.map(v => v.toString -> v.value).toList == numbers(Proto2Java.Size.getDescriptor)) &&
        assertTrue(Level.values.map(v => v.toString -> v.value).toList == numbers(Proto2Java.Level.getDescriptor))
      },
      test("enums numbered other than by ordinal") {
        val sparse = Sparses(
          single = Sparse.NEGATIVE,
          many = Chunk(Sparse.TEN, Sparse.THREE, Sparse.SPARSE_UNSPECIFIED, Sparse.fromValue(99)),
          byName = Map("three" -> Sparse.THREE),
        )
        val javaSparse = Proto3Java.Sparses.newBuilder
          .setSingleValue(-1)
          .addAllManyValue(sparse.many.map(v => Int.box(v.value)).asJava)
          .putByNameValue("three", 3)
          .build()
        val levels = Levels(single = Some(Level.LOW), many = Chunk(Level.HIGH, Level.MEDIUM))
        val javaLevels = Proto2Java.Levels.newBuilder
          .setSingle(Proto2Java.Level.LOW)
          .addAllMany(levels.many.map(v => Proto2Java.Level.forNumber(v.value)).asJava)
          .build()
        assertTrue(MessageCodec[Sparses].toByteArray(sparse).sameElements(javaSparse.toByteArray)) &&
        assertTrue(MessageCodec[Sparses].parseFrom(javaSparse.toByteArray) == sparse) &&
        // an aliased number reads as the first value declared with it
        assertTrue(Sparse.fromValue(Sparse.ALSO_TEN.value) == Sparse.TEN) &&
        assertTrue(MessageCodec[Levels].toByteArray(levels).sameElements(javaLevels.toByteArray)) &&
        assertTrue(MessageCodec[Levels].parseFrom(javaLevels.toByteArray) == levels)
      },
      test("a message split into chunks writes its fields in field number order") {
        val value = Large(
          part1 = Large.Part1(choice = Some(Large.Choice.Name("name")), d1 = 1.5d),
          part5 = Large.Part5(d128 = 2.5d, count = 7L),
        )
        val java = LargeJava.Large.newBuilder.setName("name").setD1(1.5d).setD128(2.5d).setCount(7L).build()
        assertTrue(MessageCodec[Large].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Large].parseFrom(java.toByteArray) == value) &&
        assertTrue(value.d128 == 2.5d)
      },
      test("proto2 messages, byte for byte") {
        val data = ByteString.copyFrom(Array[Byte](0, -1))
        val java = Proto2Java.Node.newBuilder
          .setName("root")
          .setNumber(0)
          .setSize(Proto2Java.Size.LARGE)
          .addAllUnpacked(List(1, -1, 2).map(Int.box).asJava)
          .addAllPacked(List(4, 0, 2, 1).map(Int.box).asJava)
          .setUnsigned(-1L)
          .setSigned(Long.MinValue)
          .setReal(-0.0d)
          .setData(data)
          .setChild(Proto2Java.Node.newBuilder.setName("child").putCounts(0, 0))
          .addChildren(Proto2Java.Node.newBuilder.setNumber(1))
          .addChildren(Proto2Java.Node.getDefaultInstance)
          .putCounts(1, -1)
          .build()
        val value = Node(
          name = Some("root"),
          number = Some(0),
          size = Some(Size.LARGE),
          unpacked = Chunk(1, -1, 2),
          packed = Chunk(4, 0, 2, 1),
          unsigned = Some(-1L),
          signed = Some(Long.MinValue),
          real = Some(-0.0d),
          data = Some(data),
          child = Some(Node(name = Some("child"), counts = Map(0 -> 0))),
          children = Chunk(Node(number = Some(1)), Node()),
          counts = Map(1 -> -1),
        )
        assertTrue(MessageCodec[Node].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(MessageCodec[Node].parseFrom(java.toByteArray) == value)
      },
      test("proto3 messages with maps, oneofs and recursion") {
        def javaValue(build: pb.Value.Builder => pb.Value.Builder) = build(pb.Value.newBuilder).build()
        val java = pb.Struct.newBuilder
          .putFields("number", javaValue(_.setNumberValue(1.5d)))
          .putFields("bool", javaValue(_.setBoolValue(true)))
          .putFields(
            "list",
            javaValue(
              _.setListValue(
                pb.ListValue.newBuilder.addValues(javaValue(_.setStringValue("value"))).addValues(javaValue(_.setNullValue(pb.NullValue.NULL_VALUE))),
              ),
            ),
          )
          .putFields("struct", javaValue(_.setStructValue(pb.Struct.newBuilder.putFields("nested", javaValue(_.setNumberValue(0.0d))))))
          .build()
        val expected = Struct(fields =
          Map(
            "number" -> Value(kind = Some(Value.Kind.NumberValue(1.5d))),
            "bool" -> Value(kind = Some(Value.Kind.BoolValue(true))),
            "list" -> Value(kind =
              Some(
                Value.Kind.ListValue(
                  ListValue(values =
                    Chunk(Value(kind = Some(Value.Kind.StringValue("value"))), Value(kind = Some(Value.Kind.NullValue(NullValue.NULL_VALUE)))),
                  ),
                ),
              ),
            ),
            "struct" -> Value(kind =
              Some(Value.Kind.StructValue(Struct(fields = Map("nested" -> Value(kind = Some(Value.Kind.NumberValue(0.0d))))))),
            ),
          ),
        )
        val value = MessageCodec[Struct].parseFrom(java.toByteArray)
        assertTrue(value == expected) &&
        assertTrue(pb.Struct.parseFrom(MessageCodec[Struct].toByteArray(value)) == java)
      },
    ),
    suite("presence")(
      test("a field without presence is not written when it holds its default value") {
        assertTrue(MessageCodec[Scalars].toByteArray(Scalars()).isEmpty) &&
        assertTrue(MessageCodec[Duration].toByteArray(Duration.ZERO).isEmpty)
      },
      test("negative zero is not a default value") {
        val value = Scalars(floatValue = -0.0f, doubleValue = -0.0d)
        val decoded = roundTrip(value)
        assertTrue(MessageCodec[Scalars].toByteArray(value).sameElements(javaScalars(value).toByteArray)) &&
        assertTrue(java.lang.Float.floatToRawIntBits(decoded.floatValue) == java.lang.Float.floatToRawIntBits(-0.0f)) &&
        assertTrue(java.lang.Double.doubleToRawLongBits(decoded.doubleValue) == java.lang.Double.doubleToRawLongBits(-0.0d))
      },
      test("a field with presence is written when set to its default value") {
        val java = Proto2Java.Node.newBuilder.setNumber(0).build()
        val value = Node(number = Some(0))
        assertTrue(MessageCodec[Node].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(roundTrip(value) == value)
      },
      test("a oneof member is written when it holds its default value") {
        val java = pb.Value.newBuilder.setNumberValue(0.0d).build()
        val value = Value(kind = Some(Value.Kind.NumberValue(0.0d)))
        assertTrue(MessageCodec[Value].toByteArray(value).sameElements(java.toByteArray)) &&
        assertTrue(roundTrip(value) == value)
      },
    ),
    suite("unknown input")(
      test("skips unknown fields of every wire type") {
        val bytes = encode { out =>
          out.writeInt64(1, 42L)
          out.writeUInt64(100, 1L)
          out.writeFixed32(101, 2)
          out.writeFixed64(102, 3L)
          out.writeString(103, "unknown")
          out.writeTag(104, WireFormat.WIRETYPE_START_GROUP)
          out.writeInt32(1, 5)
          out.writeTag(104, WireFormat.WIRETYPE_END_GROUP)
          out.writeInt32(2, 7)
        }
        assertTrue(MessageCodec[Duration].parseFrom(bytes) == Duration.ofSeconds(42L, 7L))
      },
      test("skips a known field with an unexpected wire type, as protobuf-java does") {
        val bytes = encode { out =>
          out.writeString(1, "not a varint")
          out.writeInt32(2, 7)
        }
        assertTrue(pb.Duration.parseFrom(bytes).getSeconds == 0L) &&
        assertTrue(MessageCodec[Duration].parseFrom(bytes) == Duration.ofNanos(7L))
      },
      test("rejects an end group tag that ends no group") {
        val bytes = encode(_.writeTag(5, WireFormat.WIRETYPE_END_GROUP))
        assertTrue(Try(MessageCodec[Duration].parseFrom(bytes)).isFailure)
      },
      test("rejects a truncated message") {
        val bytes = MessageCodec[Struct].toByteArray(Struct(fields = Map("key" -> Value(kind = Some(Value.Kind.StringValue("value"))))))
        assertTrue(Try(MessageCodec[Struct].parseFrom(bytes.dropRight(1))).isFailure)
      },
      test("rejects messages nested deeper than protobuf-java allows") {
        def nest(depth: Int) = (1 to depth).foldLeft(Value()) { (value, _) =>
          Value(kind = Some(Value.Kind.ListValue(ListValue(values = Chunk(value)))))
        }
        // each level is a Value and a ListValue
        val shallow = MessageCodec[Value].toByteArray(nest(49))
        val deep = MessageCodec[Value].toByteArray(nest(51))
        assertTrue(Try(MessageCodec[Value].parseFrom(shallow)).isSuccess) &&
        assertTrue(Try(MessageCodec[Value].parseFrom(deep)).isFailure) &&
        assertTrue(Try(pb.Value.parseFrom(deep)).isFailure)
      },
    ),
    suite("closed enums")(
      test("drop an unrecognized value, keeping the previous one") {
        val absent = encode(_.writeEnum(3, 99))
        val bytes = encode { out =>
          out.writeString(1, "node")
          out.writeEnum(3, 99)
          out.writeEnum(3, Size.LARGE.value)
          out.writeEnum(3, 98)
        }
        assertTrue(!Proto2Java.Node.parseFrom(absent).hasSize) &&
        assertTrue(MessageCodec[Node].parseFrom(absent) == Node()) &&
        assertTrue(MessageCodec[Node].parseFrom(bytes) == Node(name = Some("node"), size = Some(Size.LARGE)))
      },
      test("drop unrecognized elements of a repeated field, packed or not") {
        val numbers = List(1, 99, 2)
        def unpacked(number: Int) = encode(out => numbers.foreach(out.writeEnum(number, _)))
        def packed(number: Int) = encode { out =>
          out.writeTag(number, WireFormat.WIRETYPE_LENGTH_DELIMITED)
          out.writeUInt32NoTag(numbers.map(CodedOutputStream.computeEnumSizeNoTag).sum)
          numbers.foreach(out.writeEnumNoTag)
        }
        val expected = Chunk(Size.SMALL, Size.LARGE)
        assertTrue(MessageCodec[ClosedEnums].parseFrom(unpacked(2)).unpacked == expected) &&
        assertTrue(MessageCodec[ClosedEnums].parseFrom(packed(2)).unpacked == expected) &&
        assertTrue(MessageCodec[ClosedEnums].parseFrom(unpacked(3)).packed == expected) &&
        assertTrue(MessageCodec[ClosedEnums].parseFrom(packed(3)).packed == expected)
      },
      test("drop an unrecognized oneof member, keeping the previous one") {
        val bytes = encode { out =>
          out.writeEnum(5, Size.LARGE.value)
          out.writeEnum(5, 99)
        }
        assertTrue(MessageCodec[ClosedEnums].parseFrom(bytes).choice.contains(ClosedEnums.Choice.ChoiceSize(Size.LARGE)))
      },
      test("read an absent map value as the enum's first value") {
        val bytes = encode { out =>
          out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED)
          out.writeUInt32NoTag(CodedOutputStream.computeStringSize(1, "key"))
          out.writeString(1, "key")
        }
        assertTrue(MessageCodec[ClosedEnums].parseFrom(bytes).byName == Map("key" -> Size.SIZE_UNSPECIFIED))
      },
      test("drop a map entry whose value is unrecognized") {
        val bytes = encode { out =>
          List("known" -> Size.SMALL.value, "unknown" -> 99).foreach { case (key, number) =>
            out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED)
            out.writeUInt32NoTag(CodedOutputStream.computeStringSize(1, key) + CodedOutputStream.computeEnumSize(2, number))
            out.writeString(1, key)
            out.writeEnum(2, number)
          }
        }
        assertTrue(MessageCodec[ClosedEnums].parseFrom(bytes).byName == Map("known" -> Size.SMALL))
      },
    ),
    suite("repeated occurrences")(
      test("the last value of a scalar field wins") {
        val bytes = MessageCodec[Duration].toByteArray(Duration.ofSeconds(1L, 2L)) ++
          MessageCodec[Duration].toByteArray(Duration.ofSeconds(3L))
        assertTrue(MessageCodec[Duration].parseFrom(bytes) == Duration.ofSeconds(3L, 2L))
      },
      test("a message field merges into the previous value, as protobuf-java does") {
        val first = Proto2Java.Node.newBuilder
          .setName("first")
          .setChild(Proto2Java.Node.newBuilder.setSize(Proto2Java.Size.SMALL))
          .build()
        val second = Proto2Java.Node.newBuilder
          .setNumber(1)
          .setChild(Proto2Java.Node.newBuilder.setNumber(2).addUnpacked(3))
          .build()
        val bytes = first.toByteArray ++ second.toByteArray
        val value = MessageCodec[Node].parseFrom(bytes)
        val child = Node(number = Some(2), size = Some(Size.SMALL), unpacked = Chunk(3))
        assertTrue(value == Node(name = Some("first"), number = Some(1), child = Some(child))) &&
        assertTrue(MessageCodec[Node].toByteArray(value).sameElements(Proto2Java.Node.parseFrom(bytes).toByteArray))
      },
      test("a message split into chunks merges each read field into the previous value") {
        val previous = Large(
          part1 = Large.Part1(d1 = 1.5d),
          part3 = Large.Part3(d70 = 3.5d),
          part5 = Large.Part5(count = 5L),
        )
        val bytes = LargeJava.Large.newBuilder.setD2(2.5d).setD128(4.5d).build().toByteArray
        val value = MessageCodec[Large].readFrom(ProtoReader(CodedInputStream.newInstance(bytes)), previous)
        assertTrue(
          value == Large(
            part1 = Large.Part1(d1 = 1.5d, d2 = 2.5d),
            part3 = Large.Part3(d70 = 3.5d),
            part5 = Large.Part5(d128 = 4.5d, count = 5L),
          ),
        ) &&
        // a chunk none of whose fields were read is the previous one
        assertTrue(value.part3 eq previous.part3)
      },
      test("many occurrences of a message field merge in linear time") {
        val count = 100000
        val child = encode(_.writeInt32(4, 1))
        val bytes = encode(out => (1 to count).foreach(_ => out.writeByteArray(10, child)))
        val value = MessageCodec[Node].parseFrom(bytes)
        assertTrue(value.child.map(_.unpacked.length).contains(count)) &&
        assertTrue(MessageCodec[Node].toByteArray(value).sameElements(Proto2Java.Node.parseFrom(bytes).toByteArray))
      },
      test("a oneof message member merges into the previous value of the same member") {
        def struct(key: String) = Value(kind = Some(Value.Kind.StructValue(Struct(fields = Map(key -> Value())))))
        val bytes = MessageCodec[Value].toByteArray(struct("a")) ++ MessageCodec[Value].toByteArray(struct("b"))
        val expected = Value(kind = Some(Value.Kind.StructValue(Struct(fields = Map("a" -> Value(), "b" -> Value())))))
        assertTrue(pb.Value.parseFrom(bytes).getStructValue.getFieldsCount == 2) &&
        assertTrue(MessageCodec[Value].parseFrom(bytes) == expected)
      },
      test("a oneof member replaces another member") {
        val struct = Value(kind = Some(Value.Kind.StructValue(Struct(fields = Map("a" -> Value())))))
        val string = Value(kind = Some(Value.Kind.StringValue("value")))
        val bytes = MessageCodec[Value].toByteArray(struct) ++ MessageCodec[Value].toByteArray(string)
        assertTrue(MessageCodec[Value].parseFrom(bytes) == string)
      },
    ),
    suite("repeated fields")(
      test("a packed field reads unpacked elements") {
        val bytes = encode { out =>
          out.writeInt32(5, 4)
          out.writeInt32(5, 0)
        }
        assertTrue(MessageCodec[Node].parseFrom(bytes).packed == Chunk(4, 0))
      },
      test("an unpacked field reads packed elements") {
        val bytes = encode { out =>
          out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED)
          out.writeUInt32NoTag(CodedOutputStream.computeInt32SizeNoTag(1) + CodedOutputStream.computeInt32SizeNoTag(-1))
          out.writeInt32NoTag(1)
          out.writeInt32NoTag(-1)
          out.writeInt32(4, 2)
        }
        assertTrue(MessageCodec[Node].parseFrom(bytes).unpacked == Chunk(1, -1, 2))
      },
      test("an empty packed field is not written") {
        assertTrue(MessageCodec[Node].toByteArray(Node(packed = Chunk.empty)).isEmpty)
      },
    ),
    suite("map fields")(
      test("write the key and value of an entry even when they are defaults") {
        val java = pb.Struct.newBuilder.putFields("", pb.Value.getDefaultInstance).build()
        assertTrue(MessageCodec[Struct].toByteArray(Struct(fields = Map("" -> Value()))).sameElements(java.toByteArray))
      },
      test("read an entry missing its key or value as the default") {
        val bytes = encode { out =>
          out.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED)
          out.writeUInt32NoTag(CodedOutputStream.computeStringSize(1, "key"))
          out.writeString(1, "key")
          out.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED)
          val value = MessageCodec[Value].toByteArray(Value(kind = Some(Value.Kind.BoolValue(true))))
          out.writeUInt32NoTag(CodedOutputStream.computeByteArraySize(2, value))
          out.writeByteArray(2, value)
        }
        assertTrue(MessageCodec[Struct].parseFrom(bytes) == Struct(fields = Map("key" -> Value(), "" -> Value(kind = Some(Value.Kind.BoolValue(true))))))
      },
      test("a message value read again merges into the previous value, as protobuf-java does") {
        def number(value: Double) = Value(kind = Some(Value.Kind.NumberValue(value)))
        def list(values: Value*) = Value(kind = Some(Value.Kind.ListValue(ListValue(values = Chunk.fromIterable(values)))))
        val entry = encode { out =>
          out.writeString(1, "key")
          out.writeByteArray(2, MessageCodec[Value].toByteArray(list(number(1))))
          out.writeByteArray(2, MessageCodec[Value].toByteArray(list(number(2))))
        }
        val bytes = encode(_.writeByteArray(1, entry))
        val value = MessageCodec[Struct].parseFrom(bytes)
        assertTrue(value == Struct(fields = Map("key" -> list(number(1), number(2))))) &&
        assertTrue(MessageCodec[Struct].toByteArray(value).sameElements(pb.Struct.parseFrom(bytes).toByteArray))
      },
      test("the last entry for a key wins") {
        def entry(value: Boolean) = MessageCodec[Struct].toByteArray(Struct(fields = Map("key" -> Value(kind = Some(Value.Kind.BoolValue(value))))))
        val bytes = entry(true) ++ entry(false)
        assertTrue(MessageCodec[Struct].parseFrom(bytes) == Struct(fields = Map("key" -> Value(kind = Some(Value.Kind.BoolValue(false))))))
      },
    ),
    suite("strings")(
      test("a validated string fails to read invalid UTF-8, as protobuf-java does") {
        val bytes = encode(_.writeByteArray(1, Array(0xff.toByte)))
        assertTrue(Try(pb.StringValue.parseFrom(bytes)).isFailure) &&
        assertTrue(Try(MessageCodec[StringValue].parseFrom(bytes)).isFailure)
      },
      test("an unvalidated string reads invalid UTF-8 as a replacement character") {
        val bytes = encode(_.writeByteArray(1, Array(0xff.toByte)))
        assertTrue(MessageCodec[Node].parseFrom(bytes).name.contains("�"))
      },
      test("writes an unpaired surrogate as protobuf-java does") {
        val value = "a\uD800b"
        assertTrue(MessageCodec[StringValue].toByteArray(StringValue(value)).sameElements(pb.StringValue.of(value).toByteArray))
      },
    ),
    suite("oneofs")(
      test("a oneof is written as one block, at its first member's number") {
        val value = Interleaved(choice = Some(Interleaved.Choice.Third(Scalars(boolValue = true))), second = 2)
        val java = Proto3Java.Interleaved.newBuilder.setThird(javaScalars(Scalars(boolValue = true))).setSecond(2).build()
        val bytes = MessageCodec[Interleaved].toByteArray(value)
        // protobuf-java writes field 2 before field 3, both orders are valid
        assertTrue(!bytes.sameElements(java.toByteArray)) &&
        assertTrue(Proto3Java.Interleaved.parseFrom(bytes) == java) &&
        assertTrue(MessageCodec[Interleaved].parseFrom(java.toByteArray) == value)
      },
      suite("a oneof in a chunk of a large message")(
        test("no member set") {
          val value = Large(part1 = Large.Part1(choice = None))
          assertTrue(roundTrip(value) == value)
        },
        test("a string member") {
          val value = Large(part1 = Large.Part1(choice = Some(Large.Choice.Name(""))))
          assertTrue(roundTrip(value) == value)
        },
        test("a number member") {
          val value = Large(part1 = Large.Part1(choice = Some(Large.Choice.Number(0L))))
          assertTrue(roundTrip(value) == value)
        },
      ),
    ),
    suite("api")(
      test("grpc marshaller") {
        val value = Duration.ofSeconds(42L, 1L)
        val marshaller = MessageCodec.marshaller[Duration]
        val stream = marshaller.stream(value)
        assertTrue(stream.isInstanceOf[KnownLength]) &&
        assertTrue(marshaller.parse(stream) == value)
      },
      test("grpc marshaller reads an empty stream as the default value") {
        val marshaller = MessageCodec.marshaller[Duration]
        assertTrue(marshaller.parse(marshaller.stream(Duration.ZERO)) == Duration.ZERO)
      },
      test("grpc marshaller reads a stream of unknown length") {
        val value = Duration.ofSeconds(42L, 1L)
        val stream = new ByteArrayInputStream(MessageCodec[Duration].toByteArray(value))
        assertTrue(MessageCodec.marshaller[Duration].parse(stream) == value)
      },
      test("grpc marshaller fails an invalid message with an internal status, as protobuf-java does") {
        val marshaller = MessageCodec.marshaller[Duration]
        val bytes = MessageCodec[Duration].toByteArray(Duration.ofSeconds(42L)).dropRight(1)
        val result = Try(marshaller.parse(new ByteArrayInputStream(bytes)))
        assertTrue(result.failed.toOption.collect { case e: StatusRuntimeException => e.getStatus.getCode }.contains(Status.Code.INTERNAL))
      },
      test("grpc marshaller fails a value java.time cannot represent with an internal status") {
        val bytes = pb.Duration.newBuilder.setSeconds(Long.MaxValue).setNanos(1_000_000_000).build().toByteArray
        val result = Try(MessageCodec.marshaller[Duration].parse(new ByteArrayInputStream(bytes)))
        assertTrue(result.failed.toOption.collect { case e: StatusRuntimeException => e.getStatus.getCode }.contains(Status.Code.INTERNAL))
      },
      test("safely parses a ByteString") {
        val value = Duration.ofSeconds(42L, 1L)
        val bytes = ByteString.copyFrom(MessageCodec[Duration].toByteArray(value))
        val truncated = ByteString.copyFrom(Array[Byte](0x0a, 0x05))
        assertTrue(MessageCodec[Duration].safeParseFrom(bytes) == Right(value)) &&
        assertTrue(MessageCodec[Duration].safeParseFrom(truncated).isLeft)
      },
    ),
  )

  private def javaScalars(value: Scalars): Proto3Java.Scalars = {
    Proto3Java.Scalars.newBuilder
      .setInt32Value(value.int32Value)
      .setInt64Value(value.int64Value)
      .setUint32Value(value.uint32Value)
      .setUint64Value(value.uint64Value)
      .setSint32Value(value.sint32Value)
      .setSint64Value(value.sint64Value)
      .setFixed32Value(value.fixed32Value)
      .setFixed64Value(value.fixed64Value)
      .setSfixed32Value(value.sfixed32Value)
      .setSfixed64Value(value.sfixed64Value)
      .setFloatValue(value.floatValue)
      .setDoubleValue(value.doubleValue)
      .setBoolValue(value.boolValue)
      .setStringValue(value.stringValue)
      .setBytesValue(value.bytesValue)
      .build()
  }

  private def encode(write: CodedOutputStream => Unit): Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val output = CodedOutputStream.newInstance(buffer)
    write(output)
    output.flush()
    buffer.toByteArray
  }

  private def isInvalid(result: Try[?]) = result.failed.toOption.exists(_.isInstanceOf[InvalidProtocolBufferException])

  private def roundTrip[A](value: A)(using messageCodec: MessageCodec[A]) = {
    messageCodec.parseFrom(messageCodec.toByteArray(value))
  }
}
