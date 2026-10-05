package zga.common

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException

/**
 * Reads the protobuf wire format, tracking how deeply messages are nested so
 * that a malicious input cannot overflow the stack.
 *
 * `CodedInputStream` has the same check, but its depth is package private and
 * only advanced by its `readMessage` and `readGroup`, which need protobuf-java
 * builders.
 */
private[zga] final class ProtoReader(val input: CodedInputStream) {

  private var depth = 0

  /**
   * Reads a length delimited message body, `read` consuming the fields up to
   * the end of it.
   */
  inline def message[T](inline read: => T): T = {
    if (depth >= ProtoReader.RecursionLimit) {
      throw InvalidProtocolBufferException("Protocol message had too many levels of nesting. May be malicious.")
    }
    val limit = input.pushLimit(input.readRawVarint32())
    depth += 1
    val value = read
    input.checkLastTagWas(0)
    depth -= 1
    input.popLimit(limit)
    value
  }

  /**
   * Reads the elements of a packed repeated field, a length delimited record of
   * values without tags.
   */
  inline def packed(inline read: => Unit): Unit = {
    val limit = input.pushLimit(input.readRawVarint32())
    while (input.getBytesUntilLimit > 0) read
    input.popLimit(limit)
  }

  /**
   * Reads an entry of a map field, a message with the key as field 1 and the
   * value as field 2, either of which may be absent. `readValue` is given the
   * previous value, into which a message value merges as protobuf-java does.
   */
  inline def mapEntry[K, V](keyTag: Int, valueTag: Int, inline defaultKey: K, inline defaultValue: V)(
    inline readKey: K,
    inline readValue: V => V,
  )(inline put: (K, V) => Unit): Unit = message {
    var key = defaultKey
    var value = defaultValue
    var done = false
    while (!done) {
      val tag = input.readTag()
      if (tag == 0) done = true
      else if (tag == keyTag) key = readKey
      else if (tag == valueTag) value = readValue(value)
      else done = !input.skipField(tag)
    }
    put(key, value)
  }
}

private[zga] object ProtoReader {

  // the default of protobuf-java
  private val RecursionLimit = 100
}
