package zga.test

import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import zio.Chunk
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec

abstract class GoogleIntegrationTest extends ZIOSpec[GoogleIntegrationTest.Env] {

  override def bootstrap = GoogleIntegrationTest.layer

  override val aspects = super.aspects ++ Chunk(
    TestAspect.timeout(60.seconds),
    TestAspect.withLiveEnvironment,
    GoogleTestEnvironment.available,
  )
}
object GoogleIntegrationTest {

  type Env = EventLoopGroup & ChannelFactory[SocketChannel]

  private val DefaultNumThreads = 0 // num procs * 2

  private def createEventLoopGroup(numThreads: Int): ZIO[Any, Nothing, EventLoopGroup] = ZIO.succeed {
    val factory = NioIoHandler.newFactory()
    new MultiThreadIoEventLoopGroup(numThreads, factory)
  }

  class SocketChannelFactory extends ChannelFactory[SocketChannel] {
    override def newChannel() = new io.netty.channel.socket.nio.NioSocketChannel()
  }
  object SocketChannelFactory {
    val layer = ZLayer.succeed(new SocketChannelFactory)
  }

  val layer = ZLayer.make[Env](
    ZLayer.fromZIO(createEventLoopGroup(DefaultNumThreads)),
    SocketChannelFactory.layer,
  )
}
