package org.openapitools.server

import java.io.File
import java.nio.file.Files

import akka.annotation.ApiMayChange
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.{ContentType, HttpEntity, Multipart}
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives._
import akka.stream.Materializer
import akka.stream.scaladsl._

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}

trait MultipartDirectives {

  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.FutureDirectives._
  import akka.http.scaladsl.server.directives.MarshallingDirectives._

  @ApiMayChange
  def formAndFiles(fileFields: FileField*): Directive1[PartsAndFiles] =
    entity(as[Multipart.FormData]).flatMap {
      formData =>
        extractRequestContext.flatMap { ctx =>
          implicit val mat: Materializer = ctx.materializer
          implicit val ec: ExecutionContextExecutor = ctx.executionContext

          val uploadingSink: Sink[FormData.BodyPart, Future[PartsAndFiles]] =
            Sink.foldAsync[PartsAndFiles, Multipart.FormData.BodyPart](PartsAndFiles.Empty) {
              (acc, part) =>
                def discard(p: Multipart.FormData.BodyPart): Future[PartsAndFiles] = {
                  p.entity.discardBytes()
                  Future.successful(acc)
                }

                part.filename.map {
                  fileName =>
                    fileFields.find(_.fieldName == part.name)
                      .map {
                        case FileField(_, destFn) =>
                          val fileInfo = FileInfo(part.name, fileName, part.entity.contentType)
                          val dest = destFn(fileInfo)

                          part.entity.dataBytes.runWith(FileIO.toPath(dest.toPath)).map { _ =>
                            acc.addFile(fileInfo, dest)
                          }
                      }.getOrElse(discard(part))
                } getOrElse {
                  part.entity match {
                    case HttpEntity.Strict(ct: ContentType.NonBinary, data) =>
                      val charsetName = ct.charset.nioCharset.name
                      val partContent = data.decodeString(charsetName)

                      Future.successful(acc.addForm(part.name, partContent))
                    case _                                                  =>
                      discard(part)
                  }
                }
            }

          val uploadedF = formData.parts.runWith(uploadingSink)

          onSuccess(uploadedF)
        }
    }
}

object MultipartDirectives extends MultipartDirectives with FileUploadDirectives {
  val tempFileFromFileInfo: FileInfo => File = {
    (file: FileInfo) => Files.createTempFile(file.fileName, ".tmp").toFile()
  }
}

final case class FileField(fieldName: String, fileNameF: FileInfo => File = MultipartDirectives.tempFileFromFileInfo)

final case class PartsAndFiles(form: immutable.Map[String, String], files: Map[String, (FileInfo, File)]) {
    def addForm(fieldName: String, content: String): PartsAndFiles = this.copy(form.updated(fieldName, content))

    def addFile(info: FileInfo, file: File): PartsAndFiles = this.copy(
        files = files.updated(info.fieldName, (info, file))
    )
}

object PartsAndFiles {
    val Empty: PartsAndFiles = PartsAndFiles(immutable.Map.empty, immutable.Map.empty)
}