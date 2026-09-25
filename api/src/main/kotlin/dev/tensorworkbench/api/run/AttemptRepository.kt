package dev.tensorworkbench.api.run

import dev.tensorworkbench.api.db.instant
import dev.tensorworkbench.api.db.instantOrNull
import dev.tensorworkbench.api.db.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Types
import java.time.ZoneOffset
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AttemptOutcome { SUCCEEDED, FAILED, LEASE_EXPIRED }

data class Attempt(
    val id: UUID,
    val runId: UUID,
    val attemptNumber: Int,
    val leaseOwner: String,
    val leaseToken: Long,
    val leaseExpiresAt: Instant,
    val claimedAt: Instant,
    val lastHeartbeatAt: Instant?,
    val computeStartedAt: Instant?,
    val computeFinishedAt: Instant?,
    val finishedAt: Instant?,
    val outcome: AttemptOutcome?,
    val errorCategory: String?,
    val errorMessage: String?,
    val summary: JsonNode?,
)

/** One bar on the sweep timeline. */
data class AttemptInterval(
    val runId: UUID,
    val sweepOrdinal: Int?,
    val attemptNumber: Int,
    val leaseOwner: String,
    val outcome: AttemptOutcome?,
    val errorCategory: String?,
    val start: Instant,
    val end: Instant?,
    /** True when start/end come from the worker's compute instrumentation rather than claim/finish times. */
    val measured: Boolean,
)

@Repository
class AttemptRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    private val rowMapper = RowMapper { rs, _ ->
        Attempt(
            id = rs.uuid("id"),
            runId = rs.uuid("run_id"),
            attemptNumber = rs.getInt("attempt_number"),
            leaseOwner = rs.getString("lease_owner"),
            leaseToken = rs.getLong("lease_token"),
            leaseExpiresAt = rs.instant("lease_expires_at"),
            claimedAt = rs.instant("claimed_at"),
            lastHeartbeatAt = rs.instantOrNull("last_heartbeat_at"),
            computeStartedAt = rs.instantOrNull("compute_started_at"),
            computeFinishedAt = rs.instantOrNull("compute_finished_at"),
            finishedAt = rs.instantOrNull("finished_at"),
            outcome = rs.getString("outcome")?.let(AttemptOutcome::valueOf),
            errorCategory = rs.getString("error_category"),
            errorMessage = rs.getString("error_message"),
            summary = rs.getString("summary")?.let { mapper.readTree(it) },
        )
    }

    fun insertClaimed(runId: UUID, attemptNumber: Int, owner: String, lease: Duration): Attempt =
        jdbc.sql(
            """
            INSERT INTO run_attempts (id, run_id, attempt_number, lease_owner, lease_token, lease_expires_at)
            VALUES (:id, :runId, :number, :owner, nextval('lease_token_seq'), now() + make_interval(secs => :leaseSeconds))
            RETURNING *
            """,
        )
            .param("id", UUID.randomUUID())
            .param("runId", runId)
            .param("number", attemptNumber)
            .param("owner", owner)
            .param("leaseSeconds", lease.toMillis() / 1000.0)
            .query(rowMapper)
            .single()

    fun find(id: UUID): Attempt? =
        jdbc.sql("SELECT * FROM run_attempts WHERE id = :id").param("id", id).query(rowMapper).optional().orElse(null)

    fun forRun(runId: UUID): List<Attempt> =
        jdbc.sql("SELECT * FROM run_attempts WHERE run_id = :runId ORDER BY attempt_number")
            .param("runId", runId)
            .query(rowMapper)
            .list()

    /** Heartbeats renew only the current, unexpired attempt holding this token. */
    fun renewLease(attemptId: UUID, token: Long, lease: Duration): Instant? =
        jdbc.sql(
            """
            UPDATE run_attempts
            SET lease_expires_at = now() + make_interval(secs => :leaseSeconds), last_heartbeat_at = now()
            WHERE id = :id AND lease_token = :token AND outcome IS NULL AND lease_expires_at > now()
            RETURNING lease_expires_at
            """,
        )
            .param("id", attemptId)
            .param("token", token)
            .param("leaseSeconds", lease.toMillis() / 1000.0)
            .query { rs, _ -> rs.instant("lease_expires_at") }
            .optional()
            .orElse(null)

    /** Locks the attempt only if [token] still holds its open, unexpired lease. */
    fun lockOwned(attemptId: UUID, token: Long): Attempt? =
        jdbc.sql(
            """
            SELECT * FROM run_attempts
            WHERE id = :id AND lease_token = :token AND outcome IS NULL AND lease_expires_at > now()
            FOR UPDATE
            """,
        )
            .param("id", attemptId)
            .param("token", token)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun lockExpiredOpen(limit: Int): List<Attempt> =
        jdbc.sql(
            """
            SELECT * FROM run_attempts
            WHERE outcome IS NULL AND lease_expires_at <= now()
            ORDER BY lease_expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        )
            .param("limit", limit)
            .query(rowMapper)
            .list()

    fun finishSucceeded(attemptId: UUID, computeStartedAt: Instant, computeFinishedAt: Instant, summary: JsonNode) {
        jdbc.sql(
            """
            UPDATE run_attempts
            SET outcome = 'SUCCEEDED', finished_at = now(), compute_started_at = :cs, compute_finished_at = :cf,
                summary = CAST(:summary AS jsonb)
            WHERE id = :id AND outcome IS NULL
            """,
        )
            .param("id", attemptId)
            .param("cs", computeStartedAt.atOffset(ZoneOffset.UTC))
            .param("cf", computeFinishedAt.atOffset(ZoneOffset.UTC))
            .param("summary", mapper.writeValueAsString(summary))
            .update()
    }

    fun finishUnsuccessful(
        attemptId: UUID,
        outcome: AttemptOutcome,
        category: String,
        message: String,
        computeStartedAt: Instant?,
        computeFinishedAt: Instant?,
    ) {
        require(outcome != AttemptOutcome.SUCCEEDED)
        jdbc.sql(
            """
            UPDATE run_attempts
            SET outcome = :outcome, finished_at = now(), error_category = :category, error_message = :message,
                compute_started_at = COALESCE(:cs, compute_started_at),
                compute_finished_at = COALESCE(:cf, compute_finished_at)
            WHERE id = :id AND outcome IS NULL
            """,
        )
            .param("id", attemptId)
            .param("outcome", outcome.name)
            .param("category", category)
            .param("message", message)
            .param("cs", computeStartedAt?.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("cf", computeFinishedAt?.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
            .update()
    }

    fun intervalsForSweep(sweepId: UUID): List<AttemptInterval> =
        jdbc.sql(
            """
            SELECT a.*, r.sweep_ordinal
            FROM run_attempts a JOIN runs r ON r.id = a.run_id
            WHERE r.sweep_id = :sweepId
            ORDER BY COALESCE(a.compute_started_at, a.claimed_at), a.id
            """,
        )
            .param("sweepId", sweepId)
            .query { rs, _ ->
                val computeStart = rs.instantOrNull("compute_started_at")
                AttemptInterval(
                    runId = rs.uuid("run_id"),
                    sweepOrdinal = rs.getObject("sweep_ordinal") as Int?,
                    attemptNumber = rs.getInt("attempt_number"),
                    leaseOwner = rs.getString("lease_owner"),
                    outcome = rs.getString("outcome")?.let(AttemptOutcome::valueOf),
                    errorCategory = rs.getString("error_category"),
                    start = computeStart ?: rs.instant("claimed_at"),
                    end = rs.instantOrNull("compute_finished_at") ?: rs.instantOrNull("finished_at"),
                    measured = computeStart != null,
                )
            }
            .list()

    fun retriesInSweep(sweepId: UUID): Long =
        jdbc.sql(
            """
            SELECT count(*) FROM run_attempts a JOIN runs r ON r.id = a.run_id
            WHERE r.sweep_id = :sweepId AND a.attempt_number > 1
            """,
        )
            .param("sweepId", sweepId)
            .query(Long::class.java)
            .single()
}
