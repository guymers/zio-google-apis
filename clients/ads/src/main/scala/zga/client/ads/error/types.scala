package zga.client.ads.error

opaque type RequestId = String
object RequestId {
  def apply(value: String): RequestId = value

  extension (id: RequestId) {
    def value: String = id
  }
}
