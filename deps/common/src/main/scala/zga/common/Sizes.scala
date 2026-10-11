package zga.common

import com.google.protobuf.CodedOutputStream

/**
 * The lengths of the length delimited values of a message: recorded once while
 * sizing it, then read back in the same order while writing it, so that a
 * nested message is not sized again at each level, as protobuf-java memoizes
 * them.
 */
private[zga] final class Sizes {

  private var lengths = new Array[Int](16)
  private var length = 0
  private var position = 0

  /**
   * The size of a length delimited value of `size` bytes, including its length,
   * which `writeLength` writes back. The length's slot is reserved before
   * `size` is computed, as the values nested in it record their lengths after
   * it, in the order they are written.
   */
  inline def delimited(inline size: Int): Int = {
    val slot = reserve()
    val bytes = size
    lengths(slot) = bytes
    CodedOutputStream.computeUInt32SizeNoTag(bytes) + bytes
  }

  /**
   * Writes the length of the next length delimited value.
   */
  def writeLength(output: CodedOutputStream): Unit = {
    output.writeUInt32NoTag(lengths(position))
    position += 1
  }

  private def reserve(): Int = {
    if (length == lengths.length) {
      lengths = java.util.Arrays.copyOf(lengths, length * 2)
    }
    length += 1
    length - 1
  }
}
