package zga.client

import io.grpc.Metadata
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import scala.jdk.CollectionConverters.*

object SafeMetadataTest extends ZIOSpecDefault {

  private val key = Metadata.Key.of("test-key", Metadata.ASCII_STRING_MARSHALLER)

  override val spec = suite("SafeMetadata")(
    test("gets and puts values") {
      for {
        headers <- SafeMetadata.make
        before <- headers.get(key)
        _ <- headers.put(key, "value")
        after <- headers.get(key)
      } yield {
        assertTrue(before == None) &&
        assertTrue(after == Some("value"))
      }
    },
    test("removes a value") {
      for {
        headers <- SafeMetadata.make
        _ <- headers.put(key, "value")
        removed <- headers.remove(key, "value")
        removedAgain <- headers.remove(key, "value")
        remaining <- headers.get(key)
      } yield {
        assertTrue(removed) &&
        assertTrue(!removedAgain) &&
        assertTrue(remaining == None)
      }
    },
    suite("putIfAbsent")(
      test("adds a missing value") {
        for {
          headers <- SafeMetadata.make
          added <- headers.putIfAbsent(key, "value")
          values = stored(headers)
        } yield {
          assertTrue(added) &&
          assertTrue(values == List("value"))
        }
      },
      test("keeps an existing value") {
        for {
          headers <- SafeMetadata.make
          _ <- headers.put(key, "first")
          added <- headers.putIfAbsent(key, "second")
          values = stored(headers)
        } yield {
          assertTrue(!added) &&
          assertTrue(values == List("first"))
        }
      },
      test("is atomic") {
        for {
          headers <- SafeMetadata.make
          added <- ZIO.foreachPar(1 to 100)(i => headers.putIfAbsent(key, i.toString))
          values = stored(headers)
        } yield {
          assertTrue(added.count(identity) == 1) &&
          assertTrue(values.length == 1)
        }
      },
    ),
  ) @@ TestAspect.timeout(15.seconds)

  private def stored(headers: SafeMetadata) = {
    Option(headers.metadata.getAll(key)).fold(List.empty[String])(_.asScala.toList)
  }

}
