package zga.client

import zio.Exit
import zio.ZIO
import zio.durationInt
import zio.test.Live

// a regression hangs uninterruptibly, so the effect is disconnected to fail the test instead of hanging it
def runUninterruptible[R, E, A](effect: ZIO[R, E, A]): ZIO[R, Nothing, Exit[E, A]] = {
  Live
    .live(effect.uninterruptible.exit.disconnect.timeout(5.seconds))
    .someOrElseZIO(ZIO.dieMessage("did not complete in an uninterruptible region"))
}
