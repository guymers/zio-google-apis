package zga.codegen

import com.google.protobuf.Descriptors

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/**
 * The protobuf enums that generated code represents as `java.time` enums. Each
 * proto value is matched to the `java.time` value of the same name. The proto
 * enum's unspecified value has no counterpart, so it is a `None`.
 *
 * A `java.time` enum is never extended, so reading a number it has no value for
 * fails rather than becoming a `None` that would be written back as the
 * unspecified value. That includes the `UNKNOWN` value of Ads enums, which the
 * server returns for a value newer than the API version.
 *
 * An enum versioned with its API is matched by a pattern of any version, so it
 * stays mapped when the symlinked protos are updated to a new version.
 */
private[codegen] enum JavaEnumType(
  val protoFullName: Regex,
  javaEnum: Class[? <: java.lang.Enum[?]],
  val unknownValue: Option[String],
) {

  case DayOfWeek extends JavaEnumType(
      raw"google\.type\.DayOfWeek".r,
      classOf[java.time.DayOfWeek],
      None,
    )

  case Month extends JavaEnumType(
      raw"google\.type\.Month".r,
      classOf[java.time.Month],
      None,
    )

  case MerchantWeekday extends JavaEnumType(
      raw"google\.shopping\.merchant\.accounts\.v\d+\.BusinessDayConfig\.Weekday".r,
      classOf[java.time.DayOfWeek],
      None,
    )

  case AdsDayOfWeek extends JavaEnumType(
      raw"google\.ads\.googleads\.v\d+\.enums\.DayOfWeekEnum\.DayOfWeek".r,
      classOf[java.time.DayOfWeek],
      Some("UNKNOWN"),
    )

  case AdsMonthOfYear extends JavaEnumType(
      raw"google\.ads\.googleads\.v\d+\.enums\.MonthOfYearEnum\.MonthOfYear".r,
      classOf[java.time.Month],
      Some("UNKNOWN"),
    )

  def scalaType: String = s"_root_.${javaEnum.getName}"

  /**
   * The names of the `java.time` values in ordinal order.
   */
  def javaValues: List[String] = javaEnum.getEnumConstants.toList.map(_.name)
}

private[codegen] object JavaEnumType {

  /**
   * The `java.time` type of the enum. Only an open enum can be mapped, as a
   * closed enum's generated reading treats `None` as an unknown number to drop,
   * while a mapped enum's `None` is the unspecified value.
   */
  def of(e: Descriptors.EnumDescriptor): Option[JavaEnumType] = {
    val found = values.find(_.protoFullName.matches(e.getFullName))
    found.foreach { t =>
      if (e.isClosed) {
        throw IllegalArgumentException(s"${e.getFullName} is a closed enum, which cannot be mapped to ${t.scalaType}")
      }
    }
    found
  }

  def of(f: Descriptors.FieldDescriptor): Option[JavaEnumType] =
    Option.when(f.getJavaType == Descriptors.FieldDescriptor.JavaType.ENUM)(f.getEnumType).flatMap(of(_))

  /**
   * The object that converts between the enum's numbers and the `java.time`
   * enum, replacing the generated enum. Codegen fails unless the proto values,
   * other than the unspecified and unknown values, are the `java.time` values.
   * A `java.time` value's number is looked up by its ordinal.
   */
  def printCodec(e: Descriptors.EnumDescriptor, t: JavaEnumType): String = {
    val comment = e.comment.map(Comments.formatAsScaladoc(_, ListMap.empty)).getOrElse("")
    val mapped = e.getValues.asScala.toList
      .filter(value => value.getNumber != 0 && !t.unknownValue.contains(value.getName))
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
