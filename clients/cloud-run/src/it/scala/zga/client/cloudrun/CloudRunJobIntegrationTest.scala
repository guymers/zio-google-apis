package zga.client.cloudrun

import zga.client.cloudrun.auth.CloudRunAuthentication
import zga.common.MessageCodec
import zga.google.cloud.run.ListJobsRequest
import zga.google.cloud.run.RunJobRequest
import zga.google.longrunning.CancelOperationRequest
import zga.google.longrunning.GetOperationRequest
import zga.google.longrunning.Operation
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zio.Chunk
import zio.Exit
import zio.Schedule
import zio.ZEnvironment
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.stream.ZStream
import zio.test.Assertion.equalTo
import zio.test.Assertion.hasField
import zio.test.Assertion.isLeft
import zio.test.Assertion.isSubtype
import zio.test.assert

object CloudRunJobIntegrationTest extends GoogleIntegrationTest {

  override val spec = suite("CloudRunJob")(
    test("can run a job") {
      for {
        auth <- ZIO.service[CloudRunAuthentication]
        client <- ZIO.service[JobsClient]
        projectId <- ZIO.service[GoogleCloudProjectId]

        jobs <- {
          val req = ListJobsRequest(s"projects/$projectId/locations/us-west1")
          client.listJobs(auth, req)
        }
        job = jobs.jobs.find(_.name.endsWith("/zga-test-job"))

        op <- {
          val req = RunJobRequest(job.get.name)
          client.runJob(auth, req)
        }
        result <- poll(op).runCollect.either
      } yield {
        assert(result)(isLeft(isSubtype[Result.Success](
          hasField("value.runningCount", (success: Result.Success) => success.value.runningCount, equalTo(0)) &&
          hasField("value.succeededCount", (success: Result.Success) => success.value.succeededCount, equalTo(1)),
        )))
      }
    },
  ).provideSomeLayerShared(CloudRunIntegrationTest.layer >+> jobClientLayer)

  private def jobClientLayer = ZLayer.fromZIOEnvironment(for {
    channel <- ZIO.service[CloudRunChannel]
    options = io.grpc.CallOptions.DEFAULT
    client = JobsClient.create(channel, options)
    operationsClient = OperationsClient.create(channel, options)
  } yield ZEnvironment.empty.add(client).add(operationsClient))

  private def poll(op: Operation) = {
    val schedule = Schedule.spaced(5.seconds).upTo(50.seconds)

    (ZStream.apply(op) ++ ZStream.fromSchedule(schedule).mapZIO(_ => get(op.name)))
      .mapError(Result.Error(_))
      .flatMap { op =>
        if (op.done) {
          val result = op.result match {
            case None =>
              val e = zga.error.Error.Unknown(
                description = Some("op is done but there is no result"),
                cause = None,
                details = Chunk.empty,
                unparseableDetails = Chunk.empty,
              )
              Exit.fail(Result.Error(e))
            case Some(Operation.Result.Error(status)) =>
              Exit.failCause(zga.error.Error.fromRpcStatus(status)).mapError(Result.Error(_))
            case Some(Operation.Result.Response(any)) =>
              MessageCodec[zga.google.cloud.run.Execution].safeParseFrom(any.value) match {
                case Left(t) =>
                  val e = zga.error.Error.Unknown(
                    description = None,
                    cause = Some(t),
                    details = Chunk.empty,
                    unparseableDetails = Chunk.empty,
                  )
                  Exit.fail(Result.Error(e))
                case Right(e) => Exit.fail(Result.Success(e))
              }
          }
          ZStream.fromZIO(result)
        } else {
          ZStream.apply(op.metadata)
        }
      }
      .collectSome
      .onError { cause =>
        cancel(op.name).ignoreLogged.when(cause.isInterruptedOnly)
      }
  }

  private def get(name: String) = for {
    auth <- ZIO.service[CloudRunAuthentication]
    operationsClient <- ZIO.service[OperationsClient]
    request = GetOperationRequest(name)
    result <- operationsClient.getOperation(auth, request)
  } yield result

  private def cancel(name: String) = for {
    auth <- ZIO.service[CloudRunAuthentication]
    operationsClient <- ZIO.service[OperationsClient]
    request = CancelOperationRequest(name)
    result <- operationsClient.cancelOperation(auth, request)
  } yield result

  sealed abstract class Result
  object Result {
    sealed trait Complete extends Result
    case class Error(error: zga.error.Error) extends Complete
    case class Success(value: zga.google.cloud.run.Execution) extends Complete
  }
}
