package zga.common

import com.google.protobuf.Descriptors

import java.util.Locale

private[zga] object FieldNames {

  /**
   * Convert a proto field name into one that is more appropriate as Scala
   * parameter name.
   *
   * Uses the proto3 JSON name otherwise the field name with underscores removed
   * and the first character uncapitalized.
   *
   * A JSON name with no lower case letters is an acronym or an initialism, so
   * it is lower cased entirely: `RAM` becomes `ram` rather than `rAM`.
   *
   * A JSON name that is not a valid identifier (`_1foo` has JSON name `1foo`)
   * keeps the proto name.
   */
  def toScalaName(field: Descriptors.FieldDescriptor): String = {
    scalaName(field.getJsonName, field.getName)
  }

  /**
   * A oneof declaration has no JSON name, so one is derived from its proto name
   * the way protobuf derives a field's.
   */
  def toScalaName(oneof: Descriptors.OneofDescriptor): String = {
    scalaName(toJsonName(oneof.getName), oneof.getName)
  }

  private def scalaName(jsonName: String, protoName: String): String = {
    if (!isScalaIdentifier(jsonName)) {
      protoName
    } else if (jsonName.exists(_.isLower)) {
      Character.toLowerCase(jsonName.head).toString + jsonName.tail
    } else {
      jsonName.toLowerCase(Locale.ROOT)
    }
  }

  /**
   * Removes the underscores of a lower snake case proto name and capitalizes
   * the character that followed each one, as protobuf's `ToJsonName` does.
   */
  private def toJsonName(name: String): String = {
    val builder = new StringBuilder(name.length)
    var capitalize = false
    var i = 0
    while (i < name.length) {
      val character = name.charAt(i)
      if (character == '_') {
        capitalize = true
      } else if (capitalize) {
        builder.append(Character.toUpperCase(character))
        capitalize = false
      } else {
        builder.append(character)
      }
      i += 1
    }
    builder.toString
  }

  private def isScalaIdentifier(name: String): Boolean = {
    name.headOption.exists(character => character.isLetter || character == '_') &&
    name.forall(character => character.isLetterOrDigit || character == '_')
  }
}
