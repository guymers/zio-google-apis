package zga.codegen

case class ZioGrpcOptions(
  classPrefix: String,
  pkgName: String,
)
object ZioGrpcOptions {

  private val SupportedOptions = Set("class_prefix", "pkg_name")

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
        } yield Some(apply(classPrefix = classPrefix, pkgName = pkgName))
      }
    } else {
      Right(None)
    }
  }
}
