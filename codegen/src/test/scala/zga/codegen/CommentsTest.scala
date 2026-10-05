package zga.codegen

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.SourceCodeInfo
import com.google.protobuf.Descriptors
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.collection.immutable.ListMap

object CommentsTest extends ZIOSpecDefault {

  override val spec = suite("Comments")(
    suite("header")(
      test("no header when the syntax has no detached comment") {
        assertTrue(Comments.header(fileWithSyntaxComments()).isEmpty)
      },
      test("the header is the syntax detached comment") {
        assertTrue(Comments.header(fileWithSyntaxComments("Copyright")) == Some("Copyright"))
      },
    ),
    suite("scaladoc")(
      test("single line") {
        val scaladoc = Comments.formatAsScaladoc("A single line.", ListMap.empty)
        assertTrue(scaladoc ==
          """/**
            |  * A single line.
            |  */""".stripMargin)
      },
      test("multiple lines") {
        val scaladoc = Comments.formatAsScaladoc("First line,\nsecond line", ListMap.empty)
        assertTrue(scaladoc ==
          """/**
            |  * First line,
            |  * second line
            |  */""".stripMargin)
      },
      test("escapes html and comment delimiters") {
        val scaladoc = Comments.formatAsScaladoc("""matches \d+ & <a> @b */""", ListMap.empty)
        assertTrue(scaladoc.contains("  * matches &#92;d+ &amp; &lt;a&gt; &#64;b *&#47;"))
      },
      test("params") {
        val params = ListMap(
          "p1" -> "first param",
          "p2" -> "second param line 1\nline 2",
        )
        val scaladoc = Comments.formatAsScaladoc("Single line", params)
        assertTrue(scaladoc ==
          """/**
            |  * Single line
            |  * @param p1
            |  *   first param
            |  * @param p2
            |  *   second param line 1
            |  *   line 2
            |  */""".stripMargin)
      },
    ),
    suite("multi line comment")(
      test("single line") {
        assertTrue(Comments.formatAsMultiLineComment("A single line.") ==
          """/*
            | * A single line.
            | */""".stripMargin)
      },
      test("multiple lines") {
        assertTrue(Comments.formatAsMultiLineComment("First line,\nsecond line") ==
          """/*
            | * First line,
            | * second line
            | */""".stripMargin)
      },
      test("neutralises a comment terminator") {
        assertTrue(Comments.formatAsMultiLineComment("before */ after") ==
          """/*
            | * before *\/ after
            | */""".stripMargin)
      },
      test("neutralises a comment opener") {
        assertTrue(Comments.formatAsMultiLineComment("before /* after") ==
          """/*
            | * before /\* after
            | */""".stripMargin)
      },
    ),
  )

  private def fileWithSyntaxComments(comments: String*) = {
    val location = SourceCodeInfo.Location.newBuilder.addPath(FileDescriptorProto.SYNTAX_FIELD_NUMBER)
    comments.foreach(comment => location.addLeadingDetachedComments(comment))
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setSyntax("proto3")
      .setSourceCodeInfo(SourceCodeInfo.newBuilder.addLocation(location))
      .build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty)
  }
}
