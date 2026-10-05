package zga.client

import io.grpc.Metadata
import zga.error.Error
import zio.Cause
import zio.Chunk

private[client] object CallbackFailure {

  def apply(cause: Cause[Nothing], trailers: Metadata): Error = {
    Error.Internal(
      description = Some("Failed to handle the call's response"),
      cause = cause.defects.headOption,
      details = Chunk.empty,
      unparseableDetails = Chunk.empty,
      trailers = trailers,
    )
  }
}
