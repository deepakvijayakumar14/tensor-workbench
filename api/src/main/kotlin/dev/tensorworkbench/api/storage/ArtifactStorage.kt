package dev.tensorworkbench.api.storage

import java.net.URI
import java.time.Duration
import java.time.Instant

data class StoredObject(
    val key: String,
    val sizeBytes: Long,
    /** User metadata written by the uploader (x-amz-meta-*), lower-case keys. */
    val metadata: Map<String, String>,
)

data class ObjectListing(val key: String, val sizeBytes: Long, val lastModified: Instant)

/**
 * The API's view of object storage. It never reads whole tensors: it checks that
 * uploads exist, reads small byte ranges for previews and signs download URLs.
 */
interface ArtifactStorage {
    fun head(key: String): StoredObject?

    /** Reads [length] bytes starting at [offset]. Callers must keep ranges small. */
    fun readRange(key: String, offset: Long, length: Int): ByteArray

    fun presignDownload(key: String, downloadFilename: String, ttl: Duration): URI

    fun list(prefix: String): Sequence<ObjectListing>

    fun delete(key: String)

    fun ensureBucket()
}
