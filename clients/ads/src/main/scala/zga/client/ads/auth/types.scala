package zga.client.ads.auth

opaque type LinkedCustomerId = Long
object LinkedCustomerId {
  extension (id: LinkedCustomerId) {
    def value: Long = id
  }

  def apply(value: Long): LinkedCustomerId = value
}

opaque type LoginCustomerId = Long
object LoginCustomerId {
  extension (id: LoginCustomerId) {
    def value: Long = id
  }

  def apply(value: Long): LoginCustomerId = value
}
