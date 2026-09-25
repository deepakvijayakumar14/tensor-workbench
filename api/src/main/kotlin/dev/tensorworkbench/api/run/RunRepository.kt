package dev.tensorworkbench.api.run

import dev.tensorworkbench.api.db.instant
import dev.tensorworkbench.api.db.instantOrNull
import dev.tensorworkbench.api.db.uuid
import dev.tensorworkbench.api.db.uuidOrNull
import dev.tensorworkbench.api.web.Page
import dev.tensorworkbench.api.web.PageRequest
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class RunState { QUEUED, RUNNING, SUCCEEDED, FAILED }

data class Run(
    val id: UUID,
    val datasetId: UUID,
    val sweepId: UUID?,
    val sweepOrdinal: Int?,
    val gain: BigDecimal,
    val bias: BigDecimal,
    val implementationVersion: String,
    val configSnapshot: JsonNode,
    val configHash: String,
    val state: RunState,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val acceptedAttemptId: UUID?,
    val lastErrorCategory: String?,
    val lastErrorMessage: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

/** A run plus the columns the results table needs, fetched in one query. */
data class RunRow(
    val run: Run,
    val acceptedAttemptNumber: Int?,
    val summary: JsonNode?,
    val computeStartedAt: Instant?,
    val computeFinishedAt: Instant?,
    val outputSizeBytes: Long?,
    val openAttemptClaimedAt: Instant?,
    val openAttemptOwner: String?,
)

data class NewRun(
    val id: UUID,
    val datasetId: UUID,
    val sweepId: UUID?,
    val sweepOrdinal: Int?,
    val gain: BigDecimal,
    val bias: BigDecimal,
    val implementationVersion: String,
    val configSnapshot: JsonNode,
    val configHash: String,
    val maxAttempts: Int,
)

@Repository
class RunRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    private fun mapRun(rs: ResultSet) = Run(
        id = rs.uuid("id"),
        datasetId = rs.uuid("dataset_id"),
        sweepId = rs.uuidOrNull("sweep_id"),
        sweepOrdinal = rs.getObject("sweep_ordinal") as Int?,
        gain = rs.getBigDecimal("gain"),
        bias = rs.getBigDecimal("bias"),
        implementationVersion = rs.getString("implementation_version"),
        configSnapshot = mapper.readTree(rs.getString("config_snapshot")),
        configHash = rs.getString("config_hash"),
        state = RunState.valueOf(rs.getString("state")),
        attemptCount = rs.getInt("attempt_count"),
        maxAttempts = rs.getInt("max_attempts"),
        nextAttemptAt = rs.instant("next_attempt_at"),
        acceptedAttemptId = rs.uuidOrNull("accepted_attempt_id"),
        lastErrorCategory = rs.getString("last_error_category"),
        lastErrorMessage = rs.getString("last_error_message"),
        createdAt = rs.instant("created_at"),
        finishedAt = rs.instantOrNull("finished_at"),
    )

    private val runMapper = RowMapper { rs, _ -> mapRun(rs) }

    private val rowMapper = RowMapper { rs, _ ->
        RunRow(
            run = mapRun(rs),
            acceptedAttemptNumber = rs.getObject("acc_number") as Int?,
            summary = rs.getString("acc_summary")?.let { mapper.readTree(it) },
            computeStartedAt = rs.instantOrNull("acc_compute_started_at"),
            computeFinishedAt = rs.instantOrNull("acc_compute_finished_at"),
            outputSizeBytes = rs.getObject("output_size_bytes") as Long?,
            openAttemptClaimedAt = rs.instantOrNull("open_claimed_at"),
            openAttemptOwner = rs.getString("open_owner"),
        )
    }

    fun insert(run: NewRun) {
        jdbc.sql(
            """
            INSERT INTO runs (id, dataset_id, sweep_id, sweep_ordinal, gain, bias, implementation_version,
                              config_snapshot, config_hash, state, max_attempts)
            VALUES (:id, :datasetId, :sweepId, :ordinal, :gain, :bias, :version,
                    CAST(:snapshot AS jsonb), :hash, 'QUEUED', :maxAttempts)
            """,
        )
            .param("id", run.id)
            .param("datasetId", run.datasetId)
            .param("sweepId", run.sweepId)
            .param("ordinal", run.sweepOrdinal)
            .param("gain", run.gain)
            .param("bias", run.bias)
            .param("version", run.implementationVersion)
            .param("snapshot", mapper.writeValueAsString(run.configSnapshot))
            .param("hash", run.configHash)
            .param("maxAttempts", run.maxAttempts)
            .update()
    }

    fun find(id: UUID): Run? =
        jdbc.sql("SELECT * FROM runs WHERE id = :id").param("id", id).query(runMapper).optional().orElse(null)

    fun lock(id: UUID): Run? =
        jdbc.sql("SELECT * FROM runs WHERE id = :id FOR UPDATE").param("id", id).query(runMapper).optional().orElse(null)

    fun findRow(id: UUID): RunRow? =
        jdbc.sql("$ROW_SELECT WHERE r.id = :id").param("id", id).query(rowMapper).optional().orElse(null)

    fun pageForSweep(sweepId: UUID, request: PageRequest): Page<RunRow> {
        val items = jdbc.sql("$ROW_SELECT WHERE r.sweep_id = :sweepId ORDER BY r.sweep_ordinal LIMIT :limit OFFSET :offset")
            .param("sweepId", sweepId)
            .param("limit", request.size)
            .param("offset", request.offset)
            .query(rowMapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM runs WHERE sweep_id = :sweepId")
            .param("sweepId", sweepId).query(Long::class.java).single()
        return Page(items, request.page, request.size, total)
    }

    fun pageStandalone(request: PageRequest): Page<RunRow> {
        val items = jdbc.sql("$ROW_SELECT WHERE r.sweep_id IS NULL ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
            .param("limit", request.size)
            .param("offset", request.offset)
            .query(rowMapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM runs WHERE sweep_id IS NULL").query(Long::class.java).single()
        return Page(items, request.page, request.size, total)
    }

    /**
     * Claims the next eligible run: retries become eligible at next_attempt_at, so
     * backoff is enforced by the same query that provides FIFO ordering.
     */
    fun claimNext(): Run? =
        jdbc.sql(
            """
            WITH candidate AS (
                SELECT id FROM runs
                WHERE state = 'QUEUED' AND next_attempt_at <= now()
                ORDER BY next_attempt_at, created_at, sweep_ordinal NULLS FIRST, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE runs r
            SET state = 'RUNNING', attempt_count = r.attempt_count + 1, updated_at = now()
            FROM candidate
            WHERE r.id = candidate.id
            RETURNING r.*
            """,
        )
            .query(runMapper)
            .optional()
            .orElse(null)

    /** Conditional publication of the authoritative attempt. */
    fun markSucceeded(runId: UUID, attemptId: UUID): Boolean =
        jdbc.sql(
            """
            UPDATE runs
            SET state = 'SUCCEEDED', accepted_attempt_id = :attemptId, finished_at = now(), updated_at = now(),
                last_error_category = NULL, last_error_message = NULL
            WHERE id = :runId AND state = 'RUNNING' AND accepted_attempt_id IS NULL
            """,
        )
            .param("runId", runId)
            .param("attemptId", attemptId)
            .update() == 1

    fun requeue(runId: UUID, delay: Duration, category: String, message: String) {
        jdbc.sql(
            """
            UPDATE runs
            SET state = 'QUEUED', next_attempt_at = now() + make_interval(secs => :delaySeconds),
                last_error_category = :category, last_error_message = :message, updated_at = now()
            WHERE id = :runId AND state = 'RUNNING'
            """,
        )
            .param("runId", runId)
            .param("delaySeconds", delay.toMillis() / 1000.0)
            .param("category", category)
            .param("message", message)
            .update()
    }

    fun markFailed(runId: UUID, category: String, message: String) {
        jdbc.sql(
            """
            UPDATE runs
            SET state = 'FAILED', last_error_category = :category, last_error_message = :message,
                finished_at = now(), updated_at = now()
            WHERE id = :runId AND state = 'RUNNING'
            """,
        )
            .param("runId", runId)
            .param("category", category)
            .param("message", message)
            .update()
    }

    /**
     * Explicit retry of a terminally failed run. The attempt budget is extended from
     * the current count, so history is kept and numbering continues.
     */
    fun requeueFailed(runId: UUID, additionalAttempts: Int): Boolean =
        jdbc.sql(
            """
            UPDATE runs
            SET state = 'QUEUED', max_attempts = attempt_count + :extra, next_attempt_at = now(),
                finished_at = NULL, updated_at = now()
            WHERE id = :runId AND state = 'FAILED'
            """,
        )
            .param("runId", runId)
            .param("extra", additionalAttempts)
            .update() == 1

    /** Requeues only FAILED children; SUCCEEDED children are never touched or duplicated. */
    fun requeueFailedInSweep(sweepId: UUID, additionalAttempts: Int): Int =
        jdbc.sql(
            """
            UPDATE runs
            SET state = 'QUEUED', max_attempts = attempt_count + :extra, next_attempt_at = now(),
                finished_at = NULL, updated_at = now()
            WHERE sweep_id = :sweepId AND state = 'FAILED'
            """,
        )
            .param("sweepId", sweepId)
            .param("extra", additionalAttempts)
            .update()

    fun countsForSweep(sweepId: UUID): Map<RunState, Long> =
        jdbc.sql("SELECT state, count(*) AS n FROM runs WHERE sweep_id = :sweepId GROUP BY state")
            .param("sweepId", sweepId)
            .query { rs, _ -> RunState.valueOf(rs.getString("state")) to rs.getLong("n") }
            .list()
            .toMap()

    fun countByState(): Map<RunState, Long> =
        jdbc.sql("SELECT state, count(*) AS n FROM runs GROUP BY state")
            .query { rs, _ -> RunState.valueOf(rs.getString("state")) to rs.getLong("n") }
            .list()
            .toMap()

    companion object {
        /**
         * Runs with their accepted (authoritative) attempt and its output artifact.
         * The UI shows the accepted attempt, never "whichever file finished last".
         */
        private const val ROW_SELECT = """
            SELECT r.*,
                   acc.attempt_number      AS acc_number,
                   acc.summary             AS acc_summary,
                   acc.compute_started_at  AS acc_compute_started_at,
                   acc.compute_finished_at AS acc_compute_finished_at,
                   output.size_bytes       AS output_size_bytes,
                   open_attempt.claimed_at AS open_claimed_at,
                   open_attempt.lease_owner AS open_owner
            FROM runs r
            LEFT JOIN run_attempts acc ON acc.id = r.accepted_attempt_id
            LEFT JOIN artifacts output ON output.attempt_id = r.accepted_attempt_id AND output.kind = 'OUTPUT_TENSOR'
            LEFT JOIN run_attempts open_attempt ON open_attempt.run_id = r.id AND open_attempt.outcome IS NULL
        """
    }
}
