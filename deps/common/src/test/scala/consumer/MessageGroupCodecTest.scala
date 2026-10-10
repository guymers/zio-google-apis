package consumer

import zga.common.MessageCodec
import zga.common.MessageGroupCodec
import zga.google.protobuf.Duration
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object MessageGroupCodecTest extends ZIOSpecDefault {

  private case class Part(seconds: Long)
  private object Part {
    given MessageGroupCodec[Part] = MessageGroupCodec.derived[Part](MessageCodec[Duration].descriptor)
  }
  private case class Whole(part: Part, nanos: Int)
  private object Whole {
    given MessageCodec[Whole] = MessageCodec.derived[Whole](MessageCodec[Duration].descriptor)
  }

  override val spec = suite("MessageGroupCodec outside zga")(
    test("derives group and message codecs in a consumer package") {
      val value = Whole(part = Part(seconds = 42L), nanos = 7)
      val bytes = MessageCodec[Duration].toByteArray(Duration(seconds = 42L, nanos = 7))
      assertTrue(MessageCodec[Whole].toByteArray(value).sameElements(bytes)) &&
      assertTrue(MessageCodec[Whole].parseFrom(bytes) == value)
    },
  )
}
