// format: off

val grpcVersion = "1.83.1"
val googleCommonProtosVersion = "2.78.0"
val googleOauth2HttpVersion = "1.54.0"
val logbackVersion = "1.6.5"
val nettyVersion = "4.2.18.Final"
val protobufVersion = "4.36.2"
val zioVersion = "2.1.26"

organization := "io.github.guymers"
homepage := Some(uri("https://github.com/guymers/zio-google-apis"))
licenses := List(License.Apache2)
developers := List(
  Developer("guymers", "Sam Guymer", "@guymers", uri("https://github.com/guymers"))
)
scmInfo := Some(ScmInfo(
  uri("https://github.com/guymers/zio-google-apis"),
  "scm:git:https://github.com/guymers/zio-google-apis.git",
  Some("scm:git:git@github.com:guymers/zio-google-apis.git"),
))

scalaVersion := "3.3.8"
versionScheme := Some("early-semver")

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-release", "17",
  "-unchecked",
  "-explain",
  "-explain-types",
  "-no-indent",
  "-source:future",
  "-Xmax-inlines", "256",
  "-Wconf:name=PatternMatchExhaustivity:error",
  "-Wnonunit-statement",
  "-Wunused:all",
  "-Wvalue-discard",
)

Compile / console / scalacOptions ~= filterScalacConsoleOpts
Test / console / scalacOptions ~= filterScalacConsoleOpts

def filterScalacConsoleOpts(options: Seq[String]) = {
  options.filterNot { opt =>
    opt.startsWith("-W")
  }
}

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test" % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
)

mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet

lazy val noPublishSettings = Seq(
  publish / skip := true,
  mimaPreviousArtifacts := Set.empty,
)

lazy val root = project.in(file("."))
  .settings(name := "zio-google-apis")
  .settings(noPublishSettings)
  .aggregate(codegen)
  .aggregate(shared, sharedIT, common, iam)
  .aggregate(analytics, cloudErrorReporting, cloudKms, cloudRun, cloudTrace, merchant, secretManager, storage)
  // note not including client IT projects so `test` does not consider them
  .disablePlugins(MimaPlugin)

lazy val codegenPluginJar = taskKey[HashedVirtualFileRef]("The protoc plugin jar")
lazy val codegenProtocPlugin = taskKey[HashedVirtualFileRef]("Builds a protoc plugin script")
lazy val invalidateProtocCache = taskKey[Unit]("Deletes the cached protoc output when the codegen plugin changes")

lazy val codegen = project.in(file("codegen"))
  .settings(name := "zga-codegen")
  .settings(noPublishSettings)
  .settings(
    // dynver changes the version on every build of a dirty working tree
    version := "0.0.0",
    // generation needs to use the same field naming logic as the codec
    Compile / unmanagedSources += (LocalRootProject / baseDirectory).value / "deps/common/src/main/scala/zga/common/FieldNames.scala",
    libraryDependencies ++= Seq(
      "com.google.protobuf" % "protobuf-java" % protobufVersion,
      "com.google.api.grpc" % "proto-google-common-protos" % googleCommonProtosVersion,
    ),
    assembly / mainClass := Some("zga.codegen.Plugin"),
    // `assembly` is transient, re-expose it so the jar is part of the cache input
    codegenPluginJar := Def.uncached(assembly.value),
    codegenProtocPlugin := {
      val conv = fileConverter.value
      val jar = codegenPluginJar.value
      val script = s"""|#!/bin/sh
                       |exec java -jar '${conv.toPath(jar)}'
                       |""".stripMargin
      val scriptFile = target.value / "protoc-plugin.sh"
      IO.write(scriptFile, script)
      scriptFile.setExecutable(true)
      Def.declareOutput(conv.toVirtualFile(scriptFile.toPath))
    },
  )

def itProject(name: String, directory: Option[String]) = {
  Project(s"${name}IT", file(s"${directory.fold("")(d => s"$d/")}$name-it"))
    .settings(moduleName := s"zga-$name-it")
    .settings(
      publish / skip := true,
      Test / fork := true,
      Test / javaOptions += "-Xmx1000m",
    )
    .settings(Seq(
      Compile / javaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "java",
      Compile / scalaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "scala",
      Test / javaSource := baseDirectory.value / ".." / name / "src" / "it" / "java",
      Test / scalaSource := baseDirectory.value / ".." / name / "src" / "it" / "scala",
    ))
}

def client(name: String, classPrefix: String, pkgName: String) = {
  Project(name, file(s"clients/$name"))
    .settings(moduleName := s"zga-$name")
    .settings(Seq(
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-streams" % zioVersion,
        "io.grpc" % "grpc-netty" % grpcVersion,

        // override versions of transitive `grpc-netty` dependencies
        "io.netty" % "netty-codec-http2" % nettyVersion,
        "io.netty" % "netty-handler-proxy" % nettyVersion,
        "io.netty" % "netty-transport-native-unix-common" % nettyVersion,

        "ch.qos.logback" % "logback-classic" % logbackVersion % Test,
      ),
    ))
    .settings(Seq(
      // copy the proto files to avoid dealing with protoc and symlinks
      ProtobufConfig / protobufSources := Def.uncached {
        val sources = (ProtobufConfig / protobufSources).value
        val dest = target.value / "protobuf_unsymlink"
        val dirs = (ProtobufConfig / sourceDirectories).value
        val files = sources.flatMap { f =>
          dirs
            .map(dir => dir.toPath.relativize(f.toPath)).sortBy(_.toString).headOption
            .map(d => (f, dest.toPath.resolve(d).toFile))
        }
        IO.delete(dest)
        IO.copy(files, CopyOptions().withOverwrite(true).withPreserveLastModified(true))
        files.map(_._2)
      },
      cleanFiles += target.value / "protobuf_unsymlink",
      // reset include paths to include the copied files
      ProtobufConfig / protobufIncludePaths := Def.uncached {
        (target.value / "protobuf_unsymlink") :: Nil
      },
      ProtobufConfig / protobufIncludePaths += Def.uncached {
        (ProtobufConfig / protobufExternalIncludePath).value
      },
    ))
    .settings(protoPlugin(Map("class_prefix" -> classPrefix, "pkg_name" -> pkgName)))
    .enablePlugins(ProtobufPlugin)
    .dependsOn(
      shared,
      common % "compile->compile;protobuf->protobuf",
    )
}
def clientIT(name: String) = itProject(name, directory = Some("clients"))
  .dependsOn(sharedIT % "test->test")

lazy val analytics = client("analytics", classPrefix = "Analytics", pkgName = "analytics")

lazy val cloudErrorReporting = client("cloud-error-reporting", classPrefix = "CloudErrorReporting", pkgName = "clouderrorreporting")

lazy val cloudKms = client("cloud-kms", classPrefix = "CloudKms", pkgName = "cloudkms")

lazy val cloudRun = client("cloud-run", classPrefix = "CloudRun", pkgName = "cloudrun")
  .dependsOn(iam % "compile->compile;protobuf->protobuf")
lazy val cloudRunIT = clientIT("cloud-run")
  .dependsOn(cloudRun)

lazy val cloudTrace = client("cloud-trace", classPrefix = "CloudTrace", pkgName = "cloudtrace")

lazy val merchant = client("merchant", classPrefix = "Merchant", pkgName = "merchant")

lazy val secretManager = client("secret-manager", classPrefix = "SecretManager", pkgName = "secretmanager")
  .dependsOn(iam % "compile->compile;protobuf->protobuf")
lazy val secretManagerIT = clientIT("secret-manager")
  .dependsOn(secretManager)

lazy val storage = client("storage", classPrefix = "Storage", pkgName = "storage")
  .dependsOn(iam % "compile->compile;protobuf->protobuf")

lazy val shared = project.in(file("shared"))
  .settings(moduleName := "zga-shared")
  .settings(Seq(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "io.grpc" % "grpc-api" % grpcVersion,
      "com.google.auth" % "google-auth-library-oauth2-http" % googleOauth2HttpVersion,
    )
  ))
  .dependsOn(common)
lazy val sharedIT = itProject("shared", directory = None)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "io.grpc" % "grpc-netty" % grpcVersion,
    )
  ))
  .dependsOn(shared)

lazy val common = project.in(file("deps/common"))
  .settings(moduleName := "zga-common")
  .settings(Seq(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "io.grpc" % "grpc-api" % grpcVersion,
      "com.google.protobuf" % "protobuf-java" % protobufVersion,
      "com.google.api.grpc" % "proto-google-common-protos" % googleCommonProtosVersion % ProtobufConfig.name,
    ),
  ))
  .settings(compileExternalProtoFiles(_ / "google"))
  .settings(protoPlugin(Map.empty))
  .enablePlugins(ProtobufPlugin)

lazy val iam = project.in(file("deps/iam"))
  .settings(moduleName := "zga-iam")
  .settings(Seq(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "com.google.api.grpc" % "proto-google-iam-v1" % "1.73.0" % ProtobufConfig.name,
    ),
  ))
  .settings(compileExternalProtoFiles(_ / "google" / "iam"))
  .settings(protoPlugin(Map.empty))
  .enablePlugins(ProtobufPlugin)
  .dependsOn(common)

def protoPlugin(options: Map[String, String]) =
  ScalafmtPlugin.scalafmtConfigSettings(ProtobufConfig) ++
  inConfig(ProtobufConfig)(Seq( // referenced by scalafmt, add to config so they can be extended
    unmanagedSources := (Compile / unmanagedSources).value,
    unmanagedSourceDirectories := (Compile / unmanagedSourceDirectories).value,
  )) ++ Seq(
  ProtobufConfig / version := protobufVersion,
  ProtobufConfig / protobufProtocOptions ++= {
    val plugin = (codegen / codegenProtocPlugin).value
    val out = (Compile / sourceManaged).value
    Seq(
      s"--plugin=protoc-gen-zga=${fileConverter.value.toPath(plugin).toAbsolutePath}",
      s"--zga_out=${out}",
      s"--zga_opt=${options.toList.map((k, v) => s"$k=$v").mkString(";")}",
    )
  },
  ProtobufConfig / protobufGeneratedTargets := Seq(((Compile / sourceManaged).value, "*.scala")),
  ProtobufConfig / unmanagedSources := (ProtobufConfig / protobufGeneratedTargets).value.map(_ ** _).flatMap(_.get()),
  ProtobufConfig / unmanagedSourceDirectories := Seq.empty,
  // sbt-protobuf's generation cache keys on the .proto files and protoc options, so it ignores changes
  // to the codegen plugin behind the script. Drop that cache when the plugin jar changes, so `compile`
  // regenerates without a manual `clean`.
  invalidateProtocCache := Def.uncached {
    val pluginHash = (codegen / codegenPluginJar).value.contentHashStr
    val cacheDirectory = (ProtobufConfig / protobufGenerate / streams).value.cacheDirectory
    val stampFile = cacheDirectory / "zga-protoc-plugin.hash"
    if (!stampFile.isFile || IO.read(stampFile) != pluginHash) {
      IO.delete(cacheDirectory / s"protobuf_${scalaBinaryVersion.value}")
      IO.write(stampFile, pluginHash)
    }
  },
  ProtobufConfig / protobufGenerate := Def.uncached(Def.taskDyn {
    val generatedFiles = (ProtobufConfig / protobufGenerate).dependsOn(invalidateProtocCache).value
    Def.task {
      (ProtobufConfig / scalafmt).value
      generatedFiles
    }
  }.value),
)
Global / excludeLintKeys += ProtobufConfig / javaSource

def compileExternalProtoFiles(source: File => File) = Seq(
  ProtobufConfig / sourceDirectories += source((ProtobufConfig / protobufExternalIncludePath).value),
)
