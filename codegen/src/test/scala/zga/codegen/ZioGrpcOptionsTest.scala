package zga.codegen

import com.google.api.RoutingParameter
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object ZioGrpcOptionsTest extends ZIOSpecDefault {

  override val spec = suite("ZioGrpcOptions")(
    test("parses class_prefix and pkg_name") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test") ==
          Right(Some(ZioGrpcOptions(classPrefix = "Test", pkgName = "test", servicesOnly = Set.empty, routing = Map.empty))),
      )
    },
    test("parses services_only") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;services_only=a.proto,b.proto") ==
          Right(Some(ZioGrpcOptions(classPrefix = "Test", pkgName = "test", servicesOnly = Set("a.proto", "b.proto"), routing = Map.empty))),
      )
    },
    test("parses routing, keeping the order of a method's parameters") {
      val routing = "routing=test.TestService.Put:spec.bucket:{bucket=**},test.TestService.Put:name,test.TestService.Get:name"
      assertTrue(
        ZioGrpcOptions.fromStr(s"class_prefix=Test;pkg_name=test;$routing").map(_.map(_.routing)) ==
          Right(Some(Map(
            "test.TestService.Put" -> List(
              RoutingParameter.newBuilder.setField("spec.bucket").setPathTemplate("{bucket=**}").build,
              RoutingParameter.newBuilder.setField("name").build,
            ),
            "test.TestService.Get" -> List(
              RoutingParameter.newBuilder.setField("name").build,
            ),
          ))),
      )
    },
    test("invalid routing") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;routing=test.TestService.Put") ==
          Left("invalid grpc options string: class_prefix=Test;pkg_name=test;routing=test.TestService.Put, invalid routing 'test.TestService.Put'"),
      )
    },
    test("missing routing value") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;routing=") ==
          Left("invalid grpc options string: class_prefix=Test;pkg_name=test;routing=, missing routing"),
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
    test("repeated option") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;pkg_name=other") ==
          Left("invalid grpc options string: class_prefix=Test;pkg_name=test;pkg_name=other, repeated options: pkg_name"),
      )
    },
    test("value containing '='") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test=").map(_.map(_.pkgName)) == Right(Some("test=")),
      )
    },
    test("missing services_only value") {
      assertTrue(
        ZioGrpcOptions.fromStr("class_prefix=Test;pkg_name=test;services_only=") ==
          Left("invalid grpc options string: class_prefix=Test;pkg_name=test;services_only=, missing services_only"),
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
