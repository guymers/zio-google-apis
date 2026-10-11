package zga.common

/**
 * A value of a generated enum.
 */
trait ProtoEnum {
  def value: Int
}

object ProtoEnum {

  /**
   * The values by number, the first of values sharing a number winning, as in
   * protobuf-java.
   */
  private[zga] def valueMap[E <: ProtoEnum](values: Array[E]): Map[Int, E] = {
    val builder = Map.newBuilder[Int, E]
    var i = values.length - 1
    while (i >= 0) {
      val v = values(i)
      builder += v.value -> v
      i -= 1
    }
    builder.result()
  }
}

/**
 * A number of an open enum that the generated code does not know. Generated as
 * a plain class rather than a case class, as it is never constructed or
 * destructured outside of the generated code.
 */
abstract class UnrecognizedValue(val value: Int) extends ProtoEnum {

  override def equals(other: Any): Boolean = {
    other != null && other.getClass == getClass && other.asInstanceOf[UnrecognizedValue].value == value
  }

  override def hashCode: Int = getClass.getName.hashCode * 31 + value

  override def toString: String = s"${getClass.getSimpleName}($value)"
}
