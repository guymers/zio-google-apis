package zga.client

import zio.Exit
import zio.ZIO
import zio.durationInt
import zio.test.Live

// Runs `effect` in an uninterruptible region and returns its exit. A hang there
// cannot be interrupted, so the effect is disconnected to let the timeout fail
// the test instead. The timeout uses the live clock, as the test clock does not
// advance on its own.
def runUninterruptible[R, E, A](effect: ZIO[R, E, A]): ZIO[R, Nothing, Exit[E, A]] = {
  Live
    .live(effect.uninterruptible.exit.disconnect.timeout(5.seconds))
    .someOrElseZIO(ZIO.dieMessage("did not complete in an uninterruptible region"))
}
