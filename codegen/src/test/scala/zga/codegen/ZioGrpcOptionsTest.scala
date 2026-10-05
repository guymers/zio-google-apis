package zga.codegen

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object ZioGrpcOptionsTest extends ZIOSpecDefault {

  override val spec = suite("ZioGrpcOptions")(
    test("parses class_prefix and pkg_name") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test") ==
          Right(Some(ZioGrpcOptions(classPrefix = "Test", pkgName = "test"))),
      )
    },
    test("no options") {
      assertTrue(ZioGrpcOptions.fromStr("") == Right(None))
    },
    test("missing option") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test") ==
          Left("invalid grpc options string: class_prefix=Test, missing pkg_name"),
      )
    },
    test("unknown option") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;extra=1") ==
          Left("invalid grpc options string: class_prefix=Test;pkg_name=test;extra=1, unknown options: extra"),
      )
    },
    test("empty option value") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=;pkg_name=test") ==
          Left("invalid grpc options string: class_prefix=;pkg_name=test, missing class_prefix"),
      )
    },
  )
}
