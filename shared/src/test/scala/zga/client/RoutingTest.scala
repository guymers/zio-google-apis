package zga.client

import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.jdk.CollectionConverters.*

object RoutingTest extends ZIOSpecDefault {

  private val tableName = "projects/proj_foo/instances/instance_bar/tables/table_baz"

  private val single = Routing.Segment.Single
  private val multi = Routing.Segment.Multi

  private def literal(value: String): Routing.Segment = Routing.Segment.Literal(value)

  private def variable(name: String, pattern: Routing.Segment*): Routing.Segment = {
    Routing.Segment.Variable(name, pattern.toList)
  }

  private def template(segments: Routing.Segment*): Routing.Template = Routing.Template(segments.toList)

  private def extract(template: Routing.Template, value: String) = Routing.extract(template, value)

  private final case class Request(name: String)

  // the request of AIP-4222's "last one wins" example
  private final case class TopicRequest(parent: String, billingProject: String)

  private val projectPattern: Routing.Template =
    template(variable("project", literal("projects"), single), multi)

  private val subprojectPattern: Routing.Template =
    template(variable("project", literal("projects"), single, literal("subprojects"), single), multi)

  private val nameParameter: List[Routing.Parameter[Request]] =
    List(Routing.parameter("name", (request: Request) => Some(request.name)))

  override val spec = suite("Routing")(
    suite("path templates")(
      test("extracts the whole field") {
        assertTrue(extract(template(variable("table_name", multi)), tableName) == List("table_name" -> tableName))
      },
      test("matches a well-formed value") {
        val table = template(variable("table_name", literal("projects"), single, literal("instances"), single, multi))
        assertTrue(extract(table, tableName) == List("table_name" -> tableName))
      },
      test("skips a value that does not match the template") {
        val table = template(variable("table_name", literal("regions"), single, literal("zones"), single, multi))
        assertTrue(extract(table, tableName).isEmpty)
      },
      test("extracts part of the value") {
        val routing = template(variable("routing_id", literal("projects"), single), multi)
        assertTrue(extract(routing, tableName) == List("routing_id" -> "projects/proj_foo"))
      },
      test("extracts an unnamed middle segment") {
        val instance = template(literal("projects"), single, variable("instance_id", literal("instances"), single), multi)
        assertTrue(extract(instance, tableName) == List("instance_id" -> "instances/instance_bar"))
      },
      test("extracts a middle segment") {
        val location = template(literal("projects"), single, variable("table_location", literal("instances"), single), literal("tables"), single)
        assertTrue(extract(location, tableName) == List("table_location" -> "instances/instance_bar"))
      },
      test("extracts a suffix segment") {
        val profile = template(literal("profiles"), variable("routing_id", single))
        assertTrue(extract(profile, "profiles/prof_qux") == List("routing_id" -> "prof_qux"))
      },
      test("a double wildcard matches zero segments") {
        val location = template(literal("projects"), single, literal("locations"), variable("location", single), multi)
        assertTrue(extract(location, "projects/p/locations/l") == List("location" -> "l"))
      },
      // AIP-4222: "a path_template like `foo/**` matches all of the following:
      // `foo`, `foo/`, `foo/bar/baz`"
      test("a final double wildcard matches zero or more segments") {
        val foo = template(variable("name", literal("foo"), multi))
        assertTrue(extract(foo, "foo") == List("name" -> "foo")) &&
        assertTrue(extract(foo, "foo/") == List("name" -> "foo/")) &&
        assertTrue(extract(foo, "foo/bar/baz") == List("name" -> "foo/bar/baz"))
      },
      test("a double wildcard is greedy") {
        val instances = template(variable("routing_id", literal("projects"), single, literal("instances"), single), multi)
        assertTrue(extract(instances, tableName) == List("routing_id" -> "projects/proj_foo/instances/instance_bar"))
      },
      // AIP-4222: routing_parameters { field: "parent" path_template: "{project=projects/*}/**" }
      test("extracts the projects/* part of the AIP-4222 explicit example") {
        assertTrue(extract(projectPattern, "projects/100/subprojects/200/foo") == List("project" -> "projects/100")) &&
        assertTrue(extract(projectPattern, "projects/100") == List("project" -> "projects/100"))
      },
      // AIP-4222: routing_parameters { field: "parent"
      //   path_template: "{project=projects/*/subprojects/*}/**" }
      test("extracts the projects/*/subprojects/* part of the AIP-4222 example") {
        val value = "projects/100/subprojects/200/foo"
        assertTrue(extract(subprojectPattern, value) == List("project" -> "projects/100/subprojects/200")) &&
        // the more specific pattern does not match without a subproject
        assertTrue(extract(subprojectPattern, "projects/100").isEmpty)
      },
      // AIP-4222: "It is acceptable to omit the path_template field altogether. An
      // omitted path_template is equivalent to a path_template with the same
      // resource ID name as the field and the pattern `**`"
      test("an omitted path_template sends the whole field, as `{field=**}` does") {
        val whole = Routing.parameter("parent", (request: TopicRequest) => Some(request.parent))
        val matched = Routing.parameter(template(variable("parent", multi)), (request: TopicRequest) => Some(request.parent))
        assertTrue(
          Routing.requestParams(TopicRequest("projects/100/foo", ""), List(whole)) ==
            Routing.requestParams(TopicRequest("projects/100/foo", ""), List(matched)),
        )
      },
      test("a trailing slash is an empty segment") {
        val project = template(variable("name", literal("projects"), single))
        assertTrue(extract(project, "projects/p/").isEmpty)
      },
      test("a variable must bind at least one segment") {
        val trailing = template(variable("routing_id", multi), literal("foo"))
        assertTrue(extract(trailing, "foo").isEmpty)
      },
      test("a variable still binds the segments it does match") {
        val trailing = template(variable("routing_id", multi), literal("foo"))
        assertTrue(extract(trailing, "bar/foo") == List("routing_id" -> "bar"))
      },
    ),
    suite("request params metadata")(
      test("sets the header when it is absent") {
        for {
          headers <- SafeMetadata.make
          _ <- Routing.setRequestParams(headers, Request("some/name"), nameParameter)
          values = requestParams(headers)
        } yield {
          assertTrue(values == List("name=some%2Fname"))
        }
      },
      test("does not overwrite an existing header") {
        for {
          headers <- SafeMetadata.fromMetadata {
            val metadata = new io.grpc.Metadata
            metadata.put(Routing.RequestParamsKey, "custom=1")
            metadata
          }
          _ <- Routing.setRequestParams(headers, Request("some/name"), nameParameter)
          values = requestParams(headers)
        } yield {
          assertTrue(values == List("custom=1"))
        }
      },
      test("sets nothing when no parameter matches") {
        for {
          headers <- SafeMetadata.make
          _ <- Routing.setRequestParams(headers, Request(""), nameParameter)
          values = requestParams(headers)
        } yield {
          assertTrue(values.isEmpty)
        }
      },
      // AIP-4222: "if a given request has a parent field with a value e.g.
      // projects/100/subprojects/200/foo, patterns in both first and second
      // routing_parameters will match it, but the second one will 'win' since it
      // is specified 'last'"
      test("the last parameter wins for a shared header key") {
        val parameters = List(
          Routing.parameter(projectPattern, (request: TopicRequest) => Some(request.parent)),
          Routing.parameter(subprojectPattern, (request: TopicRequest) => Some(request.parent)),
          Routing.parameter(template(variable("project", multi)), (request: TopicRequest) => Some(request.billingProject)),
        )
        assertTrue(
          Routing.requestParams(TopicRequest("projects/100/subprojects/200/foo", ""), parameters) ==
            Some("project=projects%2F100%2Fsubprojects%2F200"),
        ) &&
        // a non-empty billing_project is specified last, so it wins outright
        assertTrue(
          Routing.requestParams(TopicRequest("projects/100/subprojects/200/foo", "profiles/prof_qux"), parameters) ==
            Some("project=profiles%2Fprof_qux"),
        )
      },
      // AIP-4222: "If all the routing parameters with the same resource ID segment
      // name have failed to match the field ... the key-value pair ... must not be sent"
      test("sends nothing when no pattern matches the field") {
        val parameters = List(
          Routing.parameter(projectPattern, (request: TopicRequest) => Some(request.parent)),
          Routing.parameter(subprojectPattern, (request: TopicRequest) => Some(request.parent)),
        )
        assertTrue(Routing.requestParams(TopicRequest("not-a-project", ""), parameters).isEmpty)
      },
      // AIP-4222: "Both the key and the value must be URL-encoded per RFC 6570 §3.2.2"
      test("percent-encodes the value") {
        assertTrue(Routing.requestParams(Request("a b/c:d?e"), nameParameter) == Some("name=a%20b%2Fc%3Ad%3Fe"))
      },
      // AIP-4222: "if there is more than one key-value pair to be sent, the `&`
      // character is used as the separator"
      test("joins several parameters with `&`") {
        val parameters = List(
          Routing.parameter("parent", (request: TopicRequest) => Some(request.parent)),
          Routing.parameter("billing_project", (request: TopicRequest) => Some(request.billingProject)),
        )
        assertTrue(
          Routing.requestParams(TopicRequest("projects/1", "profiles/2"), parameters) ==
            Some("parent=projects%2F1&billing_project=profiles%2F2"),
        )
      },
      test("sets the header once when called concurrently") {
        for {
          headers <- SafeMetadata.make
          _ <- ZIO.foreachParDiscard(1 to 100)(i => Routing.setRequestParams(headers, Request(s"name-$i"), nameParameter))
          values = requestParams(headers)
        } yield {
          assertTrue(values.length == 1)
        }
      },
    ),
  ) @@ TestAspect.timeout(15.seconds)

  private def requestParams(headers: SafeMetadata) = {
    Option(headers.metadata.getAll(Routing.RequestParamsKey)).fold(List.empty[String])(_.asScala.toList)
  }
}
