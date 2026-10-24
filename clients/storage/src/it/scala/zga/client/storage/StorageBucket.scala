package zga.client.storage

case class StorageBucket(name: String) {
  // the resource name of a bucket, buckets are globally unique so the project is `_`
  def resourceName: String = s"projects/_/buckets/$name"
  override def toString = name
}
