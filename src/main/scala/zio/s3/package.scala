/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio

import software.amazon.awssdk.auth.credentials.{ AwsCredentials, AwsCredentialsProvider }

import java.net.URI
import java.util.concurrent.CompletableFuture
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.S3Exception
import zio.blocking.Blocking
import zio.nio.file.{ Path => ZPath }
import zio.s3.S3Bucket.S3BucketListing
import zio.s3.providers.const
import zio.stream.{ Stream, ZStream, ZTransducer }

package object s3 {
  type S3          = Has[S3.Service]
  type Settings    = Has[S3Settings]
  type S3Stream[A] = ZStream[S3, S3Exception, A]

  /**
   * The `S3` module provides access to a s3 amazon storage.
   * All operations are async since we are relying on the amazon async client
   */
  object S3 {

    trait Service { self =>

      /**
       * Create a bucket
       *
       * @param bucketName name of the bucket
       */
      def createBucket(bucketName: String): IO[S3Exception, Unit]

      /**
       * Delete bucket, the operation fail if bucket is not present
       *
       * @param bucketName name of the bucket
       */
      def deleteBucket(bucketName: String): IO[S3Exception, Unit]

      /**
       * Check if bucket exists
       *
       * @param bucketName name of the bucket
       */
      def isBucketExists(bucketName: String): IO[S3Exception, Boolean]

      /**
       * List all available buckets
       */
      val listBuckets: IO[S3Exception, S3BucketListing]

      /**
       * delete an object from a bucket, if not present it will succeed
       *
       * @param bucketName name of the bucket
       * @param key object identifier to remove
       */
      def deleteObject(bucketName: String, key: String): IO[S3Exception, Unit]

      /**
       * Read an object from a bucket, the operation fail if object is not present
       *
       * @param bucketName name of the bucket
       * @param key object identifier to read
       * @return
       */
      def getObject(bucketName: String, key: String): Stream[S3Exception, Byte]

      /**
       * Retrieves metadata from an object without returning the object itself.
       * This operation is useful if you're only interested in an object's metadata.
       * @param bucketName name of the bucket
       * @param key object identifier to read
       * @return the [[ObjectMetadata]]
       */
      def getObjectMetadata(bucketName: String, key: String): IO[S3Exception, ObjectMetadata]

      /**
       * list all object for a specific bucket
       *
       * @param bucketName name of the bucket
       * @param prefix filter all object key by the prefix, default value is an empty string
       * @param maxKeys max total number of objects, default value is 1000 elements
       */
      def listObjects(bucketName: String): IO[S3Exception, S3ObjectListing] =
        listObjects(bucketName, ListObjectOptions.default)

      def listObjects(bucketName: String, options: ListObjectOptions): IO[S3Exception, S3ObjectListing]

      /**
       * Fetch the next object listing from a specific object listing.
       *
       * @param listing listing to use as a start
       */
      def getNextObjects(listing: S3ObjectListing): IO[S3Exception, S3ObjectListing]

      /**
       * Store data object into a specific bucket
       *
       * @param bucketName name of the bucket
       * @param key unique object identifier
       * @param contentLength length of the data in bytes
       * @param content object data
       * @return
       */
      def putObject[R](
        bucketName: String,
        key: String,
        contentLength: Long,
        content: ZStream[R, Throwable, Byte],
        options: UploadOptions = UploadOptions.default
      ): ZIO[R, S3Exception, Unit]

      /**
       * *
       *
       * Store data object into a specific bucket, minimun size of the data is 5 Mb to use multipartt upload (restriction from amazon API)
       *
       * @param bucketName name of the bucket
       * @param key unique object identifier
       * @param content object data
       * @param options the optional configurations of the multipart upload
       * @param parallelism the number of parallel requests to upload chunks
       */
      def multipartUpload[R](
        bucketName: String,
        key: String,
        content: ZStream[R, Throwable, Byte],
        options: MultipartUploadOptions = MultipartUploadOptions.default
      )(parallelism: Int): ZIO[R, S3Exception, Unit]

      /**
       * Read an object by lines
       *
       * @param bucketName name of the bucket
       * @param key: unique key of the object
       */
      def streamLines(bucketName: String, key: String): Stream[S3Exception, String] =
        self
          .getObject(bucketName, key)
          .transduce(ZTransducer.utf8Decode >>> ZTransducer.splitLines)

      /**
       * List all descendant objects of a bucket
       * Fetch all objects recursively of all nested directory by traversing all of them
       *
       * @param bucketName name of the bucket
       * @param prefix filter all object identifier which start with this `prefix`
       *
       * MaxKeys have a default value to 1000 elements
       */
      def listAllObjects(bucketName: String): Stream[S3Exception, S3ObjectSummary] =
        listAllObjects(bucketName, ListObjectOptions.default)

      def listAllObjects(bucketName: String, options: ListObjectOptions): Stream[S3Exception, S3ObjectSummary] =
        ZStream
          .fromEffect(self.listObjects(bucketName, options))
          .flatMap(
            paginate(_).mapConcat(_.objectSummaries)
          )

      /**
       * List all objects by traversing all nested directories
       *
       * @param initialListing object listing to start with
       * @return
       */
      def paginate(initialListing: S3ObjectListing): Stream[S3Exception, S3ObjectListing] =
        ZStream.paginateM(initialListing) {
          case current @ S3ObjectListing(_, _, _, _, None, _) => ZIO.succeed(current -> None)
          case current                                        => self.getNextObjects(current).map(next => current -> Some(next))
        }

      /**
       * *
       * expose safely amazon s3 async client
       *
       * @param f call any operations on s3 async client
       * @tparam T value type to return
       */
      def execute[T](f: S3AsyncClient => CompletableFuture[T]): IO[S3Exception, T]
    }
  }

  def live(region: Region, credentials: AwsCredentials, uriEndpoint: Option[URI] = None): Layer[S3Exception, S3] =
    liveM(region, const(credentials.accessKeyId, credentials.secretAccessKey), uriEndpoint)

  def liveM[R](
    region: Region,
    provider: RManaged[R, AwsCredentialsProvider],
    uriEndpoint: Option[URI] = None
  ): ZLayer[R, S3Exception, S3] =
    ZLayer.fromManaged(
      ZManaged
        .fromEffect(ZIO.fromEither(S3Region.from(region)))
        .flatMap(Live.connect(_, provider, uriEndpoint))
    )

  def settings[R](region: Region, cred: ZIO[R, S3Exception, AwsCredentials]): ZLayer[R, S3Exception, Settings] =
    ZLayer.fromEffect(cred.flatMap(S3Settings.from(region, _)))

  val live: ZLayer[Settings, ConnectionError, S3] = ZLayer.fromFunctionManaged(s =>
    Live.connect(
      s.get.s3Region,
      const(s.get.credentials.accessKeyId, s.get.credentials.secretAccessKey),
      None
    )
  )

  def stub(path: ZPath): ZLayer[Blocking, Nothing, S3] =
    ZLayer.fromFunction(Test.connect(path))

  def listAllObjects(bucketName: String): S3Stream[S3ObjectSummary] =
    ZStream.accessStream[S3](_.get.listAllObjects(bucketName))

  def listAllObjects(bucketName: String, options: ListObjectOptions): S3Stream[S3ObjectSummary] =
    ZStream.accessStream[S3](_.get.listAllObjects(bucketName, options))

  def paginate(initialListing: S3ObjectListing): S3Stream[S3ObjectListing] =
    ZStream.accessStream[S3](_.get.paginate(initialListing))

  def streamLines(bucketName: String, key: String): S3Stream[String] =
    ZStream.accessStream[S3](_.get.streamLines(bucketName, key))

  def createBucket(bucketName: String): ZIO[S3, S3Exception, Unit] =
    ZIO.accessM(_.get.createBucket(bucketName))

  def deleteBucket(bucketName: String): ZIO[S3, S3Exception, Unit] =
    ZIO.accessM(_.get.deleteBucket(bucketName))

  def isBucketExists(bucketName: String): ZIO[S3, S3Exception, Boolean] =
    ZIO.accessM(_.get.isBucketExists(bucketName))

  val listBuckets: ZIO[S3, S3Exception, S3BucketListing] =
    ZIO.accessM(_.get.listBuckets)

  def deleteObject(bucketName: String, key: String): ZIO[S3, S3Exception, Unit] =
    ZIO.accessM(_.get.deleteObject(bucketName, key))

  def getObject(bucketName: String, key: String): ZStream[S3, S3Exception, Byte] =
    ZStream.accessStream(_.get.getObject(bucketName, key))

  def getObjectMetadata(bucketName: String, key: String): ZIO[S3, S3Exception, ObjectMetadata] =
    ZIO.accessM(_.get.getObjectMetadata(bucketName, key))

  /**
   * Same as listObjects with default values for an empty prefix and sets the maximum number of object max to `1000`
   *
   * @param bucketName name of the bucket
   */
  def listObjects(bucketName: String): ZIO[S3, S3Exception, S3ObjectListing] =
    ZIO.accessM(_.get.listObjects(bucketName))

  def listObjects(bucketName: String, options: ListObjectOptions): ZIO[S3, S3Exception, S3ObjectListing] =
    ZIO.accessM(_.get.listObjects(bucketName, options))

  def getNextObjects(listing: S3ObjectListing): ZIO[S3, S3Exception, S3ObjectListing] =
    ZIO.accessM(_.get.getNextObjects(listing))

  def putObject[R](
    bucketName: String,
    key: String,
    contentLength: Long,
    content: ZStream[R, Throwable, Byte],
    options: UploadOptions = UploadOptions.default
  ): ZIO[S3 with R, S3Exception, Unit] =
    ZIO.accessM[S3 with R](_.get.putObject(bucketName, key, contentLength, content, options))

  /**
   * Same as multipartUpload with default parallelism = 1
   *
   * @param bucketName name of the bucket
   * @param key unique object identifier
   * @param content object data
   * @param options the optional configurations of the multipart upload
   */
  def multipartUpload[R](
    bucketName: String,
    key: String,
    content: ZStream[R, Throwable, Byte],
    options: MultipartUploadOptions = MultipartUploadOptions.default
  )(parallelism: Int): ZIO[S3 with R, S3Exception, Unit] =
    ZIO.accessM[S3 with R](
      _.get.multipartUpload(bucketName, key, content, options)(parallelism)
    )

  def execute[T](f: S3AsyncClient => CompletableFuture[T]): ZIO[S3, S3Exception, T] =
    ZIO.accessM(_.get.execute(f))
}
