package zga.client

import io.grpc.ClientCall
import io.grpc.ClientCall.Listener
import io.grpc.Status
import zio.Trace
import zio.ZIO

trait ZClientCall[Req, Res] {
  def start(listener: Listener[Res], headers: SafeMetadata)(using Trace): ZIO[Any, Status, Unit]
  def request(numMessages: Int)(using Trace): ZIO[Any, Status, Unit]
  def cancel(message: String)(using Trace): ZIO[Any, Status, Unit]
  def isReady(using Trace): ZIO[Any, Status, Boolean]
  def halfClose()(using Trace): ZIO[Any, Status, Unit]
  def sendMessage(message: Req)(using Trace): ZIO[Any, Status, Unit]
}

class ZClientCallImpl[Req, Res](private val call: ClientCall[Req, Res]) extends ZClientCall[Req, Res] {
  override def start(listener: Listener[Res], headers: SafeMetadata)(using Trace) = {
    headers.copy.flatMap(metadata => attempt(call.start(listener, metadata)))
  }
  override def request(numMessages: Int)(using Trace) = attempt(call.request(numMessages))
  override def cancel(message: String)(using Trace) = attempt(call.cancel(message, null))
  override def isReady(using Trace) = attempt(call.isReady)
  override def halfClose()(using Trace) = attempt(call.halfClose())
  override def sendMessage(message: Req)(using Trace) = attempt(call.sendMessage(message))

  private def attempt[A](effect: => A)(using Trace) = ZIO.attempt(effect).mapError(Status.fromThrowable(_))
}

object ZClientCall {
  def apply[Req, Res](call: ClientCall[Req, Res]): ZClientCall[Req, Res] = new ZClientCallImpl(call)

  class ForwardingZClientCall[Req, Res](
    protected val delegate: ZClientCall[Req, Res],
  ) extends ZClientCall[Req, Res] {
    override def start(listener: Listener[Res], headers: SafeMetadata)(using Trace) = delegate.start(listener, headers)
    override def request(numMessages: Int)(using Trace) = delegate.request(numMessages)
    override def cancel(message: String)(using Trace) = delegate.cancel(message)
    override def isReady(using Trace) = delegate.isReady
    override def halfClose()(using Trace) = delegate.halfClose()
    override def sendMessage(message: Req)(using Trace) = delegate.sendMessage(message)
  }
}
