# zio-google-apis

[![Maven Central](https://img.shields.io/maven-central/v/io.github.guymers/zga-shared_3)](https://central.sonatype.com/namespace/io.github.guymers)

Scala 3 [ZIO](https://zio.dev) gRPC clients for Google APIs. Requires Java 17 or later.

## Clients

Each client is a separate artifact:

```scala
libraryDependencies += "io.github.guymers" %% "<client>" % "<version>"
```

- [Ads](https://developers.google.com/google-ads/api/reference/rpc/v26/overview): `zga-ads`
- [Analytics Data](https://developers.google.com/analytics/devguides/reporting/data/v1) and [Analytics Admin](https://developers.google.com/analytics/devguides/config/admin/v1): `zga-analytics`
- [Cloud Error Reporting](https://docs.cloud.google.com/error-reporting/reference/rest): `zga-cloud-error-reporting`
- [Cloud Key Management (KMS)](https://docs.cloud.google.com/kms/docs/reference/rpc/google.cloud.kms.v1): `zga-cloud-kms`
- [Cloud Run](https://docs.cloud.google.com/run/docs/reference/rpc): `zga-cloud-run`
- [Cloud Trace](https://docs.cloud.google.com/trace/docs/reference/v2/rpc/google.devtools.cloudtrace.v2): `zga-cloud-trace`
- [Merchant Center](https://developers.google.com/merchant/api): `zga-merchant`
- [Secret Manager](https://docs.cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secretmanager.v1): `zga-secret-manager`
- [Storage](https://docs.cloud.google.com/storage/docs/reference/rpc/google.storage.v2): `zga-storage`

## Example

Accessing the latest version of a secret with Secret Manager, using [application default credentials](https://docs.cloud.google.com/docs/authentication/application-default-credentials):

```scala
import zga.auth.Credentials
import zga.client.secretmanager.SecretManagerChannel
import zga.client.secretmanager.SecretManagerClient
import zga.client.secretmanager.auth.SecretManagerAuthentication
import zga.google.cloud.secretmanager.AccessSecretVersionRequest

object Example extends zio.ZIOAppDefault {

  override val run = for {
    channel <- SecretManagerChannel.default
    googleCredentials <- Credentials.defaultGoogleCredentials
    auth = SecretManagerAuthentication.fromGoogle(googleCredentials)
    client = SecretManagerClient.create(channel, auth, io.grpc.CallOptions.DEFAULT)

    name = "projects/my-project/secrets/my-secret/versions/latest"
    response <- client.accessSecretVersion(AccessSecretVersionRequest(name))
    _ <- zio.ZIO.foreachDiscard(response.payload) { payload =>
      zio.Console.printLine(payload.data.toStringUtf8)
    }
  } yield ()
}
```

When credentials differ per call, create the client with `SecretManagerClient.unauthenticated(channel, options)` and call `.withAuth(auth)` for each request.

## Development

The clients' protos are symlinked out of the `submodules/googleapis` submodule, run `git submodule update --init` to fetch it.
