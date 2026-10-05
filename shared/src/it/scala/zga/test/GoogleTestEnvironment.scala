package zga.test

import zga.auth.Credentials
import zio.ZLayer
import zio.test.Annotations
import zio.test.TestAspect
import zio.test.TestAspectAtLeastR

object GoogleTestEnvironment {

  private val EnvVarKey = "ZGA_TEST_GOOGLE_DEFAULT_CREDENTIALS_AVAILABLE"
  private val envVar = sys.env.get(EnvVarKey).filter(_.nonEmpty)

  private val ProjectEnvVarKey = "ZGA_TEST_GOOGLE_PROJECT_ID"
  private val projectEnvVar = sys.env.get(ProjectEnvVarKey).filter(_.nonEmpty).flatMap(_.toLongOption)

  val available: TestAspectAtLeastR[Annotations] = {
    if (envVar.isDefined && projectEnvVar.isDefined) TestAspect.identity else TestAspect.ignore
  }

  val credentials = ZLayer.fromZIO(Credentials.defaultGoogleCredentials)

  val projectId = ZLayer.succeed {
    val id = projectEnvVar.getOrElse(throw new IllegalStateException("ProjectEnv variable not defined"))
    GoogleCloudProjectId(id)
  }
}

case class GoogleCloudProjectId(value: Long) {
  override def toString = value.toString
}
