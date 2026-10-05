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
 * `CANCELLED`, and `isReady` gates sending until [[makeReady]].
 */
private final class FakeClientCall[Req, Res](
  responses: List[Res],
  status: Status = Status.OK,
  respondOnStart: Boolean = false,
  initiallyReady: Boolean = true,
) extends ClientCall[Req, Res] {

  val requests: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty
  @volatile var cancelled: List[String] = Nil
  @volatile var sent: List[Req] = Nil
  @volatile var readyChecks = 0
  @volatile var startHeaders: Option[Metadata] = None
  @volatile var halfCloseCalls = 0

  @volatile private var ready = initiallyReady
  private var listener: ClientCall.Listener[Res] = null
  private var permits = 0
  private var pending = List.empty[Res]
  private var responding = false
  private var closed = false

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

  override def cancel(message: String, cause: Throwable) = synchronized {
    cancelled = message :: cancelled
    pending = Nil
    close(Status.CANCELLED.withDescription(message))
  }

  override def halfClose() = synchronized {
    halfCloseCalls += 1
    if (!responding) respond()
  }

  override def isReady = {
    readyChecks += 1
    ready
  }

  override def sendMessage(message: Req) = synchronized {
    sent = sent :+ message
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
    if (responding && pending.isEmpty) close(status)
  }

  private def close(status: Status) = {
    if (!closed) {
      closed = true
      listener.onClose(status, new Metadata)
    }
  }
}
