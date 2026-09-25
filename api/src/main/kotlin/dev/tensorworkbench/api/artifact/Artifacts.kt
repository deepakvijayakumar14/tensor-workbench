package dev.tensorworkbench.api.artifact

import dev.tensorworkbench.api.db.instant
import dev.tensorworkbench.api.db.intList
import dev.tensorworkbench.api.db.toPgIntArray
import dev.tensorworkbench.api.db.uuid
import dev.tensorworkbench.api.db.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

enum class ArtifactKind { INPUT_TENSOR, OUTPUT_TENSOR, PREVIEW_STACK }

data class Artifact(
    val id: UUID,
    val kind: ArtifactKind,
    val datasetId: UUID?,
    val attemptId: UUID?,
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val dtype: String,
    val shape: List<Int>,
    val metadata: JsonNode,
    val createdAt: Instant,
)

/** What a worker reports after uploading an object. */
data class UploadedArtifact(
    val kind: ArtifactKind?,
    val objectKey: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val dtype: String?,
    val shape: List<Int>?,
    val metadata: JsonNode?,
)

data class ArtifactView(
    val kind: ArtifactKind,
    val objectKey: String,
    val sizeBytes: Long,
    val sha256: String,
    val dtype: String,
    val shape: List<Int>,
) {
    companion object {
        fun of(a: Artifact) = ArtifactView(a.kind, a.objectKey, a.sizeBytes, a.sha256, a.dtype, a.shape)
    }
}

@Repository
class ArtifactRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    private val rowMapper = RowMapper { rs, _ ->
        Artifact(
            id = rs.uuid("id"),
            kind = ArtifactKind.valueOf(rs.getString("kind")),
            datasetId = rs.uuidOrNull("dataset_id"),
            attemptId = rs.uuidOrNull("attempt_id"),
            objectKey = rs.getString("object_key"),
            contentType = rs.getString("content_type"),
            sizeBytes = rs.getLong("size_bytes"),
            sha256 = rs.getString("sha256"),
            dtype = rs.getString("dtype"),
            shape = rs.intList("shape"),
            metadata = mapper.readTree(rs.getString("metadata")),
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(
        kind: ArtifactKind,
        datasetId: UUID?,
        attemptId: UUID?,
        upload: VerifiedUpload,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO artifacts (id, kind, dataset_id, attempt_id, object_key, content_type, size_bytes, sha256, dtype, shape, metadata)
            VALUES (:id, :kind, :datasetId, :attemptId, :key, :contentType, :size, :sha, :dtype, CAST(:shape AS integer[]), CAST(:metadata AS jsonb))
            """,
        )
            .param("id", id)
            .param("kind", kind.name)
            .param("datasetId", datasetId)
            .param("attemptId", attemptId)
            .param("key", upload.objectKey)
            .param("contentType", upload.contentType)
            .param("size", upload.sizeBytes)
            .param("sha", upload.sha256)
            .param("dtype", upload.dtype)
            .param("shape", upload.shape.toPgIntArray())
            .param("metadata", mapper.writeValueAsString(upload.metadata))
            .update()
        return id
    }

    fun inputForDataset(datasetId: UUID): Artifact? =
        jdbc.sql("SELECT * FROM artifacts WHERE dataset_id = :id AND kind = 'INPUT_TENSOR'")
            .param("id", datasetId)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun forAttempt(attemptId: UUID): List<Artifact> =
        jdbc.sql("SELECT * FROM artifacts WHERE attempt_id = :id ORDER BY kind")
            .param("id", attemptId)
            .query(rowMapper)
            .list()

    fun forAttempt(attemptId: UUID, kind: ArtifactKind): Artifact? =
        jdbc.sql("SELECT * FROM artifacts WHERE attempt_id = :id AND kind = :kind")
            .param("id", attemptId)
            .param("kind", kind.name)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun allObjectKeys(): Set<String> =
        jdbc.sql("SELECT object_key FROM artifacts").query(String::class.java).list().filterNotNull().toSet()
}

/** An upload whose existence, size and checksum metadata were confirmed in storage. */
data class VerifiedUpload(
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val dtype: String,
    val shape: List<Int>,
    val metadata: JsonNode,
)
