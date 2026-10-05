## Architecture

Generates Scala 3 case-class messages and ZIO gRPC clients from Google protobuf definitions. Code generation runs as a protoc plugin in `codegen/`.

### Module Structure

- **codegen** - protoc plugin that generates Scala messages/enums and [ZIO](https://github.com/zio/zio) gRPC clients
- **common** - generated Google common protos
- **iam** - generated Google IAM protos
- **shared** - [ZIO](https://github.com/zio/zio) gRPC runtime

#### Clients
- **analytics** - generated Analytics Data and Analytics Admin clients
- **cloud-error-reporting** - generated Cloud Error Reporting client
- **cloud-kms** - generated Cloud Key Management Service client
- **cloud-run** - generated Cloud Run client
- **cloud-trace** - generated Cloud Trace client
- **merchant** - generated Merchant Center client (all v1 sub-APIs)
- **secret-manager** - generated Secret Manager client
- **storage** - generated Cloud Storage client
