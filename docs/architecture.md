## Architecture

Generates Scala 3 case-class messages and ZIO gRPC clients from Google protobuf definitions. Code generation runs as a protoc plugin in `codegen/`.

### Module Structure

- **codegen** - protoc plugin that generates Scala messages/enums and [ZIO](https://github.com/zio/zio) gRPC clients
- **common** - generated Google common protos
- **iam** - generated Google IAM protos
- **shared** - [ZIO](https://github.com/zio/zio) gRPC runtime

#### Clients
- **cloud-run** - generated Cloud Run client
