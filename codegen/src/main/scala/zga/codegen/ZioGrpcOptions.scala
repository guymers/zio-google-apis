package zga.codegen

import com.google.api.RoutingParameter

/**
 * @param routing
 *   routing parameters by method full name, for routing the proto does not
 *   declare; the Google client libraries add these by hand, for example to
 *   route a client-streaming method on its first message
 */
case class ZioGrpcOptions(
  classPrefix: String,
  pkgName: String,
  servicesOnly: Set[String],
  routing: Map[String, List[RoutingParameter]],
)
object ZioGrpcOptions {

  private val SupportedOptions = Set("class_prefix", "pkg_name", "services_only", "routing")

  def fromStr(str: String): Either[String, Option[ZioGrpcOptions]] = {
    if (str.nonEmpty) {
      val options = str.split(';').filter(_.nonEmpty).map { option =>
        val parts = option.split('=')
        parts.headOption.getOrElse("") -> parts.drop(1).mkString("=")
      }.toMap
      val unknown = options.keySet.diff(SupportedOptions)
      if (unknown.nonEmpty) {
        Left(s"invalid grpc options string: $str, unknown options: ${unknown.toList.sorted.mkString(", ")}")
      } else {
        for {
          classPrefix <- options
            .get("class_prefix")
            .filter(_.nonEmpty)
            .toRight(s"invalid grpc options string: $str, missing class_prefix")
          pkgName <- options
            .get("pkg_name")
            .filter(_.nonEmpty)
            .toRight(s"invalid grpc options string: $str, missing pkg_name")
          servicesOnly <- options.get("services_only") match {
            case None => Right(Set.empty[String])
            case Some(value) =>
              val files = value.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
              if (files.isEmpty) Left(s"invalid grpc options string: $str, missing services_only")
              else Right(files)
          }
          routing <- options.get("routing") match {
            case None => Right(Map.empty[String, List[RoutingParameter]])
            case Some(value) => parseRouting(value).left.map(err => s"invalid grpc options string: $str, $err")
          }
        } yield Some(apply(classPrefix = classPrefix, pkgName = pkgName, servicesOnly = servicesOnly, routing = routing))
      }
    } else {
      Right(None)
    }
  }

  /**
   * Parses `method:field` and `method:field:path_template` entries separated by
   * `,`, where `method` is the full name of the method.
   */
  private def parseRouting(value: String): Either[String, Map[String, List[RoutingParameter]]] = {
    val entries = value.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList
    if (entries.isEmpty) {
      Left("missing routing")
    } else {
      val (errors, parameters) = entries.partitionMap { entry =>
        entry.split(":", 3).toList match {
          case method :: field :: template if (method :: field :: template).forall(_.nonEmpty) =>
            Right(method -> RoutingParameter.newBuilder.setField(field).setPathTemplate(template.headOption.getOrElse("")).build)
          case _ =>
            Left(s"invalid routing '$entry'")
        }
      }
      errors.headOption.toLeft(parameters.groupMap(_._1)(_._2))
    }
  }
}
