package zga.client.ads.error

opaque type RequestId = String
object RequestId {
  extension (id: RequestId) {
    def value: String = id
  }

  def apply(value: String): RequestId = value
}
