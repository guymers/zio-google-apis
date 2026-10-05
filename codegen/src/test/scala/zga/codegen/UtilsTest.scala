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
        assertTrue(Utils.generatedPackageParts("com.google.cloud.run.v2") == List("zga", "google", "cloud", "run", "v2")) &&
        assertTrue(Utils.generatedPackageParts("google.protobuf") == List("zga", "google", "protobuf"))
      },
      test("leaves other packages alone") {
        assertTrue(Utils.generatedPackageParts("test") == List("test")) &&
        assertTrue(Utils.generatedPackageParts("org.example.api") == List("org", "example", "api"))
      },
    ),
  )
}
