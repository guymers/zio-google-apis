package zga.client

import com.google.auth.Credentials as GoogleAuthCredentials
import com.google.auth.oauth2.ComputeEngineCredentials
import io.grpc.Grpc
import io.grpc.ManagedChannelBuilder
import io.grpc.alts.GoogleDefaultChannelCredentials
import io.grpc.auth.MoreCallCredentials

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.util.Try

/**
 * Configuration for [[DirectPath]].
 *
 * Mirrors the environment variable handling and builder options of gax's
 * [[https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java InstantiatingGrpcChannelProvider]].
 * `env`, `productName` and `osName` are injectable so that the decision can be
 * unit tested without running on Compute Engine.
 *
 * @param attemptDirectPath
 *   whether the client opts in to DirectPath, equivalent to
 *   `InstantiatingGrpcChannelProvider.Builder#setAttemptDirectPath`. DirectPath
 *   is never attempted when this is false, even if the environment variable is
 *   set.
 * @param attemptDirectPathXds
 *   whether to use the xDS (`google-c2p`) variant, equivalent to
 *   `InstantiatingGrpcChannelProvider.Builder#setAttemptDirectPathXds`. When
 *   false the legacy DirectPath target is used unless
 *   `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS` is set.
 * @param allowNonDefaultServiceAccount
 *   whether to allow credentials that are not
 *   [[com.google.auth.oauth2.ComputeEngineCredentials]], equivalent to
 *   `Builder#setAllowNonDefaultServiceAccount`. Needed for e.g. GKE Workload
 *   Identity.
 */
final case class DirectPathConfig(
  attemptDirectPath: Boolean = true,
  attemptDirectPathXds: Boolean = true,
  allowNonDefaultServiceAccount: Boolean = false,
  env: String => Option[String] = DirectPathConfig.systemEnv,
  productName: () => Option[String] = DirectPathConfig.gceProductName,
  osName: () => Option[String] = DirectPathConfig.systemOsName,
) {

  /**
   * Whether DirectPath has been opted in to and not disabled by
   * `GOOGLE_CLOUD_DISABLE_DIRECT_PATH`, equivalent to
   * `InstantiatingGrpcChannelProvider#isDirectPathEnabled`.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L410
   */
  def directPathEnabled: Boolean = {
    attemptDirectPath && !env(DirectPath.DisableEnv).exists(_.toBoolean)
  }

  /**
   * Whether the xDS (`google-c2p`) variant of DirectPath is enabled, equivalent
   * to `InstantiatingGrpcChannelProvider#isDirectPathXdsEnabled`.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L440
   */
  def directPathXdsEnabled: Boolean = {
    attemptDirectPathXds || env(DirectPath.EnableXdsEnv).exists(_.toBoolean)
  }

  /**
   * DirectPath is only available on Linux Compute Engine instances, equivalent
   * to `InstantiatingGrpcChannelProvider#isOnComputeEngine`.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L508
   */
  def onComputeEngine: Boolean = {
    osName().contains(DirectPath.LinuxOsName) && productName().exists { name =>
      name.contains(DirectPath.GceProductionNamePrior2016) || name.contains(DirectPath.GceProductionNameAfter2016)
    }
  }

  /**
   * DirectPath requires a Compute Engine credential, equivalent to
   * `InstantiatingGrpcChannelProvider#isCredentialDirectPathCompatible`.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L494
   */
  def credentialDirectPathCompatible(credentials: GoogleAuthCredentials): Boolean = {
    allowNonDefaultServiceAccount || credentials.isInstanceOf[ComputeEngineCredentials]
  }

  /**
   * Whether DirectPath can be used for the given credentials, equivalent to
   * `InstantiatingGrpcChannelProvider#canUseDirectPath`.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L869
   */
  def canUseDirectPath(credentials: GoogleAuthCredentials): Boolean = {
    directPathEnabled && credentialDirectPathCompatible(credentials) && onComputeEngine
  }
}

object DirectPathConfig {

  val default: DirectPathConfig = DirectPathConfig()

  def systemEnv(name: String): Option[String] = sys.env.get(name)

  def systemOsName(): Option[String] = Option(System.getProperty("os.name"))

  /**
   * The product name read by gax to detect Compute Engine, an empty file means
   * not on Compute Engine.
   */
  def gceProductName(): Option[String] = {
    Try {
      new String(Files.readAllBytes(Paths.get("/sys/class/dmi/id/product_name")), StandardCharsets.UTF_8).trim
    }.toOption.filter(_.nonEmpty)
  }
}

/**
 * Support for Google Cloud DirectPath, based on the Java client library's
 * `InstantiatingGrpcChannelProvider`.
 *
 * When DirectPath is enabled and the client is running on a Compute Engine
 * instance the channel is built with
 * [[io.grpc.alts.GoogleDefaultChannelCredentials]] and the `google-c2p` name
 * resolver, so that traffic can use ALTS over the direct path, falling back to
 * TLS over the public path as decided by the resolver.
 *
 * The `google-c2p` resolver is provided by `io.grpc:grpc-googleapis`. Without
 * that artifact on the classpath, and without `io.grpc:grpc-alts` to compile
 * against, [[channelBuilder]] would fail at runtime or not compile.
 *
 * Ported from:
 *   - [[https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L705 createChannelBuilder]]
 *     (the DirectPath branch is at line 715)
 *   - [[https://github.com/grpc/grpc-java/blob/master/googleapis/src/main/java/io/grpc/googleapis/GoogleCloudToProdNameResolver.java GoogleCloudToProdNameResolver]]
 *     (the resolver and its bundled xDS bootstrap)
 */
object DirectPath {

  /**
   * `GOOGLE_CLOUD_DISABLE_DIRECT_PATH`, when `true` DirectPath is never
   * attempted.
   */
  val DisableEnv: String = "GOOGLE_CLOUD_DISABLE_DIRECT_PATH"

  /**
   * `GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS`, enables the xDS variant of
   * DirectPath.
   */
  val EnableXdsEnv: String = "GOOGLE_CLOUD_ENABLE_DIRECT_PATH_XDS"

  /**
   * `google-c2p` is CloudToProd(C2P) DirectPath, the scheme is registered by
   * `io.grpc.googleapis.GoogleCloudToProdNameResolverProvider`.
   */
  val C2pScheme: String = "google-c2p:///"

  val KeepAliveSeconds: Long = 3600L

  val KeepAliveTimeoutSeconds: Long = 20L

  private[client] val LinuxOsName: String = "Linux"

  private[client] val GceProductionNamePrior2016: String = "Google"

  private[client] val GceProductionNameAfter2016: String = "Google Compute Engine"

  /**
   * The default service config used by the legacy (non xDS) DirectPath target,
   * `pick_first` so that channel pooling does not multiply subchannels.
   *
   * https://github.com/googleapis/sdk-platform-java/blob/main/gax-java/gax-grpc/src/main/java/com/google/api/gax/grpc/InstantiatingGrpcChannelProvider.java#L1444
   */
  private[client] val DirectPathServiceConfig: java.util.Map[String, Any] = {
    val pickFirst = new java.util.HashMap[String, Any]()
    pickFirst.put("pick_first", new java.util.HashMap[String, Any]())
    val loadBalancingConfig = new java.util.ArrayList[Any]()
    loadBalancingConfig.add(pickFirst)
    val serviceConfig = new java.util.HashMap[String, Any]()
    serviceConfig.put("loadBalancingConfig", loadBalancingConfig)
    serviceConfig
  }

  /**
   * Whether DirectPath will be used for the given credentials.
   */
  def canUseDirectPath(
    credentials: GoogleAuthCredentials,
    config: DirectPathConfig = DirectPathConfig.default,
  ): Boolean = {
    config.canUseDirectPath(credentials)
  }

  /**
   * Builds a channel builder for `host:port`, using DirectPath when
   * [[DirectPathConfig.canUseDirectPath]] is true and the given `fallback`
   * otherwise.
   *
   * `fallback` is only evaluated when DirectPath is not used, so a client can
   * keep using its existing [[io.grpc.netty.NettyChannelBuilder]] based
   * builder.
   */
  def channelBuilder(
    host: String,
    port: Int,
    credentials: GoogleAuthCredentials,
    config: DirectPathConfig = DirectPathConfig.default,
  )(fallback: => ManagedChannelBuilder[?]): ManagedChannelBuilder[?] = {
    if (config.canUseDirectPath(credentials)) {
      directPathChannelBuilder(host, port, credentials, config)
    } else {
      fallback
    }
  }

  private def directPathChannelBuilder(
    host: String,
    port: Int,
    credentials: GoogleAuthCredentials,
    config: DirectPathConfig,
  ): ManagedChannelBuilder[?] = {
    // altsCallCredentials may be null and GoogleDefaultChannelCredentials will
    // solely use callCredentials.
    val channelCredentials = GoogleDefaultChannelCredentials
      .newBuilder()
      .callCredentials(MoreCallCredentials.from(credentials))
      .build()

    val builder =
      if (config.directPathXdsEnabled) {
        // This resolver target must not have a port number, and unlike the non
        // DirectPath path the service config lookup must stay enabled because
        // the google-c2p resolver requires it.
        Grpc.newChannelBuilder(s"$C2pScheme$host", channelCredentials)
      } else {
        val addressBuilder = Grpc.newChannelBuilderForAddress(host, port, channelCredentials)
        val _ = addressBuilder.defaultServiceConfig(DirectPathServiceConfig)
        addressBuilder
      }

    // Defaults when DirectPath is enabled, overridden by user defined values.
    val _ = builder.keepAliveTime(KeepAliveSeconds, TimeUnit.SECONDS)
    val _ = builder.keepAliveTimeout(KeepAliveTimeoutSeconds, TimeUnit.SECONDS)
    builder
  }
}
