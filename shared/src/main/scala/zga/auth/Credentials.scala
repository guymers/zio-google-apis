package zga.auth

import com.google.auth.Credentials as GoogleAuthCredentials
import com.google.auth.Retryable
import com.google.auth.oauth2.GoogleCredentials
import io.grpc.Metadata
import io.grpc.Status
import zio.Trace
import zio.ZIO

import java.net.URI
import java.util.Base64

object Credentials {

  def defaultGoogleCredentials: ZIO[Any, Throwable, GoogleCredentials] = ZIO.attemptBlocking {
    GoogleCredentials.getApplicationDefault()
  }

  def create(credentials: GoogleAuthCredentials, scopes: List[String]): Credentials = {

    // https://github.com/googleapis/google-cloud-java/blob/gax/v2.86.0/sdk-platform-java/gax-java/gax/src/main/java/com/google/api/gax/core/GoogleCredentialsProvider.java#L96
    val cred = credentials match {
      case c: GoogleCredentials if c.createScopedRequired() => c.createScoped(scopes*)
      case c => c
    }
    new Credentials(cred)
  }

  def serviceUri(authority: String, serviceName: String)(using Trace): ZIO[Any, Status, URI] = for {
    // https://github.com/grpc/grpc-java/blob/v1.83.1/auth/src/main/java/io/grpc/auth/GoogleAuthLibraryCallCredentials.java#L165
    uri <- ZIO.attempt {
      new URI("https", authority, s"/$serviceName", null, null)
    }.mapError { t =>
      Status.UNAUTHENTICATED.withDescription("Unable to construct service URI for auth").withCause(t)
    }
    uriNoPort <- ZIO.attempt {
      if (uri.getPort == 443) {
        new URI(uri.getScheme, uri.getUserInfo, uri.getHost, -1, uri.getPath, uri.getQuery, uri.getFragment)
      } else {
        uri
      }
    }.mapError { t =>
      Status.UNAUTHENTICATED.withDescription("Unable to construct service URI after removing port").withCause(t)
    }
  } yield uriNoPort
}

final class Credentials(credentials: GoogleAuthCredentials) {

  def headers(uri: URI)(using Trace): ZIO[Any, Status, Metadata] = {
    ZIO.attemptBlocking {
      credentials.getRequestMetadata(uri)
    }.mapAttempt(toHeaders).mapError {
      // https://github.com/grpc/grpc-java/blob/v1.83.1/auth/src/main/java/io/grpc/auth/GoogleAuthLibraryCallCredentials.java#L148
      case e: Retryable if e.isRetryable =>
        Status.UNAVAILABLE
          .withDescription("Credentials failed to obtain metadata")
          .withCause(e)
      case t =>
        Status.UNAUTHENTICATED
          .withDescription("Failed computing credential metadata")
          .withCause(t)
    }
  }

  // https://github.com/grpc/grpc-java/blob/v1.83.1/auth/src/main/java/io/grpc/auth/GoogleAuthLibraryCallCredentials.java#L195
  private def toHeaders(metadata: java.util.Map[String, java.util.List[String]]) = {
    val headers = new Metadata()
    if (metadata != null) {
      metadata.forEach { case (key, values) =>
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          val headerKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER)
          values.forEach { value =>
            headers.put(headerKey, Base64.getDecoder.decode(value));
          }
        } else {
          val headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
          values.forEach { value =>
            headers.put(headerKey, value)
          }
        }
      }
    }
    headers
  }
}
