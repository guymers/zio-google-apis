package zga.codegen

import com.google.api.AnnotationsProto
import com.google.api.HttpRule
import com.google.api.RoutingProto
import com.google.api.RoutingRule
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionLite

import java.util.Locale
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/**
 * Reads a method's routing parameters from the `google.api.routing` option,
 * falling back to the path variables of its `google.api.http` rule when the
 * option is absent.
 *
 * See
 * [[https://google.aip.dev/client-libraries/4222 AIP-4222: Routing headers]]
 * for the specification, and google/api/routing.proto for the annotation.
 */
object Routing {

  /**
   * A routing parameter, carrying the request message fields its `field` path
   * resolves to, so that the generator only has to format the accessors.
   */
  case class Parameter(
    field: String,
    pathTemplate: Option[Template],
    source: Source,
    fields: List[Descriptors.FieldDescriptor],
  )

  sealed trait Source
  object Source {
    case object Routing extends Source // `google.api.routing`
    case object Http extends Source // `google.api.http`
  }

  // typed as `ExtensionLite` so that `MethodOptions.getExtension`'s three overloads are not
  // ambiguous to the Scala compiler; the other two just delegate to it.
  private val RoutingExtension: ExtensionLite[DescriptorProtos.MethodOptions, RoutingRule] = RoutingProto.routing
  private val HttpExtension: ExtensionLite[DescriptorProtos.MethodOptions, HttpRule] = AnnotationsProto.http

  def parameters(method: Descriptors.MethodDescriptor): List[Parameter] = {
    val parameters = if (method.getOptions.hasExtension(RoutingExtension)) {
      routingParameters(method)
    } else httpParameters(method)
    // Routing is defined in terms of a single request message, which a client-streaming method does not have
    if (method.isClientStreaming) Nil else parameters
  }.additionalErrorContext(s"${method.getFullName}")

  private def routingParameters(method: Descriptors.MethodDescriptor): List[Parameter] = {
    val rule: RoutingRule = method.getOptions.getExtension(RoutingExtension)
    rule.getRoutingParametersList.asScala.toList.map { parameter =>
      val field = parameter.getField
      Parameter(
        field = field,
        pathTemplate = Option(parameter.getPathTemplate).filter(_.nonEmpty).map(Template.parse),
        source = Source.Routing,
        fields = resolveFields(method, field, Source.Routing),
      )
    }
  }

  private def httpParameters(method: Descriptors.MethodDescriptor): List[Parameter] = {
    val rule: HttpRule = method.getOptions.getExtension(HttpExtension)
    httpRules(rule).flatMap(rule => httpPathVariables(httpPath(rule))).distinct.map { field =>
      Parameter(
        field = field,
        pathTemplate = None,
        source = Source.Http,
        fields = resolveFields(method, field, Source.Http),
      )
    }
  }

  private def httpRules(rule: HttpRule): List[HttpRule] = {
    rule :: rule.getAdditionalBindingsList.asScala.toList.flatMap(httpRules)
  }

  private def httpPath(rule: HttpRule) = rule.getPatternCase match {
    case HttpRule.PatternCase.GET => rule.getGet
    case HttpRule.PatternCase.PUT => rule.getPut
    case HttpRule.PatternCase.POST => rule.getPost
    case HttpRule.PatternCase.DELETE => rule.getDelete
    case HttpRule.PatternCase.PATCH => rule.getPatch
    case HttpRule.PatternCase.CUSTOM => rule.getCustom.getPath
    case HttpRule.PatternCase.PATTERN_NOT_SET => ""
  }

  /**
   * The field paths bound by an http path such as `/v2/{name}:cancel` or
   * `/v2/{job.name=projects/&#42;}`, in order and without duplicates.
   */
  private[codegen] def httpPathVariables(path: String): List[String] = {
    @tailrec
    def loop(characters: List[Char], depth: Int, current: String, variables: List[String]): List[String] = {
      characters match {
        case Nil =>
          if (depth != 0) {
            throw IllegalArgumentException(s"unbalanced braces")
          }
          variables.reverse.distinct
        case '{' :: rest =>
          if (depth != 0) {
            throw IllegalArgumentException(s"nested variables are not supported")
          }
          loop(rest, 1, "", variables)
        case '}' :: rest =>
          if (depth == 0) {
            throw IllegalArgumentException(s"unbalanced braces")
          }
          val field = current.takeWhile(_ != '=')
          val name = Segment.Variable.Name.create(field).getOrElse {
            throw IllegalArgumentException(s"'$field' is not a variable name")
          }
          loop(rest, 0, "", name.value :: variables)
        case character :: rest =>
          loop(rest, depth, if (depth == 0) current else current + character, variables)
      }
    }

    loop(path.toList, 0, "", Nil)
  }.additionalErrorContext(s"invalid http path '$path'")

  /**
   * The fields named by a parameter, resolved against the method's request
   * message.
   *
   * A client-streaming method has no single request message, so its parameters
   * are discarded, and only the annotation itself is validated.
   */
  private def resolveFields(
    method: Descriptors.MethodDescriptor,
    field: String,
    source: Source,
  ): List[Descriptors.FieldDescriptor] = {
    if (method.isClientStreaming) Nil else resolveFieldPath(method.getInputType, field, source)
  }

  /**
   * The field the path names, and each message field leading to it, in order.
   */
  private def resolveFieldPath(
    descriptor: Descriptors.Descriptor,
    path: String,
    source: Source,
  ): List[Descriptors.FieldDescriptor] = {
    @tailrec
    def loop(
      current: Descriptors.Descriptor,
      names: List[String],
      fields: List[Descriptors.FieldDescriptor],
    ): List[Descriptors.FieldDescriptor] = {
      names match {
        case Nil => fields.reverse
        case name :: rest =>
          val field = current.findFieldByName(name)
          if (field == null) {
            throw IllegalArgumentException(s"${descriptor.getFullName} has no field '$name' for routing parameter '$path'")
          }
          if (field.isRepeated) {
            val kind = if (field.isMapField) "a map" else "repeated"
            throw IllegalArgumentException(s"${descriptor.getFullName}.${field.getName} is $kind, which cannot be used for routing parameter '$path'")
          }
          if (rest.nonEmpty && !field.isMessage) {
            throw IllegalArgumentException(s"${descriptor.getFullName}.${field.getName} is not a message, for routing parameter '$path'")
          }
          if (rest.isEmpty && !isRoutingValueType(field, source)) {
            val fieldType = field.getType.name.toLowerCase(Locale.ROOT)
            throw IllegalArgumentException(s"${descriptor.getFullName}.${field.getName} has type $fieldType, which cannot be used for routing parameter '$path'")
          }
          loop(if (rest.isEmpty) current else field.getMessageType, rest, field :: fields)
      }
    }

    loop(descriptor, path.split('.').toList, Nil)
  }

  /**
   * An explicit `google.api.routing` parameter matches its value against a path
   * template, so must be a string. An http path variable may also be an
   * integer, bool or enum. Other types have no agreed text form.
   */
  private def isRoutingValueType(field: Descriptors.FieldDescriptor, source: Source): Boolean = {
    field.getJavaType match {
      case Descriptors.FieldDescriptor.JavaType.STRING => true
      case Descriptors.FieldDescriptor.JavaType.INT | Descriptors.FieldDescriptor.JavaType.LONG |
          Descriptors.FieldDescriptor.JavaType.BOOLEAN | Descriptors.FieldDescriptor.JavaType.ENUM =>
        source == Source.Http
      case Descriptors.FieldDescriptor.JavaType.FLOAT | Descriptors.FieldDescriptor.JavaType.DOUBLE |
          Descriptors.FieldDescriptor.JavaType.BYTE_STRING | Descriptors.FieldDescriptor.JavaType.MESSAGE =>
        false
    }
  }

  case class Template(segments: ::[Segment])
  object Template {

    /**
     * Parses a `path_template`: `/`-delimited literal segments, `*` and `**`
     * wildcards, and `{name=pattern}` variables.
     */
    private[codegen] def parse(template: String): Template = {
      // AIP-4222: the last symbol in a path_template may be the delimiter, and is ignored
      val normalized = if (template.endsWith("/")) template.dropRight(1) else template
      val segments = splitSegments(normalized).map(Segment.parse(_, template)) match {
        case Nil => throw IllegalArgumentException(s"no segments")
        case head :: tail => scala.collection.immutable.::(head, tail)
      }
      // google/api/routing.proto requires a path_template to contain exactly one
      // named segment, which supplies the routing header key.
      val names = namedSegments(segments)
      if (names.length != 1) {
        throw IllegalArgumentException(s"expected exactly one named segment, found ${names.length}")
      }
      // AIP-4222: "A multi-segment wildcard must only appear as the final segment or make up the entire path_template."
      // Flattening the variable patterns puts a nested `**` in the position it actually matches at.
      if (flattenSegments(segments).dropRight(1).contains(Segment.Multi)) {
        throw IllegalArgumentException(s"'**' must be the final segment")
      }
      Template(segments)
    }.additionalErrorContext(s"invalid path template '$template'")

    /**
     * The template's segments with variable patterns expanded in place, so that
     * a wildcard's position is comparable with the other segments.
     */
    private def flattenSegments(segments: List[Segment]): List[Segment] = segments.flatMap {
      case Segment.Variable(_, pattern) => flattenSegments(pattern)
      case segment => segment :: Nil
    }

    private def namedSegments(segments: List[Segment]): List[Segment.Variable.Name] = segments.flatMap {
      case Segment.Variable(name, pattern) => name :: namedSegments(pattern)
      case _ => Nil
    }
  }

  sealed trait Segment
  object Segment {

    case object Single extends Segment

    case object Multi extends Segment

    case class Literal(value: String) extends Segment

    case class Variable(name: Variable.Name, pattern: List[Segment]) extends Segment
    object Variable {

      opaque type Name = String
      object Name {
        extension (name: Name) {
          def value: String = name
        }

        def create(name: String): Option[Name] = Option.when(name.matches("[A-Za-z0-9_.]+"))(name)
      }
    }

    /**
     * AIP-4231: a complex resource ID path segment joins two or more pattern
     * variables with a single `_`, `-`, `.` or `~` separator, e.g.
     * `customers/{customer}/feedItemTargets/{feed}~{feed_item}`.
     *
     * AIP-4222 forbids them in a routing path_template.
     */
    private val ComplexResourceId = """^\{[^{}]*\}[_\-\.~]\{[^{}]*\}(?:[_\-\.~]\{[^{}]*\})*$""".r

    /**
     * Parses one `/`-delimited segment of a `path_template`. `template` is the
     * whole template, used to report where an invalid segment came from.
     */
    private[codegen] def parse(segment: String, template: String): Segment = (segment match {
      case ComplexResourceId() =>
        throw IllegalArgumentException(s"'$segment' is a complex resource ID path segment")
      case "*" =>
        Single
      case "**" =>
        Multi
      case variable if variable.startsWith("{") && variable.endsWith("}") =>
        val inner = variable.substring(1, variable.length - 1)
        val equals = indexOfEquals(inner)
        if (equals < 0) {
          Variable(variableName(inner), List(Single))
        } else {
          val name = variableName(inner.substring(0, equals))
          val pattern = splitSegments(inner.substring(equals + 1))
          if (pattern.isEmpty) {
            throw IllegalArgumentException(s"'{$name=}' has an empty pattern")
          } else {
            Variable(name, pattern.map(parse(_, template)))
          }
        }
      case literal if literal.contains('{') || literal.contains('}') =>
        throw IllegalArgumentException(s"'$literal' mixes a variable with a literal segment, which is not supported")
      case literal =>
        if (literal.contains('*') || literal.contains('=')) {
          // AIP-4222: "A literal segment must not contain a symbol reserved in this syntax"
          throw IllegalArgumentException(s"literal segment '$literal' contains a reserved character")
        } else {
          Literal(literal)
        }
    }).additionalErrorContext(s"invalid path template '$template'")

    private def variableName(name: String) = Variable.Name.create(name).getOrElse {
      throw IllegalArgumentException(s"'$name' is not a variable name")
    }

    /**
     * The index of the `=` that separates a variable's name from its pattern,
     * ignoring any inside a nested pair of braces.
     */
    private def indexOfEquals(value: String): Int = {
      @tailrec def loop(index: Int, depth: Int): Int = {
        if (index >= value.length) {
          -1
        } else {
          value.charAt(index) match {
            case '{' => loop(index + 1, depth + 1)
            case '}' => loop(index + 1, depth - 1)
            case '=' if depth == 0 => index
            case _ => loop(index + 1, depth)
          }
        }
      }

      loop(0, 0)
    }
  }

  private def splitSegments(template: String): List[String] = {
    @tailrec
    def loop(characters: List[Char], depth: Int, current: String, segments: List[String]): List[String] = {
      characters match {
        case Nil =>
          if (depth != 0) {
            throw IllegalArgumentException(s"unbalanced braces")
          }
          if (current.nonEmpty) {
            (current :: segments).reverse
          } else if (segments.isEmpty) {
            // an empty template has no segments; callers decide whether that is an error
            Nil
          } else {
            throw IllegalArgumentException(s"empty segment")
          }
        case '{' :: rest => loop(rest, depth + 1, current + '{', segments)
        case '}' :: rest =>
          if (depth == 0) {
            throw IllegalArgumentException(s"unbalanced braces")
          }
          loop(rest, depth - 1, current + '}', segments)
        case '/' :: rest if depth == 0 =>
          if (current.isEmpty) {
            throw IllegalArgumentException(s"empty segment")
          }
          loop(rest, depth, "", current :: segments)
        case character :: rest => loop(rest, depth, current + character, segments)
      }
    }

    loop(template.toList, 0, "", Nil)
  }.additionalErrorContext(s"invalid path template '$template'")

  extension [V](v: => V) {
    private def additionalErrorContext(prefix: String): V = try {
      v
    } catch {
      case e: IllegalArgumentException => throw IllegalArgumentException(s"$prefix: ${e.getMessage}", e)
    }
  }
}
