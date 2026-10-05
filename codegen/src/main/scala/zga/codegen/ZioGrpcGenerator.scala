package zga.codegen

import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.MethodDescriptor
import com.google.protobuf.Descriptors.ServiceDescriptor

import java.util.Locale
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

// inspired by https://github.com/scalapb/zio-grpc/blob/v0.4.1/code-gen/src/main/scala/scalapb/zio_grpc/ZioCodeGenerator.scala
object ZioGrpcGenerator {

  private val CallOptions = "_root_.io.grpc.CallOptions"
  private val Deadline = "_root_.io.grpc.Deadline"
  private val MethodDescriptorType = "_root_.io.grpc.MethodDescriptor"
  private val ServiceDescriptorType = "_root_.io.grpc.ServiceDescriptor"
  private val Status = "_root_.io.grpc.Status"
  private val ClientCalls = "_root_.zga.client.ClientCalls"
  private val Error = "_root_.zga.error.Error"
  private val Trace = "_root_.zio.Trace"
  private val ZIO = "_root_.zio.ZIO"
  private val ZLayer = "_root_.zio.ZLayer"
  private val ZStream = "_root_.zio.stream.ZStream"
  private val MessageCodec = "_root_.zga.common.MessageCodec"
  private val RoutingType = "_root_.zga.client.Routing"

  def hasServices(f: Descriptors.FileDescriptor): Boolean = !f.getServices.isEmpty

  def fileName(options: ZioGrpcOptions, f: Descriptors.FileDescriptor): String = {
    s"zga/client/${options.pkgName}/${f.protoFileName}.scala"
  }

  def printServiceFile(options: ZioGrpcOptions, f: Descriptors.FileDescriptor): String = {
    val services = f.getServices.asScala.toList
    validateServiceNames(f, services)
    val routingParameters = RoutingParameters.parse(f)
    s"""${Comments.header(f).map(Comments.formatAsMultiLineComment(_)).getOrElse("")}
      |package zga.client.${options.pkgName}
      |
      |${services.map(ZioGrpcGenerator.printService(options, _, routingParameters)).mkString("\n")}
      |""".stripMargin
  }

  private def validateServiceNames(f: Descriptors.FileDescriptor, services: List[ServiceDescriptor]) = {
    services.groupBy(clientName).collectFirst {
      case (name, duplicates) if duplicates.length > 1 => (name, duplicates)
    }.foreach { case (name, duplicates) =>
      val protoNames = duplicates.map(_.getName).sorted.mkString("'", "', '", "'")
      throw IllegalArgumentException(s"${f.getFullName} has services $protoNames, which all map to the generated name '$name'")
    }
  }

  private def validateMethodNames(service: ServiceDescriptor, methods: List[MethodDescriptor]) = {
    methods.groupBy(methodConstant).collectFirst {
      case (name, duplicates) if duplicates.length > 1 => (name, duplicates)
    }.foreach { case (name, duplicates) =>
      val protoNames = duplicates.map(_.getName).sorted.mkString("'", "', '", "'")
      throw IllegalArgumentException(s"${service.getFullName} has methods $protoNames, which all map to the generated name '$name'")
    }
  }

  private def printService(
    options: ZioGrpcOptions,
    service: ServiceDescriptor,
    routingParameters: RoutingParameters,
  ) = {
    val channel = s"${options.classPrefix}Channel"
    val client = clientName(service)
    val auth = s"_root_.zga.client.${options.pkgName}.auth.${options.classPrefix}Authentication"
    val methods = service.getMethods.asScala.toList
    validateMethodNames(service, methods)

    val implementations = methods.map(implementation(_, client, auth, service.getFullName, routingParameters)).mkString("\n")
    val interfaces = methods.map(interface(_, auth)).mkString("\n")
    val grpcMembers = methods.flatMap(m => methodDescriptor(service, m) :: routingConstant(m, routingParameters).toList).mkString("\n\n")
    val grpcService = serviceDescriptor(service)
    val serviceDoc = scaladoc(service.comment)

    s"""class ${client}Live(
      |  channel: $channel,
      |  options: $CallOptions
      |) extends $client {
      |  import _root_.zga.error.syntax.*
      |
      |  $implementations
      |}
      |
      |object $client {
      |
      |  def create(
      |    channel: $channel,
      |    options: $CallOptions
      |  ): $client = {
      |    new ${client}Live(channel, options)
      |  }
      |
      |  object Grpc {
      |    $grpcMembers
      |
      |    $grpcService
      |  }
      |
      |  def layer(
      |    channel: $channel,
      |    options: $CallOptions
      |  ): $ZLayer[_root_.scala.Any, _root_.scala.Nothing, $client] = {
      |    $ZLayer.succeed(create(channel, options))
      |  }
      |}
      |
      |$serviceDoc trait $client {
      |  $interfaces
      |}
      |""".stripMargin
  }

  private def scaladoc(comment: Option[String]) = {
    comment.map(Comments.formatAsScaladoc(_, ListMap.empty) + "\n").getOrElse("")
  }

  private def interface(method: MethodDescriptor, auth: String) = {
    s"""${scaladoc(method.comment)}def ${methodName(method)}${methodTypeParams(method)}(
      |  auth: $auth,
      |  request: ${methodInType(method)},
      |  deadline: => _root_.scala.Option[$Deadline] = _root_.scala.None
      |)(using $Trace): ${methodOutType(method)}""".stripMargin
  }

  private def implementation(
    method: MethodDescriptor,
    clientName: String,
    auth: String,
    serviceName: String,
    routingParameters: RoutingParameters,
  ) = {
    val call = method match {
      case m if m.isClientStreaming && m.isServerStreaming => s"$ClientCalls.bidirectional"
      case m if m.isClientStreaming => s"$ClientCalls.clientStreaming"
      case m if m.isServerStreaming => s"$ClientCalls.serverStreaming"
      case _ => s"$ClientCalls.unary"
    }
    val authCall = s"auth.metadata(channel.channel.authority, ${Utils.escapeScalaString(serviceName)}).handleStatus"
    val metadata = if (method.isServerStreaming) s"$ZStream.fromZIO($authCall)" else authCall
    val callArgs = s"channel.channel, $clientName.Grpc.${methodConstant(method)}, opts, headers, request"
    val body =
      if (routingParameters.get(method).isEmpty) {
        s"""val opts = deadline.fold(options)(d => options.withDeadline(d))
          |$call($callArgs)""".stripMargin
      } else {
        val setRequestParams = s"$RoutingType.setRequestParams(headers, request, $clientName.Grpc.${routingConstantName(method)})"
        val effect = if (method.isServerStreaming) s"$ZStream.fromZIO($setRequestParams)" else setRequestParams
        s"""$effect *> {
          |  val opts = deadline.fold(options)(d => options.withDeadline(d))
          |  $call($callArgs)
          |}""".stripMargin
      }
    s"""def ${methodName(method)}${methodTypeParams(method)}(
      |  auth: $auth,
      |  request: ${methodInType(method)},
      |  deadline: => _root_.scala.Option[$Deadline] = _root_.scala.None
      |)(using $Trace): ${methodOutType(method)} = {
      |  $metadata.flatMap { headers =>
      |    $body
      |  }
      |}""".stripMargin
  }

  private def methodDescriptor(service: ServiceDescriptor, method: MethodDescriptor) = {
    val in = method.getInputType.scalaType
    val out = method.getOutputType.scalaType
    val methodType = (method.isClientStreaming, method.isServerStreaming) match {
      case (false, false) => "UNARY"
      case (true, false) => "CLIENT_STREAMING"
      case (false, true) => "SERVER_STREAMING"
      case (true, true) => "BIDI_STREAMING"
    }

    s"""val ${methodConstant(method)}: $MethodDescriptorType[$in, $out] =
      |  $MethodDescriptorType.newBuilder()
      |    .setType($MethodDescriptorType.MethodType.$methodType)
      |    .setFullMethodName($MethodDescriptorType.generateFullMethodName("${service.getFullName}", "${method.getName}"))
      |    .setSampledToLocalTracing(true)
      |    .setRequestMarshaller($MessageCodec.forMessage[$in])
      |    .setResponseMarshaller($MessageCodec.forMessage[$out])
      |    .build()
      |""".stripMargin
  }

  private def serviceDescriptor(service: ServiceDescriptor) = {
    val methods = service.getMethods.asScala.map(m => s".addMethod(${methodConstant(m)})").mkString("\n")
    s"""val SERVICE: $ServiceDescriptorType =
      |  $ServiceDescriptorType.newBuilder("${service.getFullName}")
      |    $methods
      |    .build()
      |""".stripMargin
  }

  private def methodInType(method: MethodDescriptor) = {
    val in = method.getInputType.scalaType
    if (method.isClientStreaming) s"$ZStream[R, $Status, $in]" else in
  }

  private def methodOutType(method: MethodDescriptor) = {
    val out = method.getOutputType.scalaType
    (method.isClientStreaming, method.isServerStreaming) match {
      case (false, false) => s"$ZIO[_root_.scala.Any, $Error, $out]"
      case (true, false) => s"$ZIO[R, $Error, $out]"
      case (false, true) => s"$ZStream[_root_.scala.Any, $Error, $out]"
      case (true, true) => s"$ZStream[R, $Error, $out]"
    }
  }

  private def methodTypeParams(method: MethodDescriptor) = {
    if (method.isClientStreaming) "[R]" else ""
  }

  private def clientName(service: ServiceDescriptor) = {
    s"${service.getName.stripSuffix("Service")}Client"
  }

  private def methodName(method: MethodDescriptor) = {
    val name = method.getName
    val lowerCased = if (name.isEmpty) name else name.head.toLower.toString + name.tail
    Utils.escapeScalaKeyword(lowerCased)
  }

  private def methodConstant(method: MethodDescriptor) = {
    s"METHOD_${method.getName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT)}"
  }

  private def routingConstantName(method: MethodDescriptor) = {
    s"ROUTING_${methodConstant(method).stripPrefix("METHOD_")}"
  }

  private def routingConstant(method: MethodDescriptor, routingParameters: RoutingParameters) = {
    val parameters = routingParameters.get(method)
    Option.when(parameters.nonEmpty) {
      val requestType = method.getInputType.scalaType
      val parameterLiterals = parameters.map { parameter =>
        val fieldValue = s"(request: $requestType) => ${routingFieldValue(parameter)}"
        parameter.pathTemplate match {
          case None =>
            s"""_root_.zga.client.Routing.parameter(
              |        ${Utils.escapeScalaString(parameter.field)},
              |        $fieldValue,
              |      )""".stripMargin
          case Some(template) =>
            s"""_root_.zga.client.Routing.parameter(
              |        ${routingTemplate(template)},
              |        $fieldValue,
              |      )""".stripMargin
        }
      }.mkString(",\n      ")
      s"""val ${routingConstantName(method)}: _root_.scala.List[_root_.zga.client.Routing.Parameter[$requestType]] = {
        |  _root_.scala.List(
        |      $parameterLiterals
        |  )
        |}""".stripMargin
    }
  }

  private def routingTemplate(template: Routing.Template) = {
    s"_root_.zga.client.Routing.Template(_root_.scala.List(${template.segments.map(routingSegment).mkString(", ")}))"
  }

  private def routingSegment(segment: Routing.Segment): String = segment match {
    case Routing.Segment.Single =>
      "_root_.zga.client.Routing.Segment.Single"
    case Routing.Segment.Multi =>
      "_root_.zga.client.Routing.Segment.Multi"
    case Routing.Segment.Literal(value) =>
      s"_root_.zga.client.Routing.Segment.Literal(${Utils.escapeScalaString(value)})"
    case Routing.Segment.Variable(name, pattern) =>
      s"_root_.zga.client.Routing.Segment.Variable(${Utils.escapeScalaString(name.value)}, _root_.scala.List(${pattern.map(routingSegment).mkString(", ")}))"
  }

  private def routingFieldValue(parameter: Routing.Parameter) = {
    extractField("request", parameter.fields)
  }

  private def extractField(current: String, fields: List[Descriptors.FieldDescriptor]): String = {
    def accessor(field: Descriptors.FieldDescriptor): (String, Boolean) = {
      Option(field.getRealContainingOneof) match {
        case None =>
          (s"$current.${Utils.escapeScalaKeyword(field.scalaName)}", field.hasPresence)
        case Some(oneof) =>
          val expression =
            s"$current.${oneof.scalaName}.collect { case ${oneof.scalaType}.${field.oneofCaseName}(value) => value }"
          (expression, true)
      }
    }

    fields match {
      case Nil =>
        "_root_.scala.None"
      case field :: Nil =>
        val (expression, present) = accessor(field)
        if (field.getJavaType == Descriptors.FieldDescriptor.JavaType.STRING) {
          if (present) expression else s"_root_.scala.Some($expression)"
        } else if (present) {
          s"$expression.map(value => ${formatRoutingValue(field, "value")})"
        } else {
          s"_root_.scala.Some(${formatRoutingValue(field, expression)})"
        }
      case field :: rest =>
        val (expression, present) = accessor(field)
        if (present) {
          s"$expression.flatMap(value => ${extractField("value", rest)})"
        } else {
          extractField(expression, rest)
        }
    }
  }

  /**
   * Formats a non-string routing value as the Google client libraries do. A
   * generated enum case is named after its proto value, so `toString` gives the
   * proto name. An unrecognized value of an open enum has no name, so its
   * number is sent.
   */
  private def formatRoutingValue(field: Descriptors.FieldDescriptor, value: String) = field.getType match {
    case Descriptors.FieldDescriptor.Type.UINT32 | Descriptors.FieldDescriptor.Type.FIXED32 =>
      s"_root_.java.lang.Integer.toUnsignedString($value)"
    case Descriptors.FieldDescriptor.Type.UINT64 | Descriptors.FieldDescriptor.Type.FIXED64 =>
      s"_root_.java.lang.Long.toUnsignedString($value)"
    case Descriptors.FieldDescriptor.Type.ENUM if !field.getEnumType.isClosed =>
      val enumType = field.getEnumType.scalaType
      s"""($value match {
        |  case unrecognized: $enumType.Unrecognized => unrecognized.value.toString
        |  case recognized: $enumType.Recognized => recognized.toString
        |})""".stripMargin
    case _ =>
      s"$value.toString"
  }
}

opaque type RoutingParameters = Map[String, List[Routing.Parameter]]
object RoutingParameters {
  extension (params: RoutingParameters) {
    def get(method: MethodDescriptor) = params.getOrElse(method.getFullName, Nil)
  }

  def parse(f: Descriptors.FileDescriptor): RoutingParameters = {
    f.getServices.asScala
      .flatMap(_.getMethods.asScala)
      .map(method => method.getFullName -> Routing.parameters(method))
      .toMap
  }
}
