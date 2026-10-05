package zga.codegen

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.SourceCodeInfo.Location
import com.google.protobuf.Descriptors.FileDescriptor

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

object Comments {

  private val locations: FileDescriptor => Map[Seq[Int], Location] = Memoize { f =>
    // iterated in reverse so that the first location for a path is kept, as a linear `find` would
    f.toProto.getSourceCodeInfo.getLocationList.asScala.reverseIterator
      .map(l => l.getPathList.asScala.map(_.intValue).toList -> l)
      .toMap
  }

  def header(f: FileDescriptor): Option[String] = {
    // The `syntax`/`edition` declaration is recorded at the same source path in
    // both proto2/proto3 and editions files, so this covers every syntax.
    locations(f)
      .get(Seq(FileDescriptorProto.SYNTAX_FIELD_NUMBER))
      .map(l => l.getLeadingDetachedCommentsList.asScala.toList.mkString("\n").trim)
      .filter(_.nonEmpty)
  }

  def comment(f: FileDescriptor, path: Seq[Int]): Option[String] = {
    locations(f).get(path).map(l => l.getLeadingComments + l.getTrailingComments).map(_.trim).filter(_.nonEmpty)
  }

  def formatAsMultiLineComment(comment: String): String = {
    val escaped = comment.replace("/*", "/\\*").replace("*/", "*\\/")
    val lines = escaped.split('\n').map(line => s" * $line")
    ("/*" +: lines :+ " */").mkString("\n")
  }

  def formatAsScaladoc(comment: String, params: ListMap[String, String]): String = {
    val lines = comment.split('\n').map(line => s"  * ${escape(line)}")
    val paramLines = params.toArray.flatMap { case (param, paramComment) =>
      Array(s"  * @param $param") ++ paramComment.split('\n').map(line => s"  *   ${escape(line)}")
    }
    ("/**" +: (lines ++ paramLines) :+ "  */").mkString("\n")
  }

  private def escape(s: String): String = {
    s.replace("&", "&amp;")
      .replace("/*", "/&#42;")
      .replace("*/", "*&#47;")
      .replace("@", "&#64;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\\", "&#92;")
  }
}
