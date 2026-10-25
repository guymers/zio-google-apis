package zga.codegen

import com.google.protobuf.Descriptors

/**
 * Protobuf types that generated code represents as `java.time` types.
 *
 * Their codecs are written by hand in `common`.
 */
private[codegen] object JavaTimeType {

  private val scalaTypes = Map(
    "google.protobuf.Timestamp" -> "_root_.java.time.Instant",
    "google.protobuf.Duration" -> "_root_.java.time.Duration",
  )

  def scalaType(d: Descriptors.Descriptor): Option[String] = scalaTypes.get(d.getFullName)
}
