package zga.codegen

import com.google.api.AnnotationsProto
import com.google.api.FieldBehavior
import com.google.api.FieldBehaviorProto
import com.google.api.HttpRule
import com.google.api.RoutingParameter
import com.google.api.RoutingProto
import com.google.api.RoutingRule
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.MethodOptions
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.google.protobuf.DescriptorProtos.SourceCodeInfo
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry

import java.io.ByteArrayOutputStream
import scala.jdk.CollectionConverters.*

/** Builders for the descriptors the code generator tests run against. */
private[codegen] object TestDescriptors {

  /**
   * The `field_behavior = REQUIRED` option, serialised and parsed back as
   * protoc writes it.
   */
  def requiredOptions: FieldOptions = {
    val buffer = new ByteArrayOutputStream()
    val output = CodedOutputStream.newInstance(buffer)
    output.writeEnum(FieldBehaviorProto.FIELD_BEHAVIOR_FIELD_NUMBER, FieldBehavior.REQUIRED.getNumber)
    output.flush()
    val registry = ExtensionRegistry.newInstance()
    FieldBehaviorProto.registerAllExtensions(registry)
    FieldOptions.parseFrom(buffer.toByteArray, registry)
  }

  def field(
    name: String,
    number: Int,
    fieldType: FieldDescriptorProto.Type,
    label: FieldDescriptorProto.Label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
    typeName: Option[String] = None,
    options: Option[FieldOptions] = None,
  ): FieldDescriptorProto = {
    val builder = FieldDescriptorProto.newBuilder
      .setName(name)
      .setNumber(number)
      .setLabel(label)
      .setType(fieldType)
    typeName.foreach { value =>
      val _ = builder.setTypeName(value)
    }
    options.foreach { value =>
      val _ = builder.setOptions(value)
    }
    builder.build()
  }

  def stringField(
    name: String = "name",
    number: Int = 1,
    options: Option[FieldOptions] = None,
    label: FieldDescriptorProto.Label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
  ): FieldDescriptorProto =
    field(name, number, FieldDescriptorProto.Type.TYPE_STRING, label, options = options)

  def messageField(options: Option[FieldOptions]): FieldDescriptorProto =
    field("nested", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, typeName = Some(".test.Message"), options = options)

  def oneofField(
    name: String,
    number: Int,
    oneofIndex: Int,
    options: Option[FieldOptions] = None,
  ): FieldDescriptorProto =
    FieldDescriptorProto.newBuilder(stringField(name = name, number = number, options = options))
      .setOneofIndex(oneofIndex)
      .build()

  def messageWithOneofs(fields: List[FieldDescriptorProto], oneofs: List[String]): DescriptorProto.Builder =
    DescriptorProto.newBuilder
      .setName("Message")
      .addAllField(fields.asJava)
      .addAllOneofDecl(oneofs.map(name => OneofDescriptorProto.newBuilder.setName(name).build()).asJava)

  def enumFile(valueName: String, syntax: String = "proto3"): FileDescriptorProto =
    FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax(syntax)
      .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
      .addEnumType(
        EnumDescriptorProto.newBuilder
          .setName("Kind")
          .addValue(EnumValueDescriptorProto.newBuilder.setName(valueName).setNumber(0)),
      )
      .build()

  def fieldDescriptors(fields: List[FieldDescriptorProto]): List[Descriptors.FieldDescriptor] = {
    val proto = FileDescriptorProto.newBuilder
      .setName("test.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
      .addMessageType(DescriptorProto.newBuilder.setName("Message").addAllField(fields.asJava))
      .build()
    Descriptors.FileDescriptor.buildFrom(proto, Array.empty)
      .findMessageTypeByName("Message")
      .getFields
      .asScala
      .toList
  }

  /**
   * A proto3 file with no messages, used to exercise file names and package
   * handling.
   */
  def fileDescriptor(name: String, javaPackage: Option[String]): Descriptors.FileDescriptor = {
    val builder = FileDescriptorProto.newBuilder
      .setName(name)
      .setSyntax("proto3")
    javaPackage.foreach { pkg =>
      val _ = builder.setPackage(pkg).setOptions(FileOptions.newBuilder.setJavaPackage(pkg))
    }
    Descriptors.FileDescriptor.buildFrom(builder.build(), Array.empty)
  }

  def routingRule(parameters: (String, Option[String])*): RoutingRule = {
    val builder = RoutingRule.newBuilder
    parameters.foreach { case (fieldName, template) =>
      val parameter = RoutingParameter.newBuilder.setField(fieldName)
      template.foreach { value =>
        val _ = parameter.setPathTemplate(value)
      }
      val _ = builder.addRoutingParameters(parameter)
    }
    builder.build()
  }

  def getRule(path: String, additionalBindings: HttpRule*): HttpRule = {
    HttpRule.newBuilder.setGet(path).addAllAdditionalBindings(additionalBindings.asJava).build()
  }

  def method(
    name: String,
    clientStreaming: Boolean = false,
    serverStreaming: Boolean = false,
    routing: Option[RoutingRule] = None,
    http: Option[String] = None,
    httpRule: Option[HttpRule] = None,
  ): MethodDescriptorProto = {
    val builder = MethodDescriptorProto.newBuilder
      .setName(name)
      .setInputType(".test.Request")
      .setOutputType(".test.Response")
      .setClientStreaming(clientStreaming)
      .setServerStreaming(serverStreaming)
    val rule = httpRule.orElse(http.map(path => HttpRule.newBuilder.setGet(path).build()))
    if (routing.nonEmpty || rule.nonEmpty) {
      val _ = builder.setOptions(methodOptions(routing, rule))
    }
    builder.build()
  }

  def fileDescriptor(
    methods: List[MethodDescriptorProto],
    withService: Boolean,
    sourceCodeInfo: Option[SourceCodeInfo] = None,
    serviceNames: List[String] = List("TestService"),
  ): Descriptors.FileDescriptor = {
    val item = DescriptorProto.newBuilder
      .setName("Item")
      .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
    val request = DescriptorProto.newBuilder
      .setName("Request")
      .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(
        field("item", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, FieldDescriptorProto.Label.LABEL_OPTIONAL, Some(".test.Item")),
      )
      .addField(
        field("items", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, FieldDescriptorProto.Label.LABEL_REPEATED, Some(".test.Item")),
      )
      .addField(field("count", 4, FieldDescriptorProto.Type.TYPE_UINT64, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("size", 5, FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(
        field("kind", 6, FieldDescriptorProto.Type.TYPE_ENUM, FieldDescriptorProto.Label.LABEL_OPTIONAL, Some(".test.Kind")),
      )
      .addField(field("table_name", 7, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      // the fields the AIP-4222 and http.proto routing examples reference
      .addField(field("parent", 9, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("billing_project", 10, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("message_id", 11, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("user_id", 12, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("a", 13, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("b", 14, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(field("c", 15, FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Label.LABEL_OPTIONAL))
      .addField(
        FieldDescriptorProto.newBuilder
          .setName("tag")
          .setNumber(8)
          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_STRING)
          .setOneofIndex(0),
      )
      .addOneofDecl(OneofDescriptorProto.newBuilder.setName("selector"))
    val builder = FileDescriptorProto.newBuilder
      .setName("test/service.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .setOptions(FileOptions.newBuilder.setJavaPackage("test"))
      .addEnumType(
        EnumDescriptorProto.newBuilder
          .setName("Kind")
          .addValue(EnumValueDescriptorProto.newBuilder.setName("KIND_UNSPECIFIED").setNumber(0)),
      )
      .addMessageType(item)
      .addMessageType(request)
      .addMessageType(DescriptorProto.newBuilder.setName("Response"))
    if (withService) {
      serviceNames.foreach { serviceName =>
        val service = ServiceDescriptorProto.newBuilder.setName(serviceName).addAllMethod(methods.asJava)
        val _ = builder.addService(service)
      }
    }
    sourceCodeInfo.foreach { info =>
      val _ = builder.setSourceCodeInfo(info)
    }
    Descriptors.FileDescriptor.buildFrom(builder.build(), Array.empty)
  }

  def serviceDescriptor(methods: MethodDescriptorProto*): Descriptors.FileDescriptor = {
    fileDescriptor(methods.toList, withService = true)
  }

  def serviceDescriptor(serviceNames: List[String], methods: MethodDescriptorProto*): Descriptors.FileDescriptor = {
    fileDescriptor(methods.toList, withService = true, serviceNames = serviceNames)
  }

  // mirrors protoc: the options are serialised and parsed back with the extensions registered
  private def methodOptions(routing: Option[RoutingRule], http: Option[HttpRule]): MethodOptions = {
    val buffer = new ByteArrayOutputStream()
    val output = CodedOutputStream.newInstance(buffer)
    routing.foreach(rule => output.writeMessage(RoutingProto.ROUTING_FIELD_NUMBER, rule))
    http.foreach(rule => output.writeMessage(AnnotationsProto.HTTP_FIELD_NUMBER, rule))
    output.flush()
    val registry = ExtensionRegistry.newInstance()
    RoutingProto.registerAllExtensions(registry)
    AnnotationsProto.registerAllExtensions(registry)
    MethodOptions.parseFrom(buffer.toByteArray, registry)
  }
}
