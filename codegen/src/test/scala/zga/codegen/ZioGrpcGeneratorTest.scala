package zga.codegen

import com.google.protobuf.DescriptorProtos.SourceCodeInfo
import com.google.protobuf.compiler.PluginProtos
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.util.Try

object ZioGrpcGeneratorTest extends ZIOSpecDefault {
  import TestDescriptors.fileDescriptor
  import TestDescriptors.method
  import TestDescriptors.routingRule
  import TestDescriptors.serviceDescriptor

  private val options = ZioGrpcOptions(classPrefix = "Test", pkgName = "test")

  override val spec = suite("ZioGrpcGenerator")(
    suite("file names")(
      test("namespaces the client file by pkg_name") {
        val file = serviceDescriptor(method("GetThing"))
        assertTrue(ZioGrpcGenerator.fileName(options, file) == "zga/client/test/service.proto.scala")
      },
      test("detects a service") {
        assertTrue(ZioGrpcGenerator.hasServices(serviceDescriptor(method("GetThing"))))
      },
      test("detects a message-only file") {
        assertTrue(!ZioGrpcGenerator.hasServices(fileDescriptor(Nil, withService = false)))
      },
    ),
    suite("method names")(
      test("escapes Scala keywords") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("Export"), method("Import")))
        assertTrue(output.contains("def `export`(")) &&
        assertTrue(output.contains("def `import`("))
      },
      test("lowercases the first letter") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(output.contains("def getThing("))
      },
      test("qualifies the auth type with _root_") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(output.contains("_root_.zga.client.test.auth.TestAuthentication"))
      },
      test("qualifies scala types with _root_") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(output.contains("deadline: => _root_.scala.Option[_root_.io.grpc.Deadline] = _root_.scala.None")) &&
        assertTrue(output.contains("_root_.zio.ZIO[_root_.scala.Any, ")) &&
        assertTrue(output.contains("_root_.zio.ZLayer[_root_.scala.Any, _root_.scala.Nothing, ")) &&
        assertTrue("(?<![.\\w])(Option|None|Any|Nothing|Int)(?!\\w)".r.findFirstIn(output).isEmpty)
      },
      test("passes the channel authority and service name to authentication") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(output.contains("""auth.metadata(channel.channel.authority, "test.TestService")"""))
      },
    ),
    suite("name collisions")(
      test("rejects services that map to the same client name") {
        val file = serviceDescriptor(List("Thing", "ThingService"), method("GetThing"))
        val error = Try(ZioGrpcGenerator.printServiceFile(options, file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("all map to the generated name 'ThingClient'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'Thing'") && t.getMessage.contains("'ThingService'")))
      },
      test("rejects methods that map to the same constant name") {
        val file = serviceDescriptor(method("GetThing"), method("Get_Thing"))
        val error = Try(ZioGrpcGenerator.printServiceFile(options, file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("all map to the generated name 'METHOD_GET_THING'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'GetThing'") && t.getMessage.contains("'Get_Thing'")))
      },
      test("rejects two protos that generate the same output file") {
        def protoFile(name: String) = {
          val proto = fileDescriptor(List(method("GetThing")), withService = true)
          proto.toProto.toBuilder.setName(name).build()
        }
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("a/service.proto")
          .addFileToGenerate("b/service.proto")
          .addProtoFile(protoFile("a/service.proto"))
          .addProtoFile(protoFile("b/service.proto"))
          .setParameter("class_prefix=Test;pkg_name=test")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage.contains("both generate the Scala file 'test/service.proto.scala'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'a/service.proto'") && t.getMessage.contains("'b/service.proto'")))
      },
      test("accepts a file whose services have distinct names") {
        val file = serviceDescriptor(List("Thing", "Widget"), method("GetThing"))
        assertTrue(Try(ZioGrpcGenerator.printServiceFile(options, file)).isSuccess)
      },
    ),
    suite("comments")(
      test("emits service and method scaladoc") {
        val sourceInfo = SourceCodeInfo.newBuilder
          .addLocation(SourceCodeInfo.Location.newBuilder.addPath(6).addPath(0).setLeadingComments("A test service."))
          .addLocation(
            SourceCodeInfo.Location.newBuilder
              .addPath(6)
              .addPath(0)
              .addPath(2)
              .addPath(0)
              .setLeadingComments("Gets a thing."),
          )
          .build()
        val file = fileDescriptor(List(method("GetThing")), withService = true, sourceCodeInfo = Some(sourceInfo))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("  * A test service.")) &&
        assertTrue(output.contains("  * Gets a thing."))
      },
    ),
    suite("routing")(
      test("emits a routing constant for a nested field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("item.name" -> None))))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("ROUTING_GET_THING")) &&
        assertTrue(output.contains("request.item.flatMap"))
      },
      test("emits the camelCase accessor for a snake_case routing field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("table_name" -> None))))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("\"table_name\",")) &&
        assertTrue(output.contains("request.tableName"))
      },
      test("emits the parsed template of an explicit routing parameter") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> Some("{project=projects/*}/**")))))
        // scalafmt wraps the generated expression, so compare it without whitespace
        val flat = ZioGrpcGenerator.printServiceFile(options, file).filterNot(_.isWhitespace)
        assertTrue(
          flat.contains(
            """_root_.zga.client.Routing.Template(_root_.scala.List(_root_.zga.client.Routing.Segment.Variable("project",_root_.scala.List(_root_.zga.client.Routing.Segment.Literal("projects"),_root_.zga.client.Routing.Segment.Single)),_root_.zga.client.Routing.Segment.Multi))""",
          ),
        )
      },
      test("reaches a oneof field through its case") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("tag" -> None))))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("request.selector.collect")) &&
        assertTrue(output.contains("case _root_.test.Request.Selector.Tag(value) => value"))
      },
      test("reaches a oneof http path variable through its case") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{tag}")))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("request.selector.collect"))
      },
      test("falls back to the http path variables") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{item.name=things/*}:get")))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("ROUTING_GET_THING")) &&
        assertTrue(output.contains("\"item.name\",")) &&
        assertTrue(output.contains("request.item.flatMap(value => _root_.scala.Some(value.name))")) &&
        assertTrue(!output.contains("Template("))
      },
      test("an explicit routing option replaces the http path variables") {
        val file = serviceDescriptor(
          method("GetThing", routing = Some(routingRule("item.name" -> None)), http = Some("/v1/{name=things/*}")),
        )
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("\"item.name\",")) &&
        assertTrue(!output.contains("\"name\","))
      },
      test("formats integer http path variables") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{count}/{size}")))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("_root_.scala.Some(_root_.java.lang.Long.toUnsignedString(request.count))")) &&
        assertTrue(output.contains("_root_.scala.Some(request.size.toString)"))
      },
      test("sends an open enum http path variable by name, or number when unrecognized") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{kind}")))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("case unrecognized: _root_.test.Kind.Unrecognized => unrecognized.value.toString")) &&
        assertTrue(output.contains("case recognized: _root_.test.Kind.Recognized => recognized.toString"))
      },
      test("wraps the routing metadata in a stream for a server-streaming method") {
        val file = serviceDescriptor(
          method("StreamThings", serverStreaming = true, routing = Some(routingRule("name" -> Some("{name=*}")))),
        )
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("ROUTING_STREAM_THINGS")) &&
        assertTrue(
          output.contains("_root_.zio.stream.ZStream.fromZIO(_root_.zga.client.Routing.setRequestParams(headers, request"),
        )
      },
      test("escapes a control character in a template literal") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("name" -> Some("{name=projects/a\nb}")))))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        // a raw newline would be emitted into a Scala string literal
        assertTrue(output.contains("Literal(\"a\\nb\")")) &&
        assertTrue(!output.contains("Literal(\"a\nb\")"))
      },
      test("omits the routing constant when there are no parameters") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(!output.contains("ROUTING_GET_THING"))
      },
    ),
  )
}
