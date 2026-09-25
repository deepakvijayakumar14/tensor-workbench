package dev.tensorworkbench.api.dataset

import dev.tensorworkbench.api.db.instant
import dev.tensorworkbench.api.db.instantOrNull
import dev.tensorworkbench.api.db.intList
import dev.tensorworkbench.api.db.longOrNull
import dev.tensorworkbench.api.db.toPgIntArray
import dev.tensorworkbench.api.db.uuid
import dev.tensorworkbench.api.web.Page
import dev.tensorworkbench.api.web.PageRequest
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class DatasetStatus { QUEUED, GENERATING, READY, FAILED }

data class Dataset(
    val id: UUID,
    val shape: List<Int>,
    val dtype: String,
    val seed: Long,
    val generatorVersion: String,
    val status: DatasetStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
    val leaseOwner: String?,
    val leaseToken: Long?,
    val leaseExpiresAt: Instant?,
    val errorCategory: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val readyAt: Instant?,
) {
    val elementCount: Long get() = shape.fold(1L) { acc, d -> acc * d }
}

@Repository
class DatasetRepository(private val jdbc: JdbcClient) {

    private val rowMapper = RowMapper { rs, _ ->
        Dataset(
            id = rs.uuid("id"),
            shape = rs.intList("shape"),
            dtype = rs.getString("dtype"),
            seed = rs.getLong("seed"),
            generatorVersion = rs.getString("generator_version"),
            status = DatasetStatus.valueOf(rs.getString("status")),
            attemptCount = rs.getInt("attempt_count"),
            maxAttempts = rs.getInt("max_attempts"),
            leaseOwner = rs.getString("lease_owner"),
            leaseToken = rs.longOrNull("lease_token"),
            leaseExpiresAt = rs.instantOrNull("lease_expires_at"),
            errorCategory = rs.getString("error_category"),
            errorMessage = rs.getString("error_message"),
            createdAt = rs.instant("created_at"),
            readyAt = rs.instantOrNull("ready_at"),
        )
    }

    fun insert(id: UUID, shape: List<Int>, seed: Long, generatorVersion: String, maxAttempts: Int) {
        jdbc.sql(
            """
            INSERT INTO datasets (id, shape, dtype, seed, generator_version, status, max_attempts)
            VALUES (:id, CAST(:shape AS integer[]), 'int32', :seed, :generator, 'QUEUED', :maxAttempts)
            """,
        )
            .param("id", id)
            .param("shape", shape.toPgIntArray())
            .param("seed", seed)
            .param("generator", generatorVersion)
            .param("maxAttempts", maxAttempts)
            .update()
    }

    fun find(id: UUID): Dataset? =
        jdbc.sql("SELECT * FROM datasets WHERE id = :id").param("id", id).query(rowMapper).optional().orElse(null)

    fun page(request: PageRequest): Page<Dataset> {
        val items = jdbc.sql("SELECT * FROM datasets ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
            .param("limit", request.size)
            .param("offset", request.offset)
            .query(rowMapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM datasets").query(Long::class.java).single()
        return Page(items, request.page, request.size, total)
    }

    /**
     * Atomically claims the oldest eligible dataset. SKIP LOCKED lets concurrent
     * claimers pass over rows another transaction is claiming right now.
     */
    fun claimNext(owner: String, lease: Duration): Dataset? =
        jdbc.sql(
            """
            WITH candidate AS (
                SELECT id FROM datasets
                WHERE status = 'QUEUED' AND next_attempt_at <= now()
                ORDER BY next_attempt_at, created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE datasets d
            SET status = 'GENERATING',
                attempt_count = d.attempt_count + 1,
                lease_owner = :owner,
                lease_token = nextval('lease_token_seq'),
                lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                updated_at = now()
            FROM candidate
            WHERE d.id = candidate.id
            RETURNING d.*
            """,
        )
            .param("owner", owner)
            .param("leaseSeconds", lease.toMillis() / 1000.0)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun renewLease(id: UUID, token: Long, lease: Duration): Instant? =
        jdbc.sql(
            """
            UPDATE datasets SET lease_expires_at = now() + make_interval(secs => :leaseSeconds), updated_at = now()
            WHERE id = :id AND status = 'GENERATING' AND lease_token = :token AND lease_expires_at > now()
            RETURNING lease_expires_at
            """,
        )
            .param("id", id)
            .param("token", token)
            .param("leaseSeconds", lease.toMillis() / 1000.0)
            .query { rs, _ -> rs.instant("lease_expires_at") }
            .optional()
            .orElse(null)

    /** Conditional publication: only the current, unexpired lease holder can mark the dataset ready. */
    fun markReady(id: UUID, token: Long): Boolean =
        jdbc.sql(
            """
            UPDATE datasets
            SET status = 'READY', lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                error_category = NULL, error_message = NULL, ready_at = now(), updated_at = now()
            WHERE id = :id AND status = 'GENERATING' AND lease_token = :token AND lease_expires_at > now()
            """,
        )
            .param("id", id)
            .param("token", token)
            .update() == 1

    /** Locks the dataset row if [token] still owns an unexpired lease. */
    fun lockOwned(id: UUID, token: Long): Dataset? =
        jdbc.sql(
            """
            SELECT * FROM datasets
            WHERE id = :id AND status = 'GENERATING' AND lease_token = :token AND lease_expires_at > now()
            FOR UPDATE
            """,
        )
            .param("id", id)
            .param("token", token)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun lockExpiredLeases(limit: Int): List<Dataset> =
        jdbc.sql(
            """
            SELECT * FROM datasets
            WHERE status = 'GENERATING' AND lease_expires_at <= now()
            ORDER BY lease_expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        )
            .param("limit", limit)
            .query(rowMapper)
            .list()

    fun requeue(id: UUID, delay: Duration, category: String, message: String) {
        jdbc.sql(
            """
            UPDATE datasets
            SET status = 'QUEUED', lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                next_attempt_at = now() + make_interval(secs => :delaySeconds),
                error_category = :category, error_message = :message, updated_at = now()
            WHERE id = :id
            """,
        )
            .param("id", id)
            .param("delaySeconds", delay.toMillis() / 1000.0)
            .param("category", category)
            .param("message", message)
            .update()
    }

    fun markFailed(id: UUID, category: String, message: String) {
        jdbc.sql(
            """
            UPDATE datasets
            SET status = 'FAILED', lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                error_category = :category, error_message = :message, updated_at = now()
            WHERE id = :id
            """,
        )
            .param("id", id)
            .param("category", category)
            .param("message", message)
            .update()
    }

    fun countByStatus(): Map<DatasetStatus, Long> =
        jdbc.sql("SELECT status, count(*) AS n FROM datasets GROUP BY status")
            .query { rs, _ -> DatasetStatus.valueOf(rs.getString("status")) to rs.getLong("n") }
            .list()
            .toMap()
}
