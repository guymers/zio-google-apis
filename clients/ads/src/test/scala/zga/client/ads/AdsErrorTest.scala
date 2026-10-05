package zga.client.ads

import io.grpc.Metadata
import zga.client.ads.error.AdsError
import zga.google.ads.errors.GoogleAdsError
import zga.google.ads.errors.GoogleAdsFailure
import zio.Chunk
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object AdsErrorTest extends ZIOSpecDefault {

  override val spec = suite("AdsError")(
    test("recognizes the failure trailer for the generated API version") {
      val failure = GoogleAdsFailure(errors = Chunk(GoogleAdsError(message = "invalid request")))
      val trailers = new Metadata()
      val key = Metadata.Key.of(GoogleAdsFailure.messageCodec.descriptor.getFullName + "-bin", Metadata.BINARY_BYTE_MARSHALLER)
      trailers.put(key, GoogleAdsFailure.messageCodec.toByteArray(failure))
      val error = zga.error.Error.InvalidArgument(
        description = None,
        cause = None,
        details = Chunk.empty,
        unparseableDetails = Chunk.empty,
        trailers = trailers,
      )
      assertTrue(AdsError.fromError(error) == AdsError.Failure(failure, error))
    },
  )
}
