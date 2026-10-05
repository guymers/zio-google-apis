package zga.client

import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import zio.Trace

trait ZClientInterceptor {

  def interceptCall[Req, Res](
    methodDescriptor: MethodDescriptor[Req, Res],
    options: CallOptions,
    clientCall: ZClientCall[Req, Res],
  )(using Trace): ZClientCall[Req, Res]
}
