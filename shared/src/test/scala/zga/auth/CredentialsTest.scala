package zga.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import com.google.auth.Retryable
import io.grpc.Metadata
import io.grpc.Status
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

object CredentialsTest extends ZIOSpecDefault {

  private val uri = URI.create("https://test.googleapis.com/test.Service")

  private val authorizationKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

  private val traceKey = Metadata.Key.of("trace-bin", Metadata.BINARY_BYTE_MARSHALLER)

  override val spec = suite("Credentials")(
    test("converts request metadata into headers") {
      for {
        _ <- ZIO.unit
        credentials = new Credentials(googleCredentials { _ =>
          Map(
            "authorization" -> List("Bearer token"),
            "trace-bin" -> List("aGVsbG8="),
          )
        })
        headers <- credentials.headers(uri)
      } yield {
        assertTrue(Option(headers.get(authorizationKey)) == Some("Bearer token")) &&
        assertTrue(Option(headers.get(traceKey)).map(new String(_, StandardCharsets.UTF_8)) == Some("hello"))
      }
    },
    test("passes the service URI to the credentials") {
      for {
        _ <- ZIO.unit
        seen = new AtomicReference[URI]()
        credentials = new Credentials(googleCredentials { uri =>
          seen.set(uri)
          Map("authorization" -> List("Bearer token"))
        })
        _ <- credentials.headers(uri)
      } yield {
        assertTrue(seen.get == uri)
      }
    },
    test("maps a retryable exception to UNAVAILABLE") {
      for {
        _ <- ZIO.unit
        credentials = new Credentials(googleCredentials(_ => throw new RetryableException))
        result <- credentials.headers(uri).either
      } yield {
        assertTrue(statusCode(result) == Some(Status.Code.UNAVAILABLE))
      }
    },
    test("maps any other exception to UNAUTHENTICATED") {
      for {
        _ <- ZIO.unit
        credentials = new Credentials(googleCredentials(_ => throw new RuntimeException))
        result <- credentials.headers(uri).either
      } yield {
        assertTrue(statusCode(result) == Some(Status.Code.UNAUTHENTICATED))
      }
    },
    test("maps a header that cannot be represented to UNAUTHENTICATED") {
      for {
        _ <- ZIO.unit
        credentials = new Credentials(googleCredentials(_ => Map("bad key" -> List("value"))))
        result <- credentials.headers(uri).either
      } yield {
        assertTrue(statusCode(result) == Some(Status.Code.UNAUTHENTICATED))
      }
    },
    suite("service URI")(
      test("uses https and omits the default port") {
        for {
          serviceUri <- Credentials.serviceUri("run.googleapis.com:443", "google.cloud.run.v2.Jobs")
        } yield {
          assertTrue(serviceUri.toString == "https://run.googleapis.com/google.cloud.run.v2.Jobs")
        }
      },
      test("keeps a non-default port") {
        for {
          serviceUri <- Credentials.serviceUri("localhost:8080", "test.Service")
        } yield {
          assertTrue(serviceUri.toString == "https://localhost:8080/test.Service")
        }
      },
    ),
  ) @@ TestAspect.timeout(15.seconds)

  private def googleCredentials(f: URI => Map[String, List[String]]) = new GoogleAuthCredentials {
    override def getAuthenticationType = "test"
    override def getRequestMetadata(uri: URI) = f(uri).map { case (key, values) => key -> values.asJava }.asJava
    override def hasRequestMetadata = true
    override def hasRequestMetadataOnly = true
    override def refresh() = ()
  }

  private def statusCode[A](result: Either[Status, A]) = result.swap.toOption.map(_.getCode)

  private class RetryableException extends RuntimeException with Retryable {
    override def isRetryable = true
    override def getRetryCount = 1
  }
}
