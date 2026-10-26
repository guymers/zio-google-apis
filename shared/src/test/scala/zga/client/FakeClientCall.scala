package zga.client

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status

import scala.collection.mutable

/**
 * A `ClientCall` that models response-side flow control: a response is only
 * delivered once it has been requested.
 *
 * Responses start once the client half-closes, or straight after `start` when
 * `respondOnStart` is set, which models a server that closes the call without
 * waiting for the requests. Like gRPC, cancelling an open call closes it with
 * `CANCELLED`, after which sending or half-closing throws, and `isReady` gates
 * sending until [[makeReady]]. When `closeOnCancel` is not set, the close is
 * delayed until [[closeCancelled]], as gRPC delivers it on another thread.
 */
private final class FakeClientCall[Req, Res](
  responses: List[Res],
  status: Status = Status.OK,
  respondOnStart: Boolean = false,
  initiallyReady: Boolean = true,
  closeOnCancel: Boolean = true,
) extends ClientCall[Req, Res] {

  val requests: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty
  @volatile var cancelled: List[String] = Nil
  @volatile var sent: List[Req] = Nil
  @volatile var readyChecks = 0
  @volatile var startHeaders: Option[Metadata] = None
  @volatile var halfCloseCalls = 0
  @volatile var rejectedCalls = 0

  @volatile private var ready = initiallyReady
  private var listener: ClientCall.Listener[Res] = null
  private var permits = 0
  private var pending = List.empty[Res]
  private var responding = false
  private var closed = false
  private var cancelCalled = false

  def makeReady(): Unit = synchronized {
    ready = true
    listener.onReady()
  }

  override def start(responseListener: ClientCall.Listener[Res], headers: Metadata) = synchronized {
    listener = responseListener
    startHeaders = Option(headers)
    responseListener.onHeaders(new Metadata)
    if (respondOnStart) respond()
  }

  override def request(numMessages: Int) = synchronized {
    val _ = requests += numMessages
    permits += numMessages
    deliver()
  }

  def closeCancelled(): Unit = synchronized {
    close(Status.CANCELLED.withDescription(cancelled.last))
  }

  override def cancel(message: String, cause: Throwable) = synchronized {
    cancelled = message :: cancelled
    cancelCalled = true
    pending = Nil
    if (closeOnCancel) close(Status.CANCELLED.withDescription(message))
  }

  override def halfClose() = synchronized {
    rejectIfCancelled()
    halfCloseCalls += 1
    if (!responding) respond()
  }

  override def isReady = {
    readyChecks += 1
    ready
  }

  override def sendMessage(message: Req) = synchronized {
    rejectIfCancelled()
    sent = sent :+ message
  }

  private def rejectIfCancelled() = {
    if (cancelCalled) {
      rejectedCalls += 1
      throw new IllegalStateException("call was cancelled")
    }
  }

  private def respond() = {
    responding = true
    pending = responses
    deliver()
  }

  private def deliver() = {
    while (pending.nonEmpty && permits > 0) {
      val head = pending.head
      pending = pending.tail
      permits -= 1
      listener.onMessage(head)
    }
    if (responding && pending.isEmpty && !cancelCalled) close(status)
  }

  private def close(status: Status) = {
    if (!closed) {
      closed = true
      listener.onClose(status, new Metadata)
    }
  }
}
