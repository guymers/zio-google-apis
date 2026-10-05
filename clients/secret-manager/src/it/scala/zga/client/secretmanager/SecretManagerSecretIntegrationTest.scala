package zga.client.secretmanager

import com.google.protobuf.ByteString
import zga.client.secretmanager.auth.SecretManagerAuthentication
import zga.google.cloud.secretmanager.AccessSecretVersionRequest
import zga.google.cloud.secretmanager.AddSecretVersionRequest
import zga.google.cloud.secretmanager.CreateSecretRequest
import zga.google.cloud.secretmanager.GetSecretRequest
import zga.google.cloud.secretmanager.Replication
import zga.google.cloud.secretmanager.Secret
import zga.google.cloud.secretmanager.SecretPayload
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
        client <- ZIO.service[SecretManagerClient]
        projectId <- ZIO.service[GoogleCloudProjectId]

        parent = s"projects/$projectId"
        name = s"$parent/secrets/$SecretId"
        data = s"zga-test-${java.util.UUID.randomUUID()}"

        secret <- client.getSecret(GetSecretRequest(name)).catchSome {
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
            client.createSecret(create)
        }
        version <- {
          val req = AddSecretVersionRequest(
            parent = secret.name,
            payload = Some(SecretPayload(ByteString.copyFromUtf8(data))),
          )
          client.addSecretVersion(req)
        }
        accessed <- client.accessSecretVersion(AccessSecretVersionRequest(version.name))
      } yield {
        assertTrue(secret.name == name) &&
        assertTrue(version.name.startsWith(s"$name/versions/")) &&
        assertTrue(accessed.payload.exists(_.data.toStringUtf8 == data))
      }
    },
  ).provideSomeLayerShared(SecretManagerIntegrationTest.layer >+> clientLayer)

  private def clientLayer = ZLayer.fromZIOEnvironment(for {
    channel <- ZIO.service[SecretManagerChannel]
    auth <- ZIO.service[SecretManagerAuthentication]
  } yield ZEnvironment.empty.add(SecretManagerClient.create(channel, auth, io.grpc.CallOptions.DEFAULT)))
}
