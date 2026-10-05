package zga.client.cloudrun

import zga.client.Routing
import zga.client.SafeMetadata
import zga.google.cloud.run.v2.CreateJobRequest
import zga.google.cloud.run.v2.GetExecutionRequest
import zga.google.cloud.run.v2.GetJobRequest
import zga.google.cloud.run.v2.Job
import zga.google.cloud.run.v2.UpdateJobRequest
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object RoutingTest extends ZIOSpecDefault {

  private val single = Routing.Segment.Single
  private val multi = Routing.Segment.Multi

  private def literal(value: String): Routing.Segment = Routing.Segment.Literal(value)

  private def variable(name: String, pattern: Routing.Segment*): Routing.Segment = {
    Routing.Segment.Variable(name, pattern.toList)
  }

  private def template(segments: Routing.Segment*): Routing.Template = Routing.Template(segments.toList)

  override val spec = suite("Routing")(
    suite("request params")(
      test("extracts the location from a resource name") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        assertTrue(Routing.requestParams(request, JobsClient.Grpc.ROUTING_GET_JOB) == Some("location=l"))
      },
      test("extracts the location from a parent") {
        val request = CreateJobRequest(parent = "projects/p/locations/l", job = None, jobId = "")
        assertTrue(Routing.requestParams(request, JobsClient.Grpc.ROUTING_CREATE_JOB) == Some("location=l"))
      },
      test("extracts a nested field") {
        val request = UpdateJobRequest(job = Some(Job(name = "projects/p/locations/l/jobs/j", template = None)))
        assertTrue(Routing.requestParams(request, JobsClient.Grpc.ROUTING_UPDATE_JOB) == Some("location=l"))
      },
      test("skips an unset nested field") {
        val request = UpdateJobRequest(job = None)
        assertTrue(Routing.requestParams(request, JobsClient.Grpc.ROUTING_UPDATE_JOB).isEmpty)
      },
      test("skips a field that does not match the path template") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        val revisions = template(literal("projects"), single, literal("locations"), single, literal("revisions"), single)
        val parameters = List(Routing.parameter(revisions, (request: GetJobRequest) => Some(request.name)))
        assertTrue(Routing.requestParams(request, parameters).isEmpty)
      },
      test("uses the field name and whole value when there is no template") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        val parameters = List(Routing.parameter("name", (request: GetJobRequest) => Some(request.name)))
        assertTrue(Routing.requestParams(request, parameters) == Some("name=projects%2Fp%2Flocations%2Fl%2Fjobs%2Fj"))
      },
      test("a later parameter wins a key conflict") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        val parameters = List(
          Routing.parameter(
            template(variable("location", literal("projects"), single), multi),
            (request: GetJobRequest) => Some(request.name),
          ),
          Routing.parameter(
            template(variable("location", literal("projects"), single, literal("locations"), single), multi),
            (request: GetJobRequest) => Some(request.name),
          ),
        )
        assertTrue(Routing.requestParams(request, parameters) == Some("location=projects%2Fp%2Flocations%2Fl"))
      },
      test("joins parameters with an ampersand") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        val parameters = List(
          Routing.parameter(
            template(literal("projects"), single, literal("locations"), variable("location", single), multi),
            (request: GetJobRequest) => Some(request.name),
          ),
          Routing.parameter(
            template(literal("projects"), single, literal("locations"), single, variable("job", literal("jobs"), single)),
            (request: GetJobRequest) => Some(request.name),
          ),
        )
        assertTrue(Routing.requestParams(request, parameters) == Some("location=l&job=jobs%2Fj"))
      },
      test("percent-encodes keys and values") {
        val request = CreateJobRequest(parent = "a b/c&d", job = None, jobId = "")
        val parameters = List(Routing.parameter("parent", (request: CreateJobRequest) => Some(request.parent)))
        assertTrue(Routing.requestParams(request, parameters) == Some("parent=a%20b%2Fc%26d"))
      },
      test("falls back to the http path variables without a routing option") {
        val request = GetExecutionRequest(name = "projects/p/locations/l/jobs/j/executions/e")
        assertTrue(
          Routing.requestParams(request, ExecutionsClient.Grpc.ROUTING_GET_EXECUTION) ==
            Some("name=projects%2Fp%2Flocations%2Fl%2Fjobs%2Fj%2Fexecutions%2Fe"),
        )
      },
      test("skips an empty http path variable") {
        val request = GetExecutionRequest(name = "")
        assertTrue(Routing.requestParams(request, ExecutionsClient.Grpc.ROUTING_GET_EXECUTION).isEmpty)
      },
    ),
    suite("metadata")(
      test("sets x-goog-request-params") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        for {
          headers <- SafeMetadata.make
          _ <- Routing.setRequestParams(headers, request, JobsClient.Grpc.ROUTING_GET_JOB)
          value <- headers.get(Routing.RequestParamsKey)
        } yield assertTrue(value == Some("location=l"))
      },
      test("does not overwrite an existing x-goog-request-params") {
        val request = GetJobRequest(name = "projects/p/locations/l/jobs/j")
        for {
          headers <- SafeMetadata.fromMetadata {
            val metadata = new io.grpc.Metadata
            metadata.put(Routing.RequestParamsKey, "custom=1")
            metadata
          }
          _ <- Routing.setRequestParams(headers, request, JobsClient.Grpc.ROUTING_GET_JOB)
          value <- headers.get(Routing.RequestParamsKey)
        } yield assertTrue(value == Some("custom=1"))
      },
      test("sets nothing when no parameter matches") {
        val request = GetJobRequest(name = "unmatched")
        for {
          headers <- SafeMetadata.make
          _ <- Routing.setRequestParams(headers, request, JobsClient.Grpc.ROUTING_GET_JOB)
          value <- headers.get(Routing.RequestParamsKey)
        } yield assertTrue(value.isEmpty)
      },
    ),
  )
}
