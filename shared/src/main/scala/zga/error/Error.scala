package zga.error

import io.grpc.Metadata
import io.grpc.Status
import zio.Cause
import zio.Chunk

import scala.annotation.threadUnsafe

/**
 * Subtypes are not comparable via equality.
 */
sealed abstract class Error(val code: Status.Code) {
  def description: Option[String]
  def cause: Option[Throwable]
  def details: Chunk[RpcStatus.Details]
  def unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)]
  def trailers: Metadata

  def asRuntimeException = Status.fromCode(code)
    .withDescription(description.orNull)
    .withCause(cause.orNull)
    .asRuntimeException(trailers)
}

/**
 * @see
 *   [[https://github.com/grpc/grpc/blob/v1.83.1/doc/statuscodes.md Status Codes]]
 */
object Error {

  // def because `Metadata` has internal state
  def emptyMetadata = new Metadata()

  /**
   * @see
   *   [[Status.Code.CANCELLED]]
   */
  case class Cancelled(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.CANCELLED)

  /**
   * @see
   *   [[Status.Code.UNKNOWN]]
   */
  case class Unknown(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.UNKNOWN)

  /**
   * @see
   *   [[Status.Code.INVALID_ARGUMENT]]
   */
  case class InvalidArgument(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.INVALID_ARGUMENT)

  /**
   * @see
   *   [[Status.Code.DEADLINE_EXCEEDED]]
   */
  case class DeadlineExceeded(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.DEADLINE_EXCEEDED)

  /**
   * @see
   *   [[Status.Code.NOT_FOUND]]
   */
  case class NotFound(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.NOT_FOUND)

  /**
   * @see
   *   [[Status.Code.ALREADY_EXISTS]]
   */
  case class AlreadyExists(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.ALREADY_EXISTS)

  /**
   * @see
   *   [[Status.Code.PERMISSION_DENIED]]
   */
  case class PermissionDenied(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.PERMISSION_DENIED)

  /**
   * @see
   *   [[Status.Code.RESOURCE_EXHAUSTED]]
   */
  case class ResourceExhausted(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.RESOURCE_EXHAUSTED)

  /**
   * @see
   *   [[Status.Code.FAILED_PRECONDITION]]
   */
  case class FailedPrecondition(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.FAILED_PRECONDITION)

  /**
   * @see
   *   [[Status.Code.ABORTED]]
   */
  case class Aborted(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.ABORTED)

  /**
   * @see
   *   [[Status.Code.OUT_OF_RANGE]]
   */
  case class OutOfRange(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.OUT_OF_RANGE)

  /**
   * @see
   *   [[Status.Code.UNIMPLEMENTED]]
   */
  case class Unimplemented(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.UNIMPLEMENTED)

  /**
   * @see
   *   [[Status.Code.INTERNAL]]
   */
  case class Internal(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.INTERNAL)

  /**
   * @see
   *   [[Status.Code.UNAVAILABLE]]
   */
  case class Unavailable(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.UNAVAILABLE)

  /**
   * @see
   *   [[Status.Code.DATA_LOSS]]
   */
  case class DataLoss(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.DATA_LOSS)

  /**
   * @see
   *   [[Status.Code.UNAUTHENTICATED]]
   */
  case class Unauthenticated(
    description: Option[String],
    cause: Option[Throwable],
    details: Chunk[RpcStatus.Details],
    unparseableDetails: Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata = emptyMetadata,
  ) extends Error(Status.Code.UNAUTHENTICATED)

  def fromStatus(status: Status): Cause[Error] = {
    fromStatusAndTrailers(status, emptyMetadata)
  }

  def fromStatusAndTrailers(status: Status, trailers: Metadata): Cause[Error] = {
    @threadUnsafe lazy val rpcStatus = RpcStatus.fromTrailers(trailers)

    fromStatusParts(
      status = status,
      description = Option(status.getDescription),
      cause = Option(status.getCause),
      details = rpcStatus.map(_.details).getOrElse(Chunk.empty),
      unparseableDetails = rpcStatus.map(_.unparseableDetails).getOrElse(Chunk.empty),
      trailers = trailers,
    )
  }

  def fromRpcStatus(status: zga.google.rpc.Status): Cause[Error] = {
    @threadUnsafe lazy val partitioned = status.details.partitionMap { a =>
      RpcStatus.Details.from(a).left.map((a, _))
    }

    fromStatusParts(
      status = Status.fromCodeValue(status.code),
      description = Option(status.message).filter(_.nonEmpty),
      cause = None,
      details = partitioned._2,
      unparseableDetails = partitioned._1,
      trailers = emptyMetadata,
    )
  }

  private def fromStatusParts(
    status: Status,
    description: Option[String],
    cause: Option[Throwable],
    details: => Chunk[RpcStatus.Details],
    unparseableDetails: => Chunk[(zga.google.protobuf.Any, Throwable)],
    trailers: Metadata,
  ) = status.getCode match {
    case Status.Code.OK => Cause.die(status.asRuntimeException(trailers))
    case Status.Code.CANCELLED => Cause.fail(Cancelled(description, cause, details, unparseableDetails, trailers))
    case Status.Code.UNKNOWN => Cause.fail(Unknown(description, cause, details, unparseableDetails, trailers))
    case Status.Code.INVALID_ARGUMENT => Cause.fail(InvalidArgument(description, cause, details, unparseableDetails, trailers))
    case Status.Code.DEADLINE_EXCEEDED => Cause.fail(DeadlineExceeded(description, cause, details, unparseableDetails, trailers))
    case Status.Code.NOT_FOUND => Cause.fail(NotFound(description, cause, details, unparseableDetails, trailers))
    case Status.Code.ALREADY_EXISTS => Cause.fail(AlreadyExists(description, cause, details, unparseableDetails, trailers))
    case Status.Code.PERMISSION_DENIED => Cause.fail(PermissionDenied(description, cause, details, unparseableDetails, trailers))
    case Status.Code.RESOURCE_EXHAUSTED => Cause.fail(ResourceExhausted(description, cause, details, unparseableDetails, trailers))
    case Status.Code.FAILED_PRECONDITION => Cause.fail(FailedPrecondition(description, cause, details, unparseableDetails, trailers))
    case Status.Code.ABORTED => Cause.fail(Aborted(description, cause, details, unparseableDetails, trailers))
    case Status.Code.OUT_OF_RANGE => Cause.fail(OutOfRange(description, cause, details, unparseableDetails, trailers))
    case Status.Code.UNIMPLEMENTED => Cause.fail(Unimplemented(description, cause, details, unparseableDetails, trailers))
    case Status.Code.INTERNAL => Cause.fail(Internal(description, cause, details, unparseableDetails, trailers))
    case Status.Code.UNAVAILABLE => Cause.fail(Unavailable(description, cause, details, unparseableDetails, trailers))
    case Status.Code.DATA_LOSS => Cause.fail(DataLoss(description, cause, details, unparseableDetails, trailers))
    case Status.Code.UNAUTHENTICATED => Cause.fail(Unauthenticated(description, cause, details, unparseableDetails, trailers))
  }
}
