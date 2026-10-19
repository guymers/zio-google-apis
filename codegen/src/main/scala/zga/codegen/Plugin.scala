package zga.codegen

import com.google.api.AnnotationsProto
import com.google.api.FieldBehaviorProto
import com.google.api.RoutingProto
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
      .setSupportedFeatures(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.getNumber.toLong)

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

    val filesToGenerate = request.getFileToGenerateList.asScala.toList.map(fileName => fileName -> fileDescriptors(fileName))
    val servicesOnly = zioGrpcOptions.fold(Set.empty[String])(_.servicesOnly)
    validateServicesOnly(servicesOnly, filesToGenerate)

    filesToGenerate.foreach { (fileName, f) =>
      if (!servicesOnly.contains(fileName)) {
        addFile(fileName, Generator.fileName(f), Generator.printFile(f))
        Generator.codecFiles(f).foreach((codecFileName, content) => addFile(fileName, codecFileName, content))
      }

      if (ZioGrpcGenerator.hasServices(f)) {
        zioGrpcOptions.foreach { options =>
          addFile(fileName, ZioGrpcGenerator.fileName(options, f), ZioGrpcGenerator.printServiceFile(options, f))
        }
      }
    }

    responseBuilder.build()
  }

  /**
   * A proto whose messages are provided by a dependency can have its messages
   * left out, generating only the service code that refers to them.
   */
  private def validateServicesOnly(servicesOnly: Set[String], filesToGenerate: List[(String, Descriptors.FileDescriptor)]): Unit = {
    val byName = filesToGenerate.toMap
    servicesOnly.foreach { name =>
      byName.get(name) match {
        case None =>
          throw IllegalArgumentException(s"services_only names '$name', which is not a file to generate")
        case Some(f) if f.getServices.isEmpty =>
          throw IllegalArgumentException(s"services_only names '$name', which has no services")
        case Some(_) => ()
      }
    }
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
