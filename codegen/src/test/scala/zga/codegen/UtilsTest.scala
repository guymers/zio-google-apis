package zga.codegen

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object UtilsTest extends ZIOSpecDefault {

  override val spec = suite("Utils")(
    suite("escapeScalaString")(
      test("escapes a control character in a string literal") {
        assertTrue(Utils.escapeScalaString("a\nb") == "\"a\\nb\"") &&
        assertTrue(Utils.escapeScalaString("a\"b\\c") == "\"a\\\"b\\\\c\"")
      },
    ),
    suite("generatedPackageParts")(
      test("maps google packages into the zga namespace") {
        assertTrue(Utils.generatedPackageParts("com.google.rpc") == List("zga", "google", "rpc")) &&
        assertTrue(Utils.generatedPackageParts("com.google.cloud.run.v2") == List("zga", "google", "cloud", "run")) &&
        assertTrue(Utils.generatedPackageParts("google.protobuf") == List("zga", "google", "protobuf"))
      },
      test("drops the API version segment") {
        assertTrue(Utils.generatedPackageParts("com.google.cloud.secretmanager.v1") == List("zga", "google", "cloud", "secretmanager")) &&
        assertTrue(Utils.generatedPackageParts("google.analytics.admin.v1beta") == List("zga", "google", "analytics", "admin")) &&
        assertTrue(Utils.generatedPackageParts("google.cloud.speech.v1p1beta1") == List("zga", "google", "cloud", "speech")) &&
        assertTrue(Utils.generatedPackageParts("com.google.iam.v1.logging") == List("zga", "google", "iam", "logging"))
      },
      test("drops ads' redundant googleads namespace segment") {
        assertTrue(Utils.generatedPackageParts("google.ads.googleads.v17") == List("zga", "google", "ads")) &&
        assertTrue(Utils.generatedPackageParts("com.google.ads.googleads.v25.errors") == List("zga", "google", "ads", "errors")) &&
        assertTrue(Utils.generatedPackageParts("com.google.ads.googleads.v25.services") == List("zga", "google", "ads", "services"))
      },
      test("leaves other packages alone") {
        assertTrue(Utils.generatedPackageParts("test") == List("test")) &&
        assertTrue(Utils.generatedPackageParts("org.example.api") == List("org", "example", "api")) &&
        assertTrue(Utils.generatedPackageParts("org.example.v1") == List("org", "example", "v1"))
      },
    ),
    suite("chunkBy")(
      test("splits into consecutive chunks of at most the maximum weight") {
        assertTrue(Utils.chunkBy(List(1, 2, 3, 4, 1), 5)(identity) == List(List(1, 2), List(3), List(4, 1)))
      },
      test("puts a value heavier than the maximum in a chunk of its own") {
        assertTrue(Utils.chunkBy(List(1, 9, 1), 5)(identity) == List(List(1), List(9), List(1)))
      },
    ),
  )
}
