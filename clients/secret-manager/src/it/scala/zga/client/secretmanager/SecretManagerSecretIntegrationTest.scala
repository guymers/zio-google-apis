package zga.client.secretmanager

import com.google.protobuf.ByteString
import zga.client.secretmanager.auth.SecretManagerAuthentication
import zga.google.cloud.secretmanager.v1.AccessSecretVersionRequest
import zga.google.cloud.secretmanager.v1.AddSecretVersionRequest
import zga.google.cloud.secretmanager.v1.CreateSecretRequest
import zga.google.cloud.secretmanager.v1.GetSecretRequest
import zga.google.cloud.secretmanager.v1.Replication
import zga.google.cloud.secretmanager.v1.Secret
import zga.google.cloud.secretmanager.v1.SecretPayload
import zga.test.GoogleCloudProjectId
import zga.test.GoogleIntegrationTest
import zio.ZEnvironment
import zio.ZIO
import zio.ZLayer
import zio.test.assertTrue

object SecretManagerSecretIntegrationTest extends GoogleIntegrationTest {

  private val SecretId = "ZGA_TEST"

  override val spec = suite("SecretManager")(
    test("create, add version, access version") {
      for {
        auth <- ZIO.service[SecretManagerAuthentication]
        client <- ZIO.service[SecretManagerClient]
        projectId <- ZIO.service[GoogleCloudProjectId]

        parent = s"projects/$projectId"
        name = s"$parent/secrets/$SecretId"
        data = s"zga-test-${java.util.UUID.randomUUID()}"

        secret <- client.getSecret(auth, GetSecretRequest(name)).catchSome {
          case _: zga.error.Error.NotFound =>
            val create = CreateSecretRequest(
              parent = parent,
              secretId = SecretId,
              secret = Some(Secret(
                replication = Some(
                  Replication(Some(Replication.Replication.Automatic(Replication.Automatic()))),
                ),
              )),
            )
            client.createSecret(auth, create)
        }
        version <- {
          val req = AddSecretVersionRequest(
            parent = secret.name,
            payload = Some(SecretPayload(ByteString.copyFromUtf8(data))),
          )
          client.addSecretVersion(auth, req)
        }
        accessed <- client.accessSecretVersion(auth, AccessSecretVersionRequest(version.name))
      } yield {
        assertTrue(secret.name == name) &&
        assertTrue(version.name.startsWith(s"$name/versions/")) &&
        assertTrue(accessed.payload.exists(_.data.toStringUtf8 == data))
      }
    },
  ).provideSomeLayerShared(SecretManagerIntegrationTest.layer >+> clientLayer)

  private def clientLayer = ZLayer.fromZIOEnvironment(for {
    channel <- ZIO.service[SecretManagerChannel]
  } yield ZEnvironment.empty.add(SecretManagerClient.create(channel, io.grpc.CallOptions.DEFAULT)))
}
