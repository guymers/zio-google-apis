package consumer

import zga.common.MessageCodec
import zga.common.Oneof
import zga.common.OneofCodec
import zga.google.protobuf.ListValue
import zga.google.protobuf.Struct
import zga.google.protobuf.Value
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object OneofCodecTest extends ZIOSpecDefault {

  private sealed trait Kind extends Oneof
  private object Kind {
    case class NullValue(value: zga.google.protobuf.NullValue) extends Kind
    case class NumberValue(value: Double) extends Kind
    case class StringValue(value: String) extends Kind
    case class BoolValue(value: Boolean) extends Kind
    case class StructValue(value: Struct) extends Kind
    case class ListValue(value: zga.google.protobuf.ListValue) extends Kind

    given OneofCodec[Kind] = OneofCodec.derived[Kind](
      OneofCodec.Case.derived[NullValue](1),
      OneofCodec.Case.derived[NumberValue](2),
      OneofCodec.Case.derived[StringValue](3),
      OneofCodec.Case.derived[BoolValue](4),
      OneofCodec.Case.derived[StructValue](5),
      OneofCodec.Case.derived[ListValue](6),
    )
  }
  private case class OwnValue(kind: Option[Kind])
  private object OwnValue {
    given MessageCodec[OwnValue] = MessageCodec.derived[OwnValue](MessageCodec[Value].descriptor)
  }

  override val spec = suite("OneofCodec outside zga")(
    test("derives oneof codecs in a consumer package") {
      val value = OwnValue(kind = Some(Kind.ListValue(ListValue(values = zio.Chunk(Value())))))
      val bytes = MessageCodec[Value].toByteArray(
        Value(kind = Some(Value.Kind.ListValue(ListValue(values = zio.Chunk(Value()))))),
      )
      assertTrue(MessageCodec[OwnValue].toByteArray(value).sameElements(bytes)) &&
      assertTrue(MessageCodec[OwnValue].parseFrom(bytes) == value)
    },
  )
}
