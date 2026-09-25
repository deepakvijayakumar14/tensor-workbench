package dev.tensorworkbench.api.artifact

import dev.tensorworkbench.api.storage.ArtifactStorage
import dev.tensorworkbench.api.web.ApiException
import dev.tensorworkbench.api.web.validate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.node.JsonNodeFactory

/**
 * Confirms that a worker's reported upload really exists before it can become
 * authoritative. Runs outside any database transaction (it performs network I/O).
 *
 * The worker computes SHA-256 from its local file and stores it as object metadata;
 * here we check that the stored object has the reported size and checksum metadata
 * and lives under the attempt's own key prefix.
 */
@Component
class UploadVerifier(private val storage: ArtifactStorage) {

    fun verify(
        upload: UploadedArtifact,
        field: String,
        expectedKind: ArtifactKind,
        requiredPrefix: String,
        expectedDtype: String,
        expectedShape: List<Int>?,
    ): VerifiedUpload {
        validate {
            require(upload.kind == expectedKind, "$field.kind") { "must be $expectedKind" }
            val key = required(upload.objectKey, "$field.objectKey")
            if (key != null) {
                require(key.startsWith(requiredPrefix), "$field.objectKey") { "must start with $requiredPrefix" }
            }
            val size = required(upload.sizeBytes, "$field.sizeBytes")
            if (size != null) require(size >= 0, "$field.sizeBytes") { "must be zero or greater" }
            val sha = required(upload.sha256, "$field.sha256")
            if (sha != null) require(SHA256.matches(sha), "$field.sha256") { "must be 64 lowercase hex characters" }
            require(upload.dtype == expectedDtype, "$field.dtype") { "must be $expectedDtype" }
            val shape = required(upload.shape, "$field.shape")
            if (shape != null && expectedShape != null) {
                require(shape == expectedShape, "$field.shape") { "must be $expectedShape" }
            }
        }

        val stored = storage.head(upload.objectKey!!)
            ?: throw verificationFailed("Object ${upload.objectKey} does not exist in storage")
        if (stored.sizeBytes != upload.sizeBytes) {
            throw verificationFailed("Object ${upload.objectKey} has ${stored.sizeBytes} bytes, worker reported ${upload.sizeBytes}")
        }
        if (stored.metadata["sha256"] != upload.sha256) {
            throw verificationFailed("Object ${upload.objectKey} checksum metadata does not match the reported SHA-256")
        }
        return VerifiedUpload(
            objectKey = upload.objectKey,
            contentType = upload.contentType ?: "application/octet-stream",
            sizeBytes = upload.sizeBytes!!,
            sha256 = upload.sha256!!,
            dtype = upload.dtype!!,
            shape = upload.shape!!,
            metadata = upload.metadata ?: JsonNodeFactory.instance.objectNode(),
        )
    }

    private fun verificationFailed(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ARTIFACT_VERIFICATION_FAILED", message)

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
