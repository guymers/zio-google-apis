package zga.error

import io.grpc.Status
import zio.Trace
import zio.ZIO
import zio.stream.ZStream

object syntax {

  extension [R, A](io: ZIO[R, Status, A]) {
    def handleStatus(using Trace): ZIO[R, Error, A] = {
      io.mapErrorCause(_.flatMap(Error.fromStatus(_)))
    }
  }

  extension [R, A](stream: ZStream[R, Status, A]) {
    def handleStatus(using Trace): ZStream[R, Error, A] = {
      stream.mapErrorCause(_.flatMap(Error.fromStatus(_)))
    }
  }
}
