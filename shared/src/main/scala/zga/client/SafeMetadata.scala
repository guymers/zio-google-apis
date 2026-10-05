package zga.client

import io.grpc.Metadata
import zio.Semaphore
import zio.Trace
import zio.ZIO

/**
 * Thread-safe access to a mutable [[Metadata]].
 */
final class SafeMetadata private (
  private val semaphore: Semaphore,
  private[client] val metadata: Metadata,
) {
  def get[T](key: Metadata.Key[T])(using Trace): ZIO[Any, Nothing, Option[T]] = wrap { m =>
    Option(m.get(key))
  }

  def put[T](key: Metadata.Key[T], value: T)(using Trace): ZIO[Any, Nothing, Unit] = wrap { m =>
    m.put(key, value)
  }

  /**
   * Adds the value only when the key has no value, returning whether it was
   * added.
   */
  def putIfAbsent[T](key: Metadata.Key[T], value: T)(using Trace): ZIO[Any, Nothing, Boolean] = wrap { m =>
    if (m.get(key) == null) {
      m.put(key, value)
      true
    } else {
      false
    }
  }

  def remove[T](key: Metadata.Key[T], value: T)(using Trace): ZIO[Any, Nothing, Boolean] = wrap { m =>
    m.remove(key, value)
  }

  private[client] def copy(using Trace) = wrap { m =>
    val copy = new Metadata
    copy.merge(m)
    copy
  }

  private def wrap[A](f: Metadata => A)(using Trace) = semaphore.withPermit(ZIO.succeed(f(metadata)))
}

object SafeMetadata {

  def make(using Trace): ZIO[Any, Nothing, SafeMetadata] = fromMetadata(new Metadata)

  /**
   * Creates a new [[SafeMetadata]] by taking ownership of the given metadata.
   *
   * The provided metadata should not be used after calling this method.
   */
  def fromMetadata(metadata: => Metadata)(using Trace): ZIO[Any, Nothing, SafeMetadata] = {
    Semaphore.make(1).map(sem => new SafeMetadata(sem, metadata))
  }
}
