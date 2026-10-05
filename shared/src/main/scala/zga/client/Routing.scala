package zga.client

import io.grpc.Metadata
import zio.Trace
import zio.ZIO

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
 * Support for the `google.api.routing` method option, and for the implicit
 * routing parameters of a method's `google.api.http` path variables when it has
 * no routing option.
 *
 * A routed method's parameters are extracted from the request and sent in the
 * `x-goog-request-params` metadata header so that Google's infrastructure can
 * route the request. See
 * [[https://google.aip.dev/client-libraries/4222 AIP-4222: Routing headers]]
 * for the specification, and google/api/routing.proto for the annotation.
 */
object Routing {

  /**
   * A single routing parameter.
   *
   * `extract` returns the `x-goog-request-params` key-value pairs the request
   * contributes, or `Nil` when the parameter does not apply to it.
   */
  case class Parameter[A](extract: A => List[(String, String)])

  val RequestParamsKey = Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER)

  /**
   * Adds the `x-goog-request-params` header to `headers`, unless it is already
   * present. The check and the write are a single atomic operation.
   */
  def setRequestParams[A](
    headers: SafeMetadata,
    request: A,
    parameters: List[Parameter[A]],
  )(using Trace): ZIO[Any, Nothing, Unit] = {
    requestParams(request, parameters).fold(ZIO.unit) { value =>
      headers.putIfAbsent(RequestParamsKey, value).unit
    }
  }

  /**
   * Returns the `x-goog-request-params` value for the given request.
   *
   * Parameters are evaluated in order, so a later parameter with the same
   * header key wins ("last one wins").
   */
  private[client] def requestParams[A](request: A, parameters: List[Parameter[A]]) = {
    val values = mutable.LinkedHashMap.empty[String, String]
    parameters.foreach(_.extract(request).foreach { case (key, value) => values.update(key, value) })
    Option.when(values.nonEmpty) {
      values.iterator.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
    }
  }

  /** A parameter that sends the whole field value, keyed by the field name. */
  private[zga] def parameter[A](field: String, fieldValue: A => Option[String]): Parameter[A] = {
    Parameter(request => fieldValue(request).filter(_.nonEmpty).toList.map(value => field -> value))
  }

  /** A parameter that extracts the value using a path template. */
  private[zga] def parameter[A](template: Template, fieldValue: A => Option[String]): Parameter[A] = {
    Parameter(request => fieldValue(request).filter(_.nonEmpty).toList.flatMap(value => extract(template, value)))
  }

  // a parsed `path_template`
  private[zga] case class Template(segments: List[Segment])

  private[zga] sealed trait Segment
  private[zga] object Segment {

    case object Single extends Segment // `*`: matches exactly one path segment

    case object Multi extends Segment // `**`: matches zero or more path segments

    case class Literal(value: String) extends Segment

    /** `{name}` or `{name=pattern}`: binds the matched value to `name`. */
    case class Variable(name: String, pattern: List[Segment]) extends Segment
  }

  /**
   * Extracts the named key-value pairs from a field value using a parsed path
   * template. Returns an empty list when the value does not match the template.
   */
  private[zga] def extract(template: Template, value: String): List[(String, String)] = {
    // a limit of -1 keeps trailing empty segments, so "projects/p/" does not
    // match `projects/*`
    matchSegments(template.segments, value.split("/", -1).toList).getOrElse(Nil)
  }

  // Matching is greedy and left-to-right: every wildcard and variable tries to
  // consume as many segments as possible first, backtracking when the rest of
  // the template does not match.
  private def matchSegments(template: List[Segment], value: List[String]): Option[List[(String, String)]] = {
    template match {
      case Nil =>
        if (value.isEmpty) Some(Nil) else None
      case Segment.Single :: rest =>
        value match {
          case head :: tail if head.nonEmpty => matchSegments(rest, tail)
          case _ => None
        }
      case Segment.Multi :: rest =>
        (value.length to 0 by -1).view.flatMap(count => matchSegments(rest, value.drop(count))).headOption
      case Segment.Literal(literal) :: rest =>
        value match {
          case head :: tail if head == literal => matchSegments(rest, tail)
          case _ => None
        }
      case Segment.Variable(name, pattern) :: rest =>
        // A variable must bind at least one segment. `**` can match zero
        // segments, but binding the variable to "" would only produce a useless
        // empty request param, so such a match is treated as no match.
        (value.length to 1 by -1).view
          .flatMap { count =>
            val prefix = value.take(count)
            matchSegments(pattern, prefix).flatMap { bindings =>
              matchSegments(rest, value.drop(count)).map { remaining =>
                (name -> prefix.mkString("/")) :: bindings ::: remaining
              }
            }
          }
          .headOption
    }
  }

  // https://github.com/googleapis/google-cloud-java/blob/gax/v2.86.0/sdk-platform-java/gax-java/gax/src/main/java/com/google/api/gax/rpc/RequestUrlParamsEncoder.java#L76
  private def encode(value: String) = {
    val builder = new StringBuilder
    value.getBytes(StandardCharsets.UTF_8).foreach { byte =>
      val character = (byte & 0xff).toChar
      if (isUnreserved(character)) {
        val _ = builder.append(character)
      } else {
        val _ = builder.append('%').append(hexDigit((byte >> 4) & 0xf)).append(hexDigit(byte & 0xf))
      }
    }
    builder.toString
  }

  private def isUnreserved(character: Char): Boolean = {
    (character >= 'a' && character <= 'z') ||
    (character >= 'A' && character <= 'Z') ||
    (character >= '0' && character <= '9') ||
    "-._~".contains(character)
  }

  private def hexDigit(value: Int): Char = "0123456789ABCDEF".charAt(value & 0xf)
}
