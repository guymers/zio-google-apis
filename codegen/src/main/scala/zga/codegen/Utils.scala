package zga.codegen

import java.util.Locale

object Utils {

  // Scala 3 hard keywords. These cannot be used as identifiers, so generated
  // field and method names are backtick-quoted when they collide with one.
  // Soft keywords (`extension`, `inline`, `opaque`, `open`, `using`, ...) are
  // deliberately omitted: they remain valid identifiers.
  private val ScalaKeywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
  )

  def escapeScalaKeyword(str: String): String = {
    if (ScalaKeywords.contains(str)) s"`$str`" else str
  }

  def escapeScalaString(str: String): String = {
    // a proto string may contain a control character
    val escaped = str.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character => character.toString
    }
    "\"" + escaped + "\""
  }

  def rootQualified(name: String): String = {
    if (name.isEmpty) "_root_" else s"_root_.$name"
  }

  /**
   * Maps a proto package (`java_package` when set) into the `zga.google`
   * namespace to avoid conflicts with Java proto artifacts.
   */
  def generatedPackageParts(pkg: String): List[String] = {
    pkg.split('.').filter(_.nonEmpty).toList match {
      case "com" :: "google" :: rest => "zga" :: "google" :: rest
      case "google" :: rest => "zga" :: "google" :: rest
      case parts => parts
    }
  }

  def snakeCaseToCamelCase(value: String): String = {
    value.split("[^A-Za-z0-9]").filter(_.nonEmpty).map { part =>
      part.headOption.fold("")(_.toUpper.toString) + part.drop(1).toLowerCase(Locale.ROOT)
    }.mkString
  }

  /**
   * Upper cases the first character of each word of a proto name, leaving the
   * rest of the word alone, so `revision_reason` and `revisionReason` both
   * become `RevisionReason`.
   */
  def pascalCase(value: String): String = {
    value.split("[^A-Za-z0-9]").filter(_.nonEmpty).map { part =>
      part.headOption.fold("")(_.toUpper.toString) + part.drop(1)
    }.mkString
  }
}
