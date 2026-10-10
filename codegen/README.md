# Codegen

A protoc plugin that generates Scala 3 case-class messages and enums, plus [ZIO](https://github.com/zio/zio) gRPC clients, from Google protobuf definitions.

## Build integration

`build.sbt` builds `codegen/assembly` and writes a `protoc-plugin.sh` wrapper that runs the jar. Every protobuf project passes it to protoc with `--plugin=protoc-gen-zga=...`, and generated sources are formatted with scalafmt afterward.

Plugin options go in `--zga_opt` as `;`-separated `key=value` pairs. `class_prefix` and `pkg_name` cause service clients to be generated in addition to messages and enums.

sbt-protobuf's cache only tracks the `.proto` files and protoc options, so the build drops it whenever the plugin jar changes. `sbt <client>/compile` therefore regenerates and compiles without a `clean`.

Generated sources are written to `target/out/jvm/scala-<version>/<project>/src_managed/main`.

## Generated code

The package comes from `java_package`, falling back to the proto `package`. `google.*` and `com.google.*` packages are moved under `zga.google` so they cannot clash with Google's own Java classes on the classpath, and the API version segment (`v1`, `v2`, `v1beta1`, `v1p1beta1`, ads' `v17`, ...) is dropped, so `google.cloud.secretmanager.v1` generates into `zga.google.cloud.secretmanager`. Ads' extra `googleads` segment is dropped too, so `google.ads.googleads.v25` generates into `zga.google.ads`. Generated names therefore do not change when an API moves to a new version.

### Messages and enums
 For each `.proto` file, `Generator.scala` emits the message case classes and enums, and a `<OuterClassname>Descriptors` object holding the file descriptor. Each message has a `given` `zga.common.MessageCodec`.

A message carries only the fields the `.proto` declares, so a field that a newer server sends and this project does not know about is dropped when the message is encoded again. A map field, however, always serializes with its entries in key order, so two equal messages encode to the same bytes whatever order their maps iterate in.

An open enum (proto3, or editions without `enum_type = CLOSED`) keeps numbers it does not know, so a message read from a newer server and written back does not lose the value:

```scala
sealed trait Kind { def value: Int }
object Kind {
  enum Recognized(val value: Int) extends Kind { case A extends Recognized(0) }
  export Recognized.*                           // Kind.A == Kind.Recognized.A
  case class Unrecognized private[Kind] (value: Int) extends Kind
  def fromValue(v: Int): Kind                   // Unrecognized(v) when unknown
}
```

A closed (proto2) enum is a plain Scala `enum`, as protobuf stores an unknown closed enum number in the message's unknown fields instead.

### Oneofs

A proto `oneof` becomes a nested sealed trait with a case class per member field, and a single `Option` case class parameter:

```scala
case class Operation(result: Option[Operation.Result] = None)
object Operation {
  sealed trait Result extends zga.common.Oneof
  object Result {
    case class Error(value: Status) extends Result
    case class Response(value: Any) extends Result

    given oneofCodec: zga.common.OneofCodec[Result] =
      zga.common.OneofCodec.derived[Result](
        zga.common.OneofCodec.Case.derived[Result.Error](4),
        zga.common.OneofCodec.Case.derived[Result.Response](5),
      )
  }
}
```

`None` is a oneof with no member set, which is distinct from a member set to its default value. `OneofCodec.derived` dispatches on the `Mirror.SumOf` of the cases, and each `OneofCodec.Case` derives its value codec from the case's `Mirror.ProductOf`, so the generator only names the cases and their field numbers.

A oneof is never `required` in protobuf and has no options of its own, so a required oneof cannot be declared directly. As with a required field, a required oneof drops its `None` default. The generator treats a oneof as required when every member that is not deprecated carries `(google.api.field_behavior) = REQUIRED`; a deprecated member is ignored, and a lone required member among many does not make the oneof required.

A proto3 `optional` field, which the descriptor models as a synthetic oneof, remains an `Option` field.

### Field defaults

Generated case classes give every field a default value, except fields marked as required by the proto2 `required` label or by `(google.api.field_behavior) = REQUIRED`.

Required collections keep their empty default, as a required repeated field does not have to be non-empty.

### Large messages

The JVM limits a method to 254 parameter slots, counting a `Long` or a `Double` as two, so a message with more fields than that cannot be one case class. Such a message's fields are split into consecutive chunks of at most that many slots, one nested case class per chunk named `Part1`, `Part2`, and so on, and the message exports them:

```scala
case class Metrics(
  part1: Metrics.Part1 = Metrics.Part1(),
  part2: Metrics.Part2 = Metrics.Part2(),
) {
  export part1.*
  export part2.*
}
```

A chunk's parameters keep the whole proto field name. Each chunk derives an explicit `MessageGroupCodec` against the message's own descriptor; it reads fields from the complete message and writes directly into the message's builder. Only the complete message is built and validated, so proto2 required fields can live in different chunks. Groups are distinct from ordinary nested messages, and an unmatched ordinary parameter remains an error.

Exporting chunks makes a field read as the message's own, `metrics.activeViewCtr`. Only reads, though: `copy`, `apply` and pattern matching still take the chunk parameters. Each real `oneof` counts as one parameter and can be placed in a chunk alongside ordinary fields. If the chunk parameters themselves exceed the limit, they are grouped again. A message at the limit is left as one case class.

### Service clients

When client options are set, `ZioGrpcGenerator.scala` emits a file per `.proto` with services, in `zga.client.<pkg_name>`. Each service gets:

- `trait <X>Client` and its implementation `<X>ClientLive`
- `object <X>Client` with `create`, `layer` and `object Grpc`, which holds the `METHOD_*` descriptors, `ROUTING_*` parameters and `SERVICE` descriptor

Clients take a `<class_prefix>Channel` and use `zga.client.<pkg_name>.auth.<class_prefix>Authentication`. Both are handwritten in the client project.

Names are checked before anything is written: two services that would produce the same client name, two methods that would produce the same `Grpc` member, and two protos that would produce the same Scala file all fail codegen with the names that collided.

### Request routing

Google API routing (`google.api.routing`) becomes `ROUTING_*` values of `zga.client.Routing.Parameter`. Each is an accessor that captures a path template parsed during generation, so malformed templates or field paths fail codegen. At runtime `zga.client.Routing` only matches and encodes them into the `x-goog-request-params` header. Client-streaming methods are skipped, as they have no single request.

A method without a `google.api.routing` option falls back to the variables of its `google.api.http` path, as the Google client libraries do: each sends the whole field value keyed by its field path, e.g. `name=projects/...`. The variables of the primary path come first, then those of each `additional_bindings` rule, in order and without duplicates. Explicit routing parameters replace the http variables entirely, and a routing option that declares no parameters declares no routing at all, which is how a method opts out of the implicit http routing.
