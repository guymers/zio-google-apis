package zga.codegen

import com.google.protobuf.Descriptors

import scala.jdk.CollectionConverters.*

/**
 * Protobuf enums that generated code represents as `java.time` enums.
 *
 * The proto enum's unspecified value has no counterpart, so it is represented
 * as `None`.
 *
 * Unrecognized values are an error, all enums that are replaced should never
 * have new values added to them.
 */
private[codegen] enum JavaEnumType(
  val protoFullName: String,
  javaEnum: Class[? <: java.lang.Enum[?]],
) {

  case DayOfWeek extends JavaEnumType("google.type.DayOfWeek", classOf[java.time.DayOfWeek])

  case Month extends JavaEnumType("google.type.Month", classOf[java.time.Month])

  def scalaType: String = s"_root_.${javaEnum.getName}"

  /**
   * The names of the `java.time` values in ordinal order.
   */
  def javaValues: List[String] = javaEnum.getEnumConstants.toList.map(_.name)
}
private[codegen] object JavaEnumType {

  def of(e: Descriptors.EnumDescriptor): Option[JavaEnumType] = {
    val found = values.find(_.protoFullName == e.getFullName)
    found.foreach { t =>
      if (e.isClosed) {
        throw IllegalArgumentException(s"${e.getFullName} is a closed enum, which cannot be mapped to ${t.scalaType}")
      }
    }
    found
  }

  def of(f: Descriptors.FieldDescriptor): Option[JavaEnumType] = {
    Option.when(f.getJavaType == Descriptors.FieldDescriptor.JavaType.ENUM)(f.getEnumType).flatMap(of(_))
  }

  def printCodec(e: Descriptors.EnumDescriptor, t: JavaEnumType, comment: String): String = {
    val mapped = e.getValues.asScala.toList.filter(_.getNumber != 0)
    val mappedNames = mapped.map(_.getName)
    if (mappedNames.sorted != t.javaValues.sorted) {
      throw IllegalArgumentException(
        s"${e.getFullName} has the values ${mappedNames.mkString(", ")}, rather than the ${t.scalaType} values",
      )
    }

    val fromValueCases = mapped.map { value =>
      s"      case ${value.getNumber} => _root_.scala.Some(${t.scalaType}.${value.getName})"
    }
    val numbers = t.javaValues.map(name => mapped.find(_.getName == name).get.getNumber)
    val unrecognized = Utils.escapeScalaString(s"${e.getFullName} has no ${t.scalaType} value numbered ")

    s"""
      |$comment
      |object ${Utils.escapeScalaKeyword(e.getName)} {
      |  private val numbers = _root_.scala.Array[_root_.scala.Int](${numbers.mkString(", ")})
      |
      |  def fromValue(value: _root_.scala.Int): _root_.scala.Option[${t.scalaType}] =
      |    value match {
      |      case 0 => _root_.scala.None
      |${fromValueCases.mkString("\n")}
      |      case _ => throw new _root_.com.google.protobuf.InvalidProtocolBufferException($unrecognized + value)
      |    }
      |
      |  def value(value: _root_.scala.Option[${t.scalaType}]): _root_.scala.Int =
      |    value match {
      |      case _root_.scala.Some(v) => numbers(v.ordinal)
      |      case _root_.scala.None => 0
      |    }
      |}
      |""".stripMargin
  }
}
