package zga.client

import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import zio.Duration
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.durationInt

import java.util.concurrent.TimeUnit

class ZChannel(
  private[client] val channel: ManagedChannel,
  interceptors: List[ZClientInterceptor],
) {
  def newCall[Req, Res](
    methodDescriptor: MethodDescriptor[Req, Res],
    options: CallOptions,
  )(using Trace): ZIO[Any, Nothing, ZClientCall[Req, Res]] = ZIO.succeed {
    interceptors.foldLeft[ZClientCall[Req, Res]](
      ZClientCall(channel.newCall(methodDescriptor, options)),
    )((call, interceptor) => interceptor.interceptCall(methodDescriptor, options, call))
  }

  def authority: String = channel.authority()
}

object ZChannel {

  /**
   * How long channel shutdown waits for a channel to terminate before forcing
   * it closed.
   */
  val DefaultShutdownAwait = 15.seconds

  def create[T <: ManagedChannelBuilder[T]](
    builder: ManagedChannelBuilder[T],
  )(using Trace): ZIO[Scope, Throwable, ZChannel] = {
    createWithInterceptors(builder, Nil)
  }

  def createWithInterceptors[T <: ManagedChannelBuilder[T]](
    builder: ManagedChannelBuilder[T],
    interceptors: List[ZClientInterceptor],
  )(using Trace): ZIO[Scope, Throwable, ZChannel] = for {
    b <- ZChannel.setExecutors(builder)
    channel <- {
      val acquire = ZIO.attempt(b.build())
      ZIO.acquireRelease(acquire)(channel => shutdown(channel, DefaultShutdownAwait).ignore)
    }
  } yield new ZChannel(channel, interceptors)

  def setExecutors[T <: ManagedChannelBuilder[T]](
    builder: ManagedChannelBuilder[T],
  )(using Trace): ZIO[Any, Nothing, T] = for {
    executor <- ZIO.executor
    blockingExecutor <- ZIO.blockingExecutor
  } yield {
    builder
      .executor(executor.asJava)
      .offloadExecutor(blockingExecutor.asJava)
  }

  def shutdown(channel: ManagedChannel, await: Duration)(using Trace): ZIO[Any, Throwable, Boolean] = for {
    _ <- ZIO.attempt {
      channel.shutdown()
    }
    terminated <- ZIO.attemptBlocking {
      channel.awaitTermination(await.toNanos, TimeUnit.NANOSECONDS)
    }.catchSome {
      case _: InterruptedException => ZIO.succeed(channel.isTerminated)
    }
    _ <- ZIO.attempt {
      channel.shutdownNow()
    }.when(!terminated)
  } yield terminated
}
