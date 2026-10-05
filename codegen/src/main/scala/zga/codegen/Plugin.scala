package zga.codegen

import com.google.api.AnnotationsProto
import com.google.api.FieldBehaviorProto
import com.google.api.RoutingProto
import com.google.protobuf.DescriptorProtos.Edition
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Plugin {
  import PluginProtos.CodeGeneratorRequest
  import PluginProtos.CodeGeneratorResponse

  def main(args: Array[String]): Unit = {
    val response =
      try {
        generate()
      } catch {
        case e: IllegalArgumentException =>
          errorResponse(Option(e.getMessage).getOrElse(e.toString))
        case NonFatal(e) =>
          errorResponse(stackTrace(e))
      }

    response.writeTo(System.out)
  }

  private def errorResponse(message: String) = CodeGeneratorResponse.newBuilder.setError(message).build()

  private def stackTrace(e: Throwable) = {
    val writer = new java.io.StringWriter()
    e.printStackTrace(new java.io.PrintWriter(writer))
    writer.toString
  }

  private def generate() = {
    val extensionRegistry = ExtensionRegistry.newInstance()
    FieldBehaviorProto.registerAllExtensions(extensionRegistry)
    RoutingProto.registerAllExtensions(extensionRegistry)
    AnnotationsProto.registerAllExtensions(extensionRegistry)

    generateRequest(CodeGeneratorRequest.parseFrom(System.in, extensionRegistry))
  }

  private[codegen] def generateRequest(request: CodeGeneratorRequest) = {
    val zioGrpcOptions = ZioGrpcOptions.fromStr(request.getParameter) match {
      case Left(err) => throw new IllegalArgumentException(err)
      case Right(options) => options
    }

    val fileDescriptors = protoFilesToFiles(request)

    val responseBuilder = CodeGeneratorResponse.newBuilder
      .setSupportedFeatures(
        CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.getNumber.toLong |
        CodeGeneratorResponse.Feature.FEATURE_SUPPORTS_EDITIONS.getNumber.toLong,
      )
      .setMinimumEdition(Edition.EDITION_PROTO2.getNumber)
      .setMaximumEdition(Edition.EDITION_2026.getNumber)

    // protoc refuses to write the same file twice, but its error does not say which protos collided
    val generatedFiles = mutable.Map.empty[String, String]

    def addFile(protoFileName: String, scalaFileName: String, content: => String): Unit = {
      generatedFiles.get(scalaFileName) match {
        case None =>
          generatedFiles(scalaFileName) = protoFileName
          val file = CodeGeneratorResponse.File.newBuilder
            .setName(scalaFileName)
            .setContent(content)
            .build
          val _ = responseBuilder.addFile(file)
        case Some(previous) => throw IllegalArgumentException(s"'$protoFileName' and '$previous' both generate the Scala file '$scalaFileName'")
      }
    }

    request.getFileToGenerateList.asScala.foreach { fileName =>
      val f = fileDescriptors(fileName)

      addFile(fileName, Generator.fileName(f), Generator.printFile(f))

      if (ZioGrpcGenerator.hasServices(f)) {
        zioGrpcOptions.foreach { options =>
          addFile(fileName, ZioGrpcGenerator.fileName(options, f), ZioGrpcGenerator.printServiceFile(options, f))
        }
      }
    }

    responseBuilder.build()
  }

  private def protoFilesToFiles(request: PluginProtos.CodeGeneratorRequest) = {
    val protoFiles = request.getProtoFileList.asScala.toList
    val protoByName = protoFiles.map(f => f.getName -> f).toMap

    val descriptorsByName = mutable.Map.empty[String, Descriptors.FileDescriptor]

    def buildFileDescriptor(name: String): Descriptors.FileDescriptor =
      descriptorsByName.getOrElseUpdate(
        name, {
          val fileProto = protoByName.getOrElse(name, throw IllegalArgumentException(s"Missing FileDescriptorProto for dependency: $name"))
          val dependencies = fileProto.getDependencyList.asScala.map(buildFileDescriptor).toArray
          Descriptors.FileDescriptor.buildFrom(fileProto, dependencies)
        },
      )

    protoFiles.map { fileProto =>
      val name = fileProto.getName
      name -> buildFileDescriptor(name)
    }.toMap
  }

}
