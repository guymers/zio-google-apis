package zga.client.storage

import com.google.protobuf.ByteString
import zga.client.storage.auth.StorageAuthentication
import zga.google.storage.BidiReadObjectRequest
import zga.google.storage.BidiReadObjectSpec
import zga.google.storage.BidiWriteObjectRequest
import zga.google.storage.BidiWriteObjectResponse
import zga.google.storage.ChecksummedData
import zga.google.storage.DeleteObjectRequest
import zga.google.storage.Object
import zga.google.storage.ReadObjectRequest
import zga.google.storage.ReadRange
import zga.google.storage.StartResumableWriteRequest
import zga.google.storage.WriteObjectRequest
import zga.google.storage.WriteObjectResponse
import zga.google.storage.WriteObjectSpec
import zga.test.GoogleIntegrationTest
import zio.Chunk
import zio.ZEnvironment
import zio.ZIO
import zio.ZLayer
import zio.stream.ZStream
import zio.test.TestAspect
import zio.test.assertTrue

object StorageObjectIntegrationTest extends GoogleIntegrationTest {

  override val spec = suite("Storage")(
    test("upload and download an object") {
      for {
        client <- ZIO.service[StorageClient]
        bucket <- ZIO.service[StorageBucket]

        name = objectName()
        data = objectData()

        written <- writeObject(client, bucket, name, data)
        read <- readObject(client, bucket, name).ensuring(deleteObject(client, bucket, name))
      } yield {
        val resource = written.writeStatus.collect { case WriteObjectResponse.WriteStatus.Resource(o) => o }
        assertTrue(resource.exists(_.name == name)) &&
        assertTrue(resource.exists(_.size == data.size.toLong)) &&
        assertTrue(read == data)
      }
    },
    test("resumable upload and download an object") {
      for {
        client <- ZIO.service[StorageClient]
        bucket <- ZIO.service[StorageBucket]

        name = objectName()
        data = objectData()

        started <- client.startResumableWrite(StartResumableWriteRequest(writeObjectSpec = Some(writeObjectSpec(bucket, name))))
        // the first message names only the upload, so the routing header comes from the upload id
        written <- {
          val req = WriteObjectRequest(
            firstMessage = Some(WriteObjectRequest.FirstMessage.UploadId(started.uploadId)),
            writeOffset = 0L,
            data = Some(WriteObjectRequest.Data.ChecksummedData(ChecksummedData(content = data))),
            finishWrite = true,
          )
          client.writeObject(ZStream.succeed(req))
        }
        read <- readObject(client, bucket, name).ensuring(deleteObject(client, bucket, name))
      } yield {
        val resource = written.writeStatus.collect { case WriteObjectResponse.WriteStatus.Resource(o) => o }
        assertTrue(resource.exists(_.name == name)) &&
        assertTrue(resource.exists(_.size == data.size.toLong)) &&
        assertTrue(read == data)
      }
    },
    test("bidi upload and download an object") {
      for {
        client <- ZIO.service[StorageClient]
        bucket <- ZIO.service[StorageBucket]

        name = objectName()
        data = objectData()

        responses <- {
          val req = BidiWriteObjectRequest(
            firstMessage = Some(BidiWriteObjectRequest.FirstMessage.WriteObjectSpec(writeObjectSpec(bucket, name))),
            writeOffset = 0L,
            data = Some(BidiWriteObjectRequest.Data.ChecksummedData(ChecksummedData(content = data))),
            finishWrite = true,
          )
          client.bidiWriteObject(ZStream.succeed(req)).runCollect
        }
        read <- readObject(client, bucket, name).ensuring(deleteObject(client, bucket, name))
      } yield {
        val resource = responses.flatMap(_.writeStatus).collect { case BidiWriteObjectResponse.WriteStatus.Resource(o) => o }
        assertTrue(resource.exists(_.name == name)) &&
        assertTrue(resource.exists(_.size == data.size.toLong)) &&
        assertTrue(read == data)
      }
    },
    test("upload and bidi download an object") {
      for {
        client <- ZIO.service[StorageClient]
        bucket <- ZIO.service[StorageBucket]

        name = objectName()
        data = objectData()

        _ <- writeObject(client, bucket, name, data)
        read <- {
          val req = BidiReadObjectRequest(
            readObjectSpec = Some(BidiReadObjectSpec(bucket = bucket.resourceName, `object` = name)),
            // a read length of 0 reads to the end of the object
            readRanges = Chunk(ReadRange(readOffset = 0L, readId = 1L)),
          )
          client
            .bidiReadObject(ZStream.succeed(req))
            .mapConcat(_.objectDataRanges)
            .takeUntil(_.rangeEnd)
            .runFold(ByteString.EMPTY)((acc, range) => range.checksummedData.fold(acc)(d => acc.concat(d.content)))
        }.ensuring(deleteObject(client, bucket, name))
      } yield {
        assertTrue(read == data)
      }
    } @@ TestAspect.ignore, // fails with GRPC_BIDI_READ_OBJECT_NOT_ALLOWLISTED
  ).provideSomeLayerShared(StorageIntegrationTest.layer >+> clientLayer) @@ StorageIntegrationTest.available

  private def objectName() = s"zga-test/${java.util.UUID.randomUUID()}.txt"

  private def objectData() = ByteString.copyFromUtf8(s"zga-test-${java.util.UUID.randomUUID()}")

  private def writeObjectSpec(bucket: StorageBucket, name: String) = WriteObjectSpec(
    resource = Some(Object(name = name, bucket = bucket.resourceName, contentType = "text/plain")),
  )

  private def writeObject(client: StorageClient, bucket: StorageBucket, name: String, data: ByteString) = {
    val req = WriteObjectRequest(
      firstMessage = Some(WriteObjectRequest.FirstMessage.WriteObjectSpec(writeObjectSpec(bucket, name))),
      writeOffset = 0L,
      data = Some(WriteObjectRequest.Data.ChecksummedData(ChecksummedData(content = data))),
      finishWrite = true,
    )
    client.writeObject(ZStream.succeed(req))
  }

  private def readObject(client: StorageClient, bucket: StorageBucket, name: String) = {
    val req = ReadObjectRequest(bucket = bucket.resourceName, `object` = name)
    client.readObject(req).runFold(ByteString.EMPTY) { (acc, response) =>
      response.checksummedData.fold(acc)(d => acc.concat(d.content))
    }
  }

  private def deleteObject(client: StorageClient, bucket: StorageBucket, name: String) = {
    client.deleteObject(DeleteObjectRequest(bucket = bucket.resourceName, `object` = name)).ignoreLogged
  }

  private def clientLayer = ZLayer.fromZIOEnvironment(for {
    channel <- ZIO.service[StorageChannel]
    auth <- ZIO.service[StorageAuthentication]
  } yield ZEnvironment.empty.add(StorageClient.create(channel, auth, io.grpc.CallOptions.DEFAULT)))
}
