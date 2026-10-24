package zga.client.storage

import zga.client.storage.auth.StorageAuthentication
import zga.test.GoogleIntegrationTest
import zga.test.GoogleTestEnvironment
import zio.ZLayer
import zio.test.Annotations
import zio.test.TestAspect
import zio.test.TestAspectAtLeastR

object StorageIntegrationTest {

  type Env = StorageChannel & StorageAuthentication & StorageBucket

  private val BucketEnvVarKey = "ZGA_TEST_GOOGLE_STORAGE_BUCKET"
  private val bucketEnvVar = sys.env.get(BucketEnvVarKey).filter(_.nonEmpty)

  val available: TestAspectAtLeastR[Annotations] = {
    if (bucketEnvVar.isDefined) TestAspect.identity else TestAspect.ignore
  }

  private val bucketLayer = ZLayer.succeed {
    val name = bucketEnvVar.getOrElse(throw new IllegalStateException(s"$BucketEnvVarKey variable not defined"))
    StorageBucket(name)
  }

  private val channelLayer = GoogleTestEnvironment.channel(StorageChannel.builder, StorageChannel(_))

  private val authenticationLayer = GoogleTestEnvironment.authentication(StorageAuthentication.fromGoogle)

  val layer = ZLayer.makeSome[GoogleIntegrationTest.Env, Env](
    channelLayer,
    authenticationLayer,
    bucketLayer,
  )
}
