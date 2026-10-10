package zga.client.ads.auth

opaque type LinkedCustomerId = Long
object LinkedCustomerId {
  def apply(value: Long): LinkedCustomerId = value

  extension (id: LinkedCustomerId) {
    def value: Long = id
  }
}

opaque type LoginCustomerId = Long
object LoginCustomerId {
  def apply(value: Long): LoginCustomerId = value

  extension (id: LoginCustomerId) {
    def value: Long = id
  }
}
