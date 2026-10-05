# DirectPath support

Notes on adding Google Cloud [DirectPath](https://cloud.google.com/vpc/docs/about-direct-path)
support (direct ALTS connectivity to the gRPC API when running on GKE/GCE) to
`zio-google-apis`, mirroring what the official Google Java clients do.

This is a design record, not a finished feature. The only code so far is
[`zga.client.DirectPath`](shared/src/main/scala/zga/client/DirectPath.scala) and
two new `shared` dependencies.

## Status

Done:

- Added [`shared/src/main/scala/zga/client/DirectPath.scala`](shared/src/main/scala/zga/client/DirectPath.scala):
  `DirectPathConfig` plus `DirectPath.channelBuilder`, a port of gax's DirectPath
  channel construction.
- Added `io.grpc:grpc-alts` and `io.grpc:grpc-googleapis` to the `shared` project
  in [`build.sbt`](build.sbt).

Not done (needs decisions, below):

- `ZChannel` does not accept the `ManagedChannelBuilder[?]` that `DirectPath`
  returns (F-bounded generic), so `DirectPath` is not yet usable end to end.
- The per-RPC auth model conflicts with channel-level DirectPath credentials;
  the auth strategy needs to be decided.
- No `*Channel` object is wired up to `DirectPath` yet.
- No tests, no docs in `README.md`.

## How the official clients do it

Everything lives in
[`InstantiatingGrpcChannelProvider`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java)
in gax. The relevant entry point is
[`createChannelBuilder()`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L705)
(the DirectPath branch starts at line 715).

Gating:

- `GOOGLE_CLOUD_DISABLE_DIRECT_PATH=true` disables it unconditionally
  ([`isDirectPathEnabled`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L410)).
- The env var `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS=true` is **not** sufficient on
  its own. The client library must also opt in via
  `InstantiatingGrpcChannelProvider.Builder#setAttemptDirectPath(true)`. gax logs
  the env-var-only case as a misconfiguration.
- xDS vs legacy DirectPath is
  [`isDirectPathXdsEnabled`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L440)
  = `setAttemptDirectPathXds()` **or** `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS`.
- [`canUseDirectPath`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L869)
  additionally requires:
  - credentials are `ComputeEngineCredentials`, unless
    `setAllowNonDefaultServiceAccount(true)`
    ([`isCredentialDirectPathCompatible`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L494));
  - running on Compute Engine, detected by `os.name == "Linux"` and
    `/sys/class/dmi/id/product_name` containing `Google`
    ([`isOnComputeEngine`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L508));
  - the universe domain is the default (`googleapis.com`).

Channel construction when eligible:

- `GoogleDefaultChannelCredentials` with
  `MoreCallCredentials.from(credentials)`. That yields ALTS on the direct leg and
  TLS + call credentials on the CloudPath fallback.
- xDS target is `Grpc.newChannelBuilder("google-c2p:///" + host, channelCreds)`.
  The target must **not** contain a port.
- Unlike the normal path, service-config lookup is deliberately **not** disabled;
  the `google-c2p` resolver needs it.
- `keepAliveTime` 3600s / `keepAliveTimeout` 20s.
- Legacy DirectPath instead uses `Grpc.newChannelBuilderForAddress(host, port, creds)`
  plus a default service config of `loadBalancingConfig: [{pick_first: {}}]`.

The `google-c2p` resolver:

- Is registered by `io.grpc:grpc-googleapis`
  ([`GoogleCloudToProdNameResolver`](https://github.com/grpc/grpc-java/blob/master/googleapis/src/main/java/io/grpc/googleapis/GoogleCloudToProdNameResolver.java)).
- Builds its own xDS bootstrap in code (no `GRPC_XDS_BOOTSTRAP` needed): it reads
  the zone and IPv6 capability from the GCE metadata server and points at
  `directpath-pa.googleapis.com` with `google_default` channel creds.
- Off GCP it degrades to the `dns` scheme, i.e. normal CloudPath. So enabling it
  is safe off-GCP, just not faster.
- The resolver/LB owns direct-vs-CloudPath selection and fallback. That is why
  gax does not need to do the fallback itself; it only marks the channel with
  `GrpcTransportChannel.setDirectPath(...)`.

Auth: gax attaches the bearer token **at channel level**, not per RPC. That is
the important difference from this repo's design.

## What `zga.client.DirectPath` does

- `DirectPathConfig` mirrors the gax builder options and env handling, with
  `env`, `productName` and `osName` injectable so the decision is unit testable
  off GCE.
- `DirectPathConfig.canUseDirectPath(credentials)` is gax's `canUseDirectPath`.
- `DirectPath.channelBuilder(host, port, credentials, config)(fallback)` returns
  the DirectPath builder when eligible, otherwise the by-name `fallback`. The
  fallback stays a `NettyChannelBuilder` owned by each client, which keeps
  `grpc-netty` out of `shared`.
- The legacy (non-xDS) target is also implemented, for parity.

## What still needs doing

### 1. `ZChannel` cannot consume the builder

[`ZChannel.create`](shared/src/main/scala/zga/client/ZChannel.scala) is
F-bounded:

```scala
def create[T <: ManagedChannelBuilder[T]](builder: ManagedChannelBuilder[T])
```

`Grpc.newChannelBuilder` returns `ManagedChannelBuilder[?]`, which does not
satisfy that bound. Options:

- Relax `create`/`createWithInterceptors`/`setExecutors` to
  `ManagedChannelBuilder[?]`. Same erasure, so source-compatible for callers and
  mima-safe, but the chained `builder.executor(...).offloadExecutor(...)` has to
  become two statements.
- Or add `ZChannel.fromManagedChannel(channel, interceptors)`, build the channel
  inside `DirectPath`, and keep `ZChannel` untouched.

The first is nicer at call sites; the second is less churn.

### 2. Auth has to move to the channel (the real design question)

Today each generated client method does
`auth.metadata(channel.channel.authority, serviceName)` and passes the resulting
`SafeMetadata` into `ClientCalls` (see
[`ZioGrpcGenerator`](codegen/src/main/scala/zga/codegen/ZioGrpcGenerator.scala)
and the hand-written `*Authentication` classes, e.g.
[`MerchantAuthentication`](clients/merchant/src/main/scala/zga/client/merchant/auth/MerchantAuthentication.scala)).

For DirectPath the bearer token belongs on the channel
(`MoreCallCredentials.from(credentials)` inside
`GoogleDefaultChannelCredentials`). If the per-RPC interceptor still adds an
`authorization` header, the call carries two `authorization` values and will
fail. So one of:

- **Bind auth to the channel (recommended).** Have each `*Channel` own its
  matching `*Authentication`, built from the same credentials and knowing
  whether auth is channel-provided:

  ```scala
  final class MerchantChannel(val channel: ZChannel, val authentication: MerchantAuthentication)

  object MerchantChannel {
    def default(credentials: Credentials): ZIO[Scope, Throwable, MerchantChannel] = ...
  }
  ```

  Under DirectPath, `authentication.metadata` returns an empty `SafeMetadata`.
  The generated clients do not change at all, because they still just call
  `auth.metadata(...)`. Cost: `*Channel.default` gains a credentials parameter,
  and users use `channel.authentication` instead of constructing it separately.
- **No-op auth variant.** Keep the current shape and add
  `MerchantAuthentication.directPath` (empty metadata) that the user passes when
  they enabled DirectPath. Less safe, easy to get wrong.
- **Don't attach channel call credentials** and rely on the per-RPC metadata
  token. gax's `isCredentialDirectPathCompatible` suggests DirectPath needs a
  credential at channel construction, but this is worth an experiment: build an
  ALTS channel with `GoogleDefaultChannelCredentials` and no call creds, attach
  the MDS token per RPC, and see whether the backend accepts it. If it does, the
  auth change disappears. Treat as unverified.

`zga.auth.Credentials` currently hides the wrapped `GoogleAuthCredentials`
(the constructor parameter is private and there is no accessor). Channel
construction needs it, so it needs a `private[zga]` accessor or similar.

### 3. Wire up the `*Channel` objects

Each client keeps its `Host`/`Port`/scope and routes through `DirectPath`:

```scala
def builder(credentials: Credentials): ManagedChannelBuilder[?] =
  DirectPath.channelBuilder(Host, Port, credentials.underlying) {
    NettyChannelBuilder.forAddress(Host, Port).disableRetry.useTransportSecurity
  }
```

Notable host when an Ads client is added: `googleads.googleapis.com:443`. The
v22-v25 Ads protos are already in `submodules/googleapis/google/ads/googleads`,
but there is no Ads client project in `clients/` yet.

### 4. Dependencies and module shape

`DirectPath` needs `io.grpc:grpc-alts` at compile time
(`GoogleDefaultChannelCredentials`, `MoreCallCredentials`) and
`io.grpc:grpc-googleapis` at runtime (registers `google-c2p`). Both were added
to `shared`.

Caveat: `grpc-alts` is published with relocated netty and transitively pulls
`grpc-netty-shaded` plus native BoringSSL bits. Mixing with the unshaded
`grpc-netty` the clients use is fine (the shaded classes are relocated), but it
adds binary weight to `shared`, which every client depends on. If that matters,
move DirectPath into its own opt-in sbt project/module and have clients that want
it depend on that instead. `grpc-netty-shaded` cannot be excluded, because the
shaded `grpc-alts` jar references the relocated classes.

### 5. Testing

- Unit test `DirectPathConfig` with injected `env`/`productName`/`osName`: the
  disable env var, `attemptDirectPath = false`, non-GCE product name, non-Linux,
  non-`ComputeEngineCredentials`, `allowNonDefaultServiceAccount`, xDS on/off.
- `channelBuilder` is harder to assert on without a transport provider on the
  `shared` test classpath. Either assert on `canUseDirectPath` only, or pull the
  target selection into a pure `DirectPath.target(host, config)` function.
- An integration test would need to run on GKE/GCE and be gated like
  `GoogleTestEnvironment.available`; set `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS=true`
  and assert a real RPC succeeds. Hard to run in CI.

### 6. Gotchas

- The xDS target is portless (`google-c2p:///host`); adding a port breaks it.
- Do not call `disableServiceConfigLookUp()` on the c2p channel, the opposite of
  what gax does on the non-DirectPath path.
- Keep the 3600s/20s keepalive defaults.
- GKE Workload Identity uses a non-default service account, which is what
  `allowNonDefaultServiceAccount` is for.
- Off GCP the resolver silently uses DNS/CloudPath, so enabling DirectPath is
  safe but should still be validated on the platform you care about.
- `DirectPath` currently defaults to `attemptDirectPath = true`. Decide whether
  that is opt-in per client, or driven by `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS`
  as in gax.

## Alternatives considered

1. **Depend on `com.google.api:gax-grpc` and reuse
   `InstantiatingGrpcChannelProvider`.** Exact parity, including future changes,
   channel pooling and fallback marking. Downsides: pulls gax and its grpc
   version pins into a library that currently needs only `grpc-api`, and you end
   up wrapping a gax-managed `ManagedChannel`. `setAttemptDirectPath` /
   `setAttemptDirectPathXds` are public but `@InternalApi`.
2. **Escape hatch only.** Add `ZChannel.fromManagedChannel` (and/or a
   `ManagedChannelBuilder[?]` overload) and document how to build a c2p channel.
   Least code, most user burden.
3. **Port the logic (chosen for now).** No gax dependency, small and
   self-contained, at the cost of tracking upstream changes by hand.

## References

- gax: [`InstantiatingGrpcChannelProvider`](https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java)
- grpc-java: [`GoogleCloudToProdNameResolver`](https://github.com/grpc/grpc-java/blob/master/googleapis/src/main/java/io/grpc/googleapis/GoogleCloudToProdNameResolver.java)
  and [`grpc-googleapis` build](https://github.com/grpc/grpc-java/blob/master/googleapis/build.gradle)
- grpc-java: [`grpc-alts` build](https://github.com/grpc/grpc-java/blob/master/alts/build.gradle)
- Google Ads API Java client: [advanced usage](https://developers.google.com/google-ads/api/docs/client-libs/java/advanced-usage)
