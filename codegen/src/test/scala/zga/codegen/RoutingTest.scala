package zga.codegen

import TestDescriptors.getRule
import TestDescriptors.method
import TestDescriptors.routingRule
import TestDescriptors.serviceDescriptor
import com.google.api.HttpRule
import com.google.protobuf.Descriptors
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.util.Try

object RoutingTest extends ZIOSpecDefault {

  private val single = Routing.Segment.Single
  private val multi = Routing.Segment.Multi

  private def literal(value: String): Routing.Segment = Routing.Segment.Literal(value)

  private def validName(value: String): Routing.Segment.Variable.Name = {
    Routing.Segment.Variable.Name.create(value).getOrElse {
      throw IllegalArgumentException(s"'$value' is not a valid variable name")
    }
  }

  private def variable(name: String, pattern: Routing.Segment*): Routing.Segment = {
    Routing.Segment.Variable(validName(name), pattern.toList)
  }

  private def template(segment: Routing.Segment, rest: Routing.Segment*): Routing.Template = {
    Routing.Template(::(segment, rest.toList))
  }

  private def parametersOf(file: Descriptors.FileDescriptor) = {
    Routing.parameters(file.getServices.get(0).getMethods.get(0))
  }

  /** The resolved request fields a routing parameter for `name` carries. */
  private def fieldsOf(file: Descriptors.FileDescriptor, name: String) = {
    List(file.findMessageTypeByName("Request").findFieldByName(name))
  }

  override val spec = suite("Routing")(
    suite("Template.parse")(
      test("a variable matching everything") {
        assertTrue(Routing.Template.parse("{table_name=**}") == template(variable("table_name", multi)))
      },
      test("a variable without a pattern matches one segment") {
        assertTrue(Routing.Template.parse("{name}") == template(variable("name", single)))
      },
      // AIP-4222: "`{key}` ... defaults to a template of `*`"
      test("a variable without a pattern is equivalent to `{key=*}`") {
        assertTrue(Routing.Template.parse("projects/{parent}") == Routing.Template.parse("projects/{parent=*}")) &&
        assertTrue(
          Routing.Template.parse("projects/{parent}") ==
            template(literal("projects"), variable("parent", single)),
        )
      },
      // AIP-4222: "The last symbol in a path_template may be a delimiter - it will be ignored."
      test("ignores a trailing delimiter") {
        assertTrue(Routing.Template.parse("projects/{parent=*}/") == template(literal("projects"), variable("parent", single))) &&
        assertTrue(Routing.Template.parse("{parent=**}") == Routing.Template.parse("{parent=**}/"))
      },
      // AIP-4222: "`**` ... must only appear as the final segment or make up the entire path_template"
      test("a multi-segment wildcard makes up an entire template") {
        assertTrue(Routing.Template.parse("{table_name=**}") == template(variable("table_name", multi)))
      },
      test("a multi-segment wildcard is the final segment") {
        assertTrue(
          Routing.Template.parse("projects/{parent=*}/**") ==
            template(literal("projects"), variable("parent", single), multi),
        )
      },
      test("literals, wildcards and variables") {
        assertTrue(
          Routing.Template.parse("projects/*/locations/{location=*}/**") ==
            template(literal("projects"), single, literal("locations"), variable("location", single), multi),
        )
      },
      test("a variable pattern containing slashes") {
        assertTrue(
          Routing.Template.parse("{bucket=projects/*/buckets/*}/objects/**") ==
            template(variable("bucket", literal("projects"), single, literal("buckets"), single), literal("objects"), multi),
        )
      },
      test("rejects unbalanced braces") {
        assertTrue(Try(Routing.Template.parse("projects/*/locations/{location=*")).isFailure)
      },
      test("rejects a closing brace without an opening brace") {
        assertTrue(Try(Routing.Template.parse("projects/*/locations/location=*}")).isFailure)
      },
      test("rejects an unnamed variable") {
        assertTrue(Try(Routing.Template.parse("projects/{=*}")).isFailure)
      },
      test("rejects an empty pattern") {
        assertTrue(Try(Routing.Template.parse("projects/{name=}")).isFailure)
      },
      test("rejects an empty template") {
        assertTrue(Try(Routing.Template.parse("")).isFailure)
      },
      test("rejects a template with no named segment") {
        assertTrue(Try(Routing.Template.parse("projects/*")).isFailure)
      },
      test("rejects a template with two named segments") {
        assertTrue(Try(Routing.Template.parse("{a=*}/{b=*}")).isFailure)
      },
      test("rejects a template with a nested named segment") {
        assertTrue(Try(Routing.Template.parse("{a={b=*}}")).isFailure)
      },
      test("rejects a variable mixed with a literal segment") {
        assertTrue(Try(Routing.Template.parse("projects/{name=*}:cancel")).isFailure)
      },
      test("rejects a leading slash") {
        assertTrue(Try(Routing.Template.parse("/projects/*")).isFailure)
      },
      test("rejects an empty segment") {
        assertTrue(Try(Routing.Template.parse("projects//locations/*")).isFailure)
      },
      test("rejects a variable name with a space") {
        assertTrue(Try(Routing.Template.parse("projects/{a b=*}")).isFailure)
      },
      test("rejects a variable name with a slash") {
        assertTrue(Try(Routing.Template.parse("projects/{a/b=*}")).isFailure)
      },
      // AIP-4222: "A multi-segment wildcard must only appear as the final segment
      // or make up the entire path_template"
      test("rejects a multi-segment wildcard that is not the final segment") {
        assertTrue(Try(Routing.Template.parse("projects/*/**/{name=*}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("**/{name=*}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("{name=**/things}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("{name=*}/**/things")).isFailure)
      },
      test("accepts a multi-segment wildcard as the final segment or the whole template") {
        assertTrue(
          Routing.Template.parse("{table_name=projects/*/instances/*/**}") ==
            template(variable("table_name", literal("projects"), single, literal("instances"), single, multi)),
        ) &&
        assertTrue(Routing.Template.parse("{routing_id=**}") == template(variable("routing_id", multi)))
      },
      // AIP-4222: "A literal segment must not contain a symbol reserved in this syntax"
      test("rejects a reserved character in a literal segment") {
        assertTrue(Try(Routing.Template.parse("projects/*x/{name=*}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("projects/x*y/{name=*}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("projects/a=b/{name=*}")).isFailure)
      },
      // AIP-4222: "A segment must not represent a complex resource ID as
      // described in AIP-4231", e.g. the AIP-4231 `FeedItemTarget` pattern
      test("rejects a complex resource ID path segment") {
        val error = Try(Routing.Template.parse("customers/{customer}/feedItemTargets/{feed}~{feed_item}")).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("complex resource ID"))) &&
        assertTrue(Try(Routing.Template.parse("{feed}~{feed_item}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("{a=*}-{b=*}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("projects/{a}_{b}")).isFailure) &&
        assertTrue(Try(Routing.Template.parse("{a}.{b}")).isFailure)
      },
    ),
    suite("Variable.Name.create")(
      // AIP-4222: a variable name is a field path, so letters, digits, `_` and `.`
      test("creates a name from letters, digits, underscores and dots") {
        assertTrue(Routing.Segment.Variable.Name.create("job.name").map(_.value) == Some("job.name")) &&
        assertTrue(Routing.Segment.Variable.Name.create("table_name").map(_.value) == Some("table_name"))
      },
      test("rejects a name with any other character, or no characters") {
        assertTrue(Routing.Segment.Variable.Name.create("").isEmpty) &&
        assertTrue(Routing.Segment.Variable.Name.create("a b").isEmpty) &&
        assertTrue(Routing.Segment.Variable.Name.create("a/b").isEmpty) &&
        assertTrue(Routing.Segment.Variable.Name.create("a-b").isEmpty) &&
        assertTrue(Routing.Segment.Variable.Name.create("a~b").isEmpty)
      },
    ),
    suite("httpPathVariables")(
      // AIP-4222: `option (google.api.http).post = "{parent=projects/*}/topics"` uses `parent`
      test("the implicit routing example from AIP-4222") {
        assertTrue(Routing.httpPathVariables("{parent=projects/*}/topics") == List("parent"))
      },
      // AIP-4222: "It is acceptable to omit the pattern in the resource ID segment,
      // `{parent}` for example, is equivalent to `{parent=*}`"
      test("a variable without a pattern is equivalent to `{key=*}`") {
        assertTrue(Routing.httpPathVariables("/v1/{parent}") == List("parent")) &&
        assertTrue(Routing.httpPathVariables("/v1/{parent}") == Routing.httpPathVariables("/v1/{parent=*}"))
      },
      test("a variable with a pattern") {
        assertTrue(Routing.httpPathVariables("/v2/{name=projects/*/locations/*}:cancel") == List("name"))
      },
      test("a variable without a pattern") {
        assertTrue(Routing.httpPathVariables("/v1/things/{thing_id}") == List("thing_id"))
      },
      test("a nested field path") {
        assertTrue(Routing.httpPathVariables("/v2/{job.name=projects/*/jobs/*}") == List("job.name"))
      },
      test("several variables, in order and without duplicates") {
        assertTrue(Routing.httpPathVariables("/v1/{parent=p/*}/{b}/{parent}") == List("parent", "b"))
      },
      // the http.proto `additional_bindings` example: get "/v1/messages/{message_id}"
      // with additional_bindings get "/v1/users/{user_id}/messages/{message_id}"
      test("the additional binding from the http.proto example") {
        assertTrue(Routing.httpPathVariables("/v1/messages/{message_id}") == List("message_id")) &&
        assertTrue(Routing.httpPathVariables("/v1/users/{user_id}/messages/{message_id}") == List("user_id", "message_id"))
      },
      test("no variables") {
        assertTrue(Routing.httpPathVariables("/v1/things") == Nil) &&
        assertTrue(Routing.httpPathVariables("") == Nil)
      },
      test("rejects unbalanced braces") {
        assertTrue(Try(Routing.httpPathVariables("/v1/{name")).isFailure) &&
        assertTrue(Try(Routing.httpPathVariables("/v1/name}")).isFailure)
      },
      test("rejects an invalid variable name") {
        assertTrue(Try(Routing.httpPathVariables("/v1/{=things/*}")).isFailure)
      },
    ),
    // which parameters a method's annotations produce, and which are rejected;
    // what the emitted client then looks like is ZioGrpcGeneratorTest's job
    suite("parameters")(
      // AIP-4222: option (google.api.routing) = { routing_parameters {
      //   field: "parent" path_template: "{project=projects/*}/**" } }
      test("parses the AIP-4222 explicit routing example") {
        val file = serviceDescriptor(
          method("CreateTopic", routing = Some(routingRule("parent" -> Some("{project=projects/*}/**")))),
        )
        assertTrue(
          parametersOf(file) == List(
            Routing.Parameter(
              "parent",
              Some(template(variable("project", literal("projects"), single), multi)),
              Routing.Source.Routing,
              fieldsOf(file, "parent"),
            ),
          ),
        )
      },
      // AIP-4222: `path_template: "projects/{parent}"` is the same as `"projects/{parent=*}"`
      test("defaults an explicit routing variable pattern to `*`") {
        val short = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> Some("projects/{parent}")))))
        val long = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> Some("projects/{parent=*}")))))
        // `FieldDescriptor` compares by identity, and these are different file descriptors
        assertTrue(parametersOf(short).map(_.pathTemplate) == parametersOf(long).map(_.pathTemplate)) &&
        assertTrue(parametersOf(short).head.pathTemplate.nonEmpty)
      },
      // AIP-4222: "An omitted `path_template` is equivalent to a `path_template`
      // with the same resource ID name as the field and the pattern `**`"
      test("sends the whole field for an omitted path_template") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("parent" -> None))))
        assertTrue(parametersOf(file) == List(Routing.Parameter("parent", None, Routing.Source.Routing, fieldsOf(file, "parent"))))
      },
      // AIP-4222's "last one wins" example: the order of the parameters decides
      // which value is sent for a shared resource ID name
      test("preserves the order of the AIP-4222 last-one-wins example") {
        val file = serviceDescriptor(
          method(
            "GetThing",
            routing = Some(
              routingRule(
                "parent" -> Some("{project=projects/*}/**"),
                "parent" -> Some("{project=projects/*/subprojects/*}/**"),
                "billing_project" -> Some("{project=**}"),
              ),
            ),
          ),
        )
        assertTrue(parametersOf(file).map(_.field) == List("parent", "parent", "billing_project")) &&
        assertTrue(parametersOf(file).map(_.pathTemplate).forall(_.nonEmpty))
      },
      // AIP-4222: option (google.api.http).post = "{parent=projects/*}/topics"
      test("parses the AIP-4222 implicit http routing example") {
        val file = serviceDescriptor(
          method("CreateTopic", httpRule = Some(HttpRule.newBuilder.setPost("{parent=projects/*}/topics").build())),
        )
        assertTrue(parametersOf(file) == List(Routing.Parameter("parent", None, Routing.Source.Http, fieldsOf(file, "parent"))))
      },
      // AIP-4222: "It is acceptable to omit the pattern in the resource ID segment"
      test("defaults an http path variable pattern to `*`") {
        val short = serviceDescriptor(method("GetThing", httpRule = Some(getRule("/v1/{parent}"))))
        val long = serviceDescriptor(method("GetThing", httpRule = Some(getRule("/v1/{parent=*}"))))
        // an http path variable sends the whole field, so only the name is carried
        assertTrue(parametersOf(short).map(parameter => (parameter.field, parameter.source)) ==
          parametersOf(long).map(parameter => (parameter.field, parameter.source)))
      },
      // AIP-4222: "If the google.api.http annotation contains additional_bindings,
      // these patterns must be parsed for additional request parameters." This is
      // the example from google/api/http.proto.
      test("parses the http.proto additional_bindings example") {
        val binding = HttpRule.newBuilder.setGet("/v1/users/{user_id}/messages/{message_id}").build()
        val file = serviceDescriptor(method("GetMessage", httpRule = Some(getRule("/v1/messages/{message_id}", binding))))
        assertTrue(
          parametersOf(file) == List(
            Routing.Parameter("message_id", None, Routing.Source.Http, fieldsOf(file, "message_id")),
            Routing.Parameter("user_id", None, Routing.Source.Http, fieldsOf(file, "user_id")),
          ),
        )
      },
      test("parses nested additional_bindings") {
        val nested = HttpRule.newBuilder.setGet("/v1/{c}").build()
        val binding = getRule("/v1/{b}", nested)
        val file = serviceDescriptor(method("GetThing", httpRule = Some(getRule("/v1/{a}", binding))))
        assertTrue(parametersOf(file).map(_.field) == List("a", "b", "c"))
      },
      test("uses additional_bindings when the primary http path has no variables") {
        val binding = HttpRule.newBuilder.setGet("/v1/users/{user_id}").build()
        val file = serviceDescriptor(method("GetThing", httpRule = Some(getRule("/v1/things", binding))))
        assertTrue(parametersOf(file).map(_.field) == List("user_id"))
      },
      test("an explicit routing option replaces the additional_bindings") {
        val binding = HttpRule.newBuilder.setGet("/v1/users/{user_id}").build()
        val file = serviceDescriptor(
          method("GetThing", routing = Some(routingRule("parent" -> None)), httpRule = Some(getRule("/v1/{name}", binding))),
        )
        assertTrue(parametersOf(file).map(_.field) == List("parent"))
      },
      // AIP-4222: "An empty google.api.routing annotation is acceptable. It means
      // that no routing headers should be generated for the RPC, when they
      // otherwise would be e.g. implicitly from the google.api.http annotation."
      test("an empty explicit routing option opts out of the http path variables") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule()), http = Some("/v1/{name=things/*}")))
        assertTrue(parametersOf(file).isEmpty)
      },
      test("an empty explicit routing option without an http rule has no parameters") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule())))
        assertTrue(parametersOf(file).isEmpty)
      },
      test("has no parameters without routing or http options") {
        assertTrue(parametersOf(serviceDescriptor(method("GetThing"))).isEmpty)
      },
      test("parses the template of a client-streaming method") {
        val file = serviceDescriptor(
          method("StreamThings", clientStreaming = true, routing = Some(routingRule("name" -> Some("{name=*")))),
        )
        assertTrue(Try(parametersOf(file)).isFailure)
      },
      test("ignores routing on a client-streaming method") {
        val file = serviceDescriptor(
          method("StreamThings", clientStreaming = true, routing = Some(routingRule("name" -> Some("{name=*}")))),
        )
        assertTrue(parametersOf(file).isEmpty)
      },
      // AIP-4222: routing applies to an unary or server-streaming RPC
      test("applies routing to a server-streaming method") {
        val file = serviceDescriptor(
          method("StreamThings", serverStreaming = true, routing = Some(routingRule("name" -> Some("{name=*}")))),
        )
        assertTrue(parametersOf(file).nonEmpty)
      },
      test("rejects a repeated field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("items.name" -> None))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("repeated")))
      },
      test("rejects an unknown field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("missing" -> None))))
        assertTrue(Try(parametersOf(file)).isFailure)
      },
      test("rejects a non-string explicit routing field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("size" -> None))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("has type int32")))
      },
      test("rejects a message explicit routing field") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("item" -> None))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("has type message")))
      },
      test("rejects a message http path variable") {
        val file = serviceDescriptor(method("GetThing", http = Some("/v1/{item}")))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("has type message")))
      },
      test("reports the method and the reason for a malformed path template") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("name" -> Some("{a=*}/{b=*}")))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("test.TestService.GetThing"))) &&
        assertTrue(error.exists(_.getMessage.contains("expected exactly one named segment")))
      },
      // AIP-4222: "A segment must not represent a complex resource ID as described in AIP-4231"
      test("rejects a complex resource ID path segment") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("name" -> Some("{feed}~{feed_item}")))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("complex resource ID")))
      },
      // AIP-4222: "A multi-segment wildcard must only appear as the final segment"
      test("rejects a multi-segment wildcard that is not the final segment") {
        val file = serviceDescriptor(method("GetThing", routing = Some(routingRule("name" -> Some("**/{name=*}")))))
        val error = Try(parametersOf(file)).failed.toOption
        assertTrue(error.exists(_.getMessage.contains("must be the final segment")))
      },
    ),
  )
}
