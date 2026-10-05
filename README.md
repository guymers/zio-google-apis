# zio-google-apis

Scala 3 [ZIO](https://zio.dev) gRPC clients for Google APIs.

## Clients

- [Analytics Data](https://developers.google.com/analytics/devguides/reporting/data/v1)
- [Analytics Admin](https://developers.google.com/analytics/devguides/config/admin/v1)
- [Cloud Error Reporting](https://docs.cloud.google.com/error-reporting/reference/rest)
- [Cloud Key Management (KMS)](https://docs.cloud.google.com/kms/docs/reference/rpc/google.cloud.kms.v1)
- [Cloud Run](https://docs.cloud.google.com/run/docs/reference/rpc)
- [Cloud Trace](https://docs.cloud.google.com/trace/docs/reference/v2/rpc/google.devtools.cloudtrace.v2)
- [Merchant Center](https://developers.google.com/merchant/api)
- [Secret Manager](https://docs.cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secretmanager.v1)
- [Storage](https://docs.cloud.google.com/storage/docs/reference/rpc/google.storage.v2)

## Requirements

Java 17 or later.

The clients' protos are symlinked out of the `submodules/googleapis` submodule, run `git submodule update --init` to fetch it.
