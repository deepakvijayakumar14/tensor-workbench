package dev.tensorworkbench.api.run

import dev.tensorworkbench.api.db.normalized
import dev.tensorworkbench.api.artifact.ArtifactView
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class RunResult(
    val acceptedAttemptNumber: Int,
    val min: Double?,
    val max: Double?,
    val mean: Double?,
    val std: Double?,
    val computeSeconds: Double?,
    val outputSizeBytes: Long?,
)

data class RunView(
    val id: UUID,
    val datasetId: UUID,
    val sweepId: UUID?,
    val sweepOrdinal: Int?,
    val gain: BigDecimal,
    val bias: BigDecimal,
    val implementationVersion: String,
    val state: RunState,
    /** A human-readable stage, derived from real state rather than invented percentages. */
    val stage: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant?,
    val runningSince: Instant?,
    val runningOn: String?,
    val lastErrorCategory: String?,
    val lastErrorMessage: String?,
    val faultInjection: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val result: RunResult?,
) {
    companion object {
        fun of(row: RunRow, now: Instant = Instant.now()): RunView {
            val run = row.run
            val fault = run.configSnapshot.get("faultInjection")?.takeUnless { it.isNull }?.asString()
            return RunView(
                id = run.id,
                datasetId = run.datasetId,
                sweepId = run.sweepId,
                sweepOrdinal = run.sweepOrdinal,
                gain = run.gain.normalized(),
                bias = run.bias.normalized(),
                implementationVersion = run.implementationVersion,
                state = run.state,
                stage = stage(row, now),
                attemptCount = run.attemptCount,
                maxAttempts = run.maxAttempts,
                nextAttemptAt = run.nextAttemptAt.takeIf { run.state == RunState.QUEUED },
                runningSince = row.openAttemptClaimedAt,
                runningOn = row.openAttemptOwner,
                lastErrorCategory = run.lastErrorCategory,
                lastErrorMessage = run.lastErrorMessage,
                faultInjection = fault,
                createdAt = run.createdAt,
                finishedAt = run.finishedAt,
                result = row.acceptedAttemptNumber?.let {
                    RunResult(
                        acceptedAttemptNumber = it,
                        min = row.summary?.get("min")?.asDouble(),
                        max = row.summary?.get("max")?.asDouble(),
                        mean = row.summary?.get("mean")?.asDouble(),
                        std = row.summary?.get("std")?.asDouble(),
                        computeSeconds = if (row.computeStartedAt != null && row.computeFinishedAt != null) {
                            Duration.between(row.computeStartedAt, row.computeFinishedAt).toMillis() / 1000.0
                        } else {
                            null
                        },
                        outputSizeBytes = row.outputSizeBytes,
                    )
                },
            )
        }

        private fun stage(row: RunRow, now: Instant): String = when (row.run.state) {
            RunState.QUEUED ->
                if (row.run.attemptCount == 0) {
                    "Waiting for a free worker slot"
                } else if (row.run.nextAttemptAt.isAfter(now)) {
                    "Retry ${row.run.attemptCount + 1} scheduled after ${row.run.lastErrorCategory ?: "failure"}"
                } else {
                    "Retry ${row.run.attemptCount + 1} waiting for a free worker slot"
                }
            RunState.RUNNING -> "Attempt ${row.run.attemptCount} running on ${row.openAttemptOwner ?: "worker"}"
            RunState.SUCCEEDED -> "Succeeded on attempt ${row.acceptedAttemptNumber}"
            RunState.FAILED -> "Failed: ${row.run.lastErrorCategory}"
        }
    }
}

data class AttemptView(
    val attemptNumber: Int,
    val outcome: AttemptOutcome?,
    val accepted: Boolean,
    val leaseOwner: String,
    val claimedAt: Instant,
    val computeStartedAt: Instant?,
    val computeFinishedAt: Instant?,
    val finishedAt: Instant?,
    val errorCategory: String?,
    val errorMessage: String?,
)

data class PreviewAxis(
    val axis: Int,
    val sliceCount: Int,
    val height: Int,
    val width: Int,
    val sourceHeight: Int,
    val sourceWidth: Int,
)

data class PreviewInfo(val maxDimension: Int, val valueMin: Double, val valueMax: Double, val axes: List<PreviewAxis>)

data class RunDetail(
    val run: RunView,
    val attempts: List<AttemptView>,
    val artifacts: List<ArtifactView>,
    val preview: PreviewInfo?,
    val configSnapshot: JsonNode,
    val configHash: String,
)
