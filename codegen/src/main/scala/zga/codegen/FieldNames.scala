package zga.codegen

import com.google.protobuf.Descriptors

import java.util.Locale

private[codegen] object FieldNames {

  def toScalaName(field: Descriptors.FieldDescriptor): String = scalaName(field.getName)

  def toScalaName(oneof: Descriptors.OneofDescriptor): String = scalaName(oneof.getName)

  /**
   * Convert a proto field name into one that is more appropriate as a Scala
   * parameter name.
   *
   * Uses the field name with underscores removed and the first character
   * uncapitalized.
   *
   * A camel cased name with no lower case letters is an acronym or an
   * initialism, so it is lower cased entirely: `RAM` becomes `ram` rather than
   * `rAM`.
   *
   * A camel cased name that is not a valid identifier (`_1foo` becomes `1foo`)
   * keeps the proto name.
   */
  private def scalaName(protoName: String) = {
    val camelCased = toCamelCase(protoName)
    if (!isScalaIdentifier(camelCased)) {
      protoName
    } else if (camelCased.exists(_.isLower)) {
      Character.toLowerCase(camelCased.head).toString + camelCased.tail
    } else {
      camelCased.toLowerCase(Locale.ROOT)
    }
  }

  private def toCamelCase(name: String): String = {
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

  def isScalaIdentifier(name: String): Boolean = {
    name.headOption.exists(character => character.isLetter || character == '_') &&
    name.forall(character => character.isLetterOrDigit || character == '_')
  }
}
