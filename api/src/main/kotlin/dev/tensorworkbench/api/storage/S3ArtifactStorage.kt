package dev.tensorworkbench.api.storage

import dev.tensorworkbench.api.config.WorkbenchProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

class S3ArtifactStorage(
    private val client: S3Client,
    /** Signs URLs for the browser-reachable endpoint, which differs from the in-network one. */
    private val publicPresigner: S3Presigner,
    private val bucket: String,
) : ArtifactStorage {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun head(key: String): StoredObject? = try {
        val response = client.headObject { it.bucket(bucket).key(key) }
        StoredObject(key, response.contentLength(), response.metadata().mapKeys { it.key.lowercase() })
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }

    override fun readRange(key: String, offset: Long, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        val range = "bytes=$offset-${offset + length - 1}"
        return client.getObjectAsBytes { it.bucket(bucket).key(key).range(range) }.asByteArray()
    }

    override fun presignDownload(key: String, downloadFilename: String, ttl: Duration): URI {
        val presigned = publicPresigner.presignGetObject { request ->
            request.signatureDuration(ttl)
            request.getObjectRequest {
                it.bucket(bucket).key(key)
                    .responseContentDisposition("attachment; filename=\"$downloadFilename\"")
            }
        }
        return presigned.url().toURI()
    }

    override fun list(prefix: String): Sequence<ObjectListing> =
        client.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }
            .contents()
            .asSequence()
            .map { ObjectListing(it.key(), it.size(), it.lastModified()) }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(key) }
    }

    override fun ensureBucket() {
        val exists = try {
            client.headBucket { it.bucket(bucket) }
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }
        if (!exists) {
            log.info("Creating development bucket {}", bucket)
            client.createBucket { it.bucket(bucket) }
        }
    }
}

@Configuration
class StorageConfig(private val props: WorkbenchProperties) {

    @Bean(destroyMethod = "close")
    fun s3Client(): S3Client = S3Client.builder()
        .endpointOverride(props.storage.endpoint)
        .region(Region.of(props.storage.region))
        .credentialsProvider(credentials())
        .forcePathStyle(true)
        // S3-compatible stores differ in their support for newer default checksums.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()

    @Bean(destroyMethod = "close")
    fun publicPresigner(): S3Presigner = S3Presigner.builder()
        .endpointOverride(props.storage.publicEndpoint)
        .region(Region.of(props.storage.region))
        .credentialsProvider(credentials())
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    @Bean
    fun artifactStorage(s3Client: S3Client, publicPresigner: S3Presigner): ArtifactStorage =
        S3ArtifactStorage(s3Client, publicPresigner, props.storage.bucket)

    @EventListener(ApplicationReadyEvent::class)
    fun initializeBucket(event: ApplicationReadyEvent) {
        if (props.storage.createBucket) {
            event.applicationContext.getBean(ArtifactStorage::class.java).ensureBucket()
        }
    }

    private fun credentials() = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(props.storage.accessKey, props.storage.secretKey),
    )
}
