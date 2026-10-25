package zga.codegen

import com.google.api.RoutingParameter
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.SourceCodeInfo
import com.google.protobuf.Descriptors
import com.google.protobuf.compiler.PluginProtos
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.jdk.CollectionConverters.*
import scala.util.Try

object ZioGrpcGeneratorTest extends ZIOSpecDefault {
  import TestDescriptors.field
  import TestDescriptors.fileDescriptor
  import TestDescriptors.method
  import TestDescriptors.routingRule
  import TestDescriptors.serviceDescriptor

  private val options = ZioGrpcOptions(
    classPrefix = "Test",
    pkgName = "test",
    servicesOnly = Set.empty,
    routing = Map.empty,
  )

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
        val error = Try(ZioGrpcGenerator.validateClientNames(List(file))).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("all map to the generated name 'ThingClient'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'test.Thing'") && t.getMessage.contains("'test.ThingService'")))
      },
      test("rejects services of different protos that map to the same client name") {
        def protoFile(name: String, serviceName: String) = {
          val proto = serviceDescriptor(List(serviceName), method("GetThing"))
          proto.toProto.toBuilder.setName(name).build()
        }
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("a/service.proto")
          .addFileToGenerate("b/other.proto")
          .addProtoFile(protoFile("a/service.proto", "Thing"))
          .addProtoFile(protoFile("b/other.proto", "ThingService"))
          .setParameter("class_prefix=Test;pkg_name=test")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage.contains("all map to the generated name 'ThingClient'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'test.Thing'") && t.getMessage.contains("'test.ThingService'")))
      },
      test("rejects methods that map to the same constant name") {
        val file = serviceDescriptor(method("GetThing"), method("Get_Thing"))
        val error = Try(ZioGrpcGenerator.printServiceFile(options, file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("all map to the generated name 'METHOD_GET_THING'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'GetThing'") && t.getMessage.contains("'Get_Thing'")))
      },
      test("rejects two protos that generate the same output file") {
        def protoFile(name: String, serviceName: String) = {
          val proto = serviceDescriptor(List(serviceName), method("GetThing"))
          proto.toProto.toBuilder.setName(name).build()
        }
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("a/service.proto")
          .addFileToGenerate("b/service.proto")
          .addProtoFile(protoFile("a/service.proto", "Thing"))
          .addProtoFile(protoFile("b/service.proto", "Widget"))
          .setParameter("class_prefix=Test;pkg_name=test")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage.contains("both generate the Scala file 'test/service.proto.scala'"))) &&
        assertTrue(error.exists(t => t.getMessage.contains("'a/service.proto'") && t.getMessage.contains("'b/service.proto'")))
      },
      test("accepts a file whose services have distinct names") {
        val file = serviceDescriptor(List("Thing", "Widget"), method("GetThing"))
        assertTrue(Try(ZioGrpcGenerator.validateClientNames(List(file))).isSuccess)
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
      test("escapes a keyword oneof name") {
        val proto = serviceDescriptor(method("GetThing", routing = Some(routingRule("tag" -> None)))).toProto.toBuilder
        proto.getMessageTypeBuilderList.asScala.filter(_.getName == "Request").foreach(_.getOneofDeclBuilder(0).setName("type"))
        val output = ZioGrpcGenerator.printServiceFile(options, Descriptors.FileDescriptor.buildFrom(proto.build(), Array.empty))
        assertTrue(output.contains("request.`type`.collect"))
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
      test("sends a java.time DayOfWeek http path variable by its proto name") {
        val file = withDayOfWeek(serviceDescriptor(method("GetThing", http = Some("/v1/{day_of_week}"))))
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(output.contains("_root_.scala.Some(request.dayOfWeek.fold(\"DAY_OF_WEEK_UNSPECIFIED\")(_.toString))"))
      },
      test("passes the routing constant to the call") {
        val file = serviceDescriptor(
          method("StreamThings", serverStreaming = true, routing = Some(routingRule("name" -> Some("{name=*}")))),
        )
        val output = ZioGrpcGenerator.printServiceFile(options, file)
        assertTrue(
          output.contains(
            "_root_.zga.client.ClientCalls.serverStreaming(channel.channel, TestClient.Grpc.METHOD_STREAM_THINGS, opts, auth.metadata(channel.channel.authority, \"test.TestService\"), TestClient.Grpc.ROUTING_STREAM_THINGS, request)",
          ),
        )
      },
      test("passes no routing to the call of a method without routing") {
        val output = ZioGrpcGenerator.printServiceFile(options, serviceDescriptor(method("GetThing")))
        assertTrue(
          output.contains("_root_.zga.client.ClientCalls.unary(channel.channel, TestClient.Grpc.METHOD_GET_THING, opts, auth.metadata(channel.channel.authority, \"test.TestService\"), _root_.scala.Nil, request)"),
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
    suite("added routing")(
      test("routes a client-streaming method on its first request") {
        val file = serviceDescriptor(method("PutThings", clientStreaming = true))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("PutThings", "item.name" -> Some("{name=**}")), file)
        assertTrue(output.contains("request.item.flatMap")) &&
        assertTrue(
          output.contains(
            "_root_.zga.client.ClientCalls.clientStreaming(channel.channel, TestClient.Grpc.METHOD_PUT_THINGS, opts, auth.metadata(channel.channel.authority, \"test.TestService\"), TestClient.Grpc.ROUTING_PUT_THINGS, request)",
          ),
        )
      },
      test("routes a bidirectional method on its first request") {
        val file = serviceDescriptor(method("SyncThings", clientStreaming = true, serverStreaming = true))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("SyncThings", "name" -> None), file)
        assertTrue(
          output.contains(
            "_root_.zga.client.ClientCalls.bidirectional(channel.channel, TestClient.Grpc.METHOD_SYNC_THINGS, opts, auth.metadata(channel.channel.authority, \"test.TestService\"), TestClient.Grpc.ROUTING_SYNC_THINGS, request)",
          ),
        )
      },
      test("adds routing to a method that has none") {
        val file = serviceDescriptor(method("GetThing"))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("GetThing", "item.name" -> None), file)
        assertTrue(output.contains("\"item.name\","))
      },
      test("adds to a method's routing option") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> None))))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("GetThing", "item.name" -> None), file)
        assertTrue(output.indexOf("\"parent\",") >= 0) &&
        assertTrue(output.indexOf("\"parent\",") < output.indexOf("\"item.name\","))
      },
      test("replaces a client-streaming method's routing option") {
        val file = serviceDescriptor(
          method("PutThings", clientStreaming = true, routing = Some(routingRule("parent" -> None))),
        )
        val output = ZioGrpcGenerator.printServiceFile(withRouting("PutThings", "item.name" -> None), file)
        assertTrue(!output.contains("\"parent\",")) &&
        assertTrue(output.contains("\"item.name\","))
      },
      test("rejects routing that drops an http path variable") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{name=things/*}")))
        val error = Try(ZioGrpcGenerator.printServiceFile(withRouting("GetThing", "item.name" -> None), file)).failed.toOption
        assertTrue(
          error.exists(_.getMessage ==
            "test.TestService.GetThing: the routing plugin option replaces the google.api.http path variables, so must also route on 'name'"),
        )
      },
      test("replaces the http path variables with routing that also routes on them") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{name=things/*}")))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("GetThing", "name" -> Some("{name=things/*}"), "item.name" -> None), file)
        assertTrue(output.contains("\"name\",")) &&
        assertTrue(output.contains("_root_.zga.client.Routing.Segment.Literal(\"things\")")) &&
        assertTrue(output.contains("\"item.name\","))
      },
      // google/api/routing.proto allows a field in several routing parameters, each with its own template
      test("adds a template for a field the routing option already routes") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> None))))
        val output = ZioGrpcGenerator.printServiceFile(withRouting("GetThing", "parent" -> Some("{parent=**}")), file)
        assertTrue(output.contains("\"parent\",")) &&
        assertTrue(output.contains("_root_.zga.client.Routing.Segment.Variable(\"parent\""))
      },
      test("rejects a field the request does not have") {
        val file = serviceDescriptor(method("PutThings", clientStreaming = true))
        val error = Try(ZioGrpcGenerator.printServiceFile(withRouting("PutThings", "missing" -> None), file)).failed.toOption
        assertTrue(error.exists(_.getMessage.startsWith("test.TestService.PutThings: test.Request has no field 'missing'")))
      },
      test("rejects a method that is not generated") {
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("test/service.proto")
          .addProtoFile(fileDescriptor(List(method("GetThing")), withService = true).toProto)
          .setParameter("class_prefix=Test;pkg_name=test;routing=test.TestService.Missing:name")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage == "routing names 'test.TestService.Missing', which is not a method of a file to generate"))
      },
    ),
    suite("services_only")(
      test("generates only the service file") {
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("test/service.proto")
          .addProtoFile(fileDescriptor(List(method("GetThing")), withService = true).toProto)
          .setParameter("class_prefix=Test;pkg_name=test;services_only=test/service.proto")
          .build()

        val names = Plugin.generateRequest(request).getFileList.asScala.map(_.getName).toSet

        assertTrue(!names.contains("test/service.proto.scala")) &&
        assertTrue(names.contains("zga/client/test/service.proto.scala"))
      },
      test("generates messages for a file that is not services only") {
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("test/service.proto")
          .addProtoFile(fileDescriptor(List(method("GetThing")), withService = true).toProto)
          .setParameter("class_prefix=Test;pkg_name=test")
          .build()

        val names = Plugin.generateRequest(request).getFileList.asScala.map(_.getName).toSet

        assertTrue(names.contains("test/service.proto.scala")) &&
        assertTrue(names.contains("zga/client/test/service.proto.scala"))
      },
      test("rejects a services_only file that is not generated") {
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("test/service.proto")
          .addProtoFile(fileDescriptor(List(method("GetThing")), withService = true).toProto)
          .setParameter("class_prefix=Test;pkg_name=test;services_only=other.proto")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage == "services_only names 'other.proto', which is not a file to generate"))
      },
      test("rejects a services_only file without services") {
        val request = PluginProtos.CodeGeneratorRequest.newBuilder
          .addFileToGenerate("test/service.proto")
          .addProtoFile(fileDescriptor(Nil, withService = false).toProto)
          .setParameter("class_prefix=Test;pkg_name=test;services_only=test/service.proto")
          .build()

        val error = Try(Plugin.generateRequest(request)).failed.toOption

        assertTrue(error.exists(_.getMessage == "services_only names 'test/service.proto', which has no services"))
      },
    ),
  )

  private def withRouting(methodName: String, parameters: (String, Option[String])*) = {
    val routing = parameters.toList.map { (field, template) =>
      RoutingParameter.newBuilder.setField(field).setPathTemplate(template.getOrElse("")).build
    }
    options.copy(routing = Map(s"test.TestService.$methodName" -> routing))
  }

  /**
   * The service file with a `google.type.DayOfWeek` field added to its request.
   */
  private def withDayOfWeek(file: Descriptors.FileDescriptor) = {
    val proto = file.toProto.toBuilder.addDependency("google/type/dayofweek.proto")
    proto.getMessageTypeBuilderList.asScala
      .find(_.getName == "Request")
      .foreach { request =>
        val _ = request.addField(
          field("day_of_week", 16, FieldDescriptorProto.Type.TYPE_ENUM, typeName = Some(".google.type.DayOfWeek")),
        )
      }
    Descriptors.FileDescriptor.buildFrom(proto.build(), Array(com.google.`type`.DayOfWeek.getDescriptor.getFile))
  }
}
