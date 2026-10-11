package zga.common

/**
 * A message without fields. Generated as a plain class rather than a case
 * class, which has no fields to give its members any use, so every value of a
 * message is equal to every other.
 */
abstract class EmptyMessage {

  override def equals(other: Any): Boolean = other != null && other.getClass == getClass

  override def hashCode: Int = getClass.getName.hashCode

  override def toString: String = s"${getClass.getSimpleName}()"
}
