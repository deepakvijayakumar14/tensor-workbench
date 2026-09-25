package dev.tensorworkbench.api.queue

import dev.tensorworkbench.api.artifact.ArtifactKind
import dev.tensorworkbench.api.artifact.ArtifactRepository
import dev.tensorworkbench.api.artifact.PreviewLayout
import dev.tensorworkbench.api.artifact.UploadVerifier
import dev.tensorworkbench.api.artifact.UploadedArtifact
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.DatasetRepository
import dev.tensorworkbench.api.dataset.DatasetService
import dev.tensorworkbench.api.db.elementList
import dev.tensorworkbench.api.db.inTx
import dev.tensorworkbench.api.run.AttemptOutcome
import dev.tensorworkbench.api.run.AttemptRepository
import dev.tensorworkbench.api.run.Run
import dev.tensorworkbench.api.run.RunRepository
import dev.tensorworkbench.api.run.RunState
import dev.tensorworkbench.api.web.FieldErrors
import dev.tensorworkbench.api.web.conflict
import dev.tensorworkbench.api.web.notFound
import dev.tensorworkbench.api.web.validate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class ClaimRequest(val workerId: String?, val workerInstance: String?, val slotCount: Int?)

data class GenerationTask(
    val datasetId: UUID,
    val attemptNumber: Int,
    val shape: List<Int>,
    val seed: Long,
    val generatorVersion: String,
)

data class RunTask(
    val runId: UUID,
    val attemptId: UUID,
    val attemptNumber: Int,
    val sweepId: UUID?,
    val sweepOrdinal: Int?,
    val config: JsonNode,
)

/** What a worker slot receives. It carries references and configuration, never tensor data. */
data class TaskAssignment(
    val type: String,
    val leaseToken: Long,
    val leaseExpiresAt: Instant,
    val leaseDurationSeconds: Double,
    val heartbeatIntervalSeconds: Double,
    /** Every object this attempt uploads must live under this attempt-scoped prefix. */
    val objectPrefix: String,
    val generation: GenerationTask? = null,
    val run: RunTask? = null,
)

data class LeaseRequest(val leaseToken: Long?)

data class LeaseResponse(val leaseExpiresAt: Instant)

data class CompleteGenerationRequest(val leaseToken: Long?, val artifact: UploadedArtifact?)

data class CompleteRunRequest(
    val leaseToken: Long?,
    val computeStartedAt: Instant?,
    val computeFinishedAt: Instant?,
    val summary: JsonNode?,
    val output: UploadedArtifact?,
    val preview: UploadedArtifact?,
)

data class FailRequest(
    val leaseToken: Long?,
    val category: String?,
    val message: String?,
    val computeStartedAt: Instant? = null,
    val computeFinishedAt: Instant? = null,
)

data class FailResponse(val nextState: String)

/**
 * The worker-facing side of the PostgreSQL job queue.
 *
 * Rules that keep accepted state correct:
 *  - Claims are short transactions committed before the worker does any I/O.
 *  - Every heartbeat, completion and failure must present the lease token of the
 *    open, unexpired attempt. Anything else gets 409 and changes nothing.
 *  - Uploads are verified before publication; publication is one conditional
 *    transaction that finishes the attempt, accepts it on the run and records artifacts.
 */
@Service
class WorkQueueService(
    private val datasets: DatasetRepository,
    private val datasetService: DatasetService,
    private val runs: RunRepository,
    private val attempts: AttemptRepository,
    private val artifacts: ArtifactRepository,
    private val verifier: UploadVerifier,
    private val retryPolicy: RetryPolicy,
    private val registry: WorkerRegistry,
    private val props: WorkbenchProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun claim(request: ClaimRequest): TaskAssignment? {
        validate {
            val id = required(request.workerId, "workerId")
            if (id != null) require(id.length in 1..200, "workerId") { "must be 1-200 characters" }
        }
        val owner = request.workerId!!
        registry.seen(request.workerInstance ?: owner, request.slotCount ?: 1)
        val lease = props.queue.leaseDuration

        // Dataset generation first: runs cannot be submitted until their dataset is ready.
        tx.inTx { datasets.claimNext(owner, lease) }?.let { d ->
            log.info("Claimed generation of dataset {} for {} (token {})", d.id, owner, d.leaseToken)
            return TaskAssignment(
                type = "GENERATE_DATASET",
                leaseToken = d.leaseToken!!,
                leaseExpiresAt = d.leaseExpiresAt!!,
                leaseDurationSeconds = lease.toMillis() / 1000.0,
                heartbeatIntervalSeconds = props.queue.heartbeatInterval.toMillis() / 1000.0,
                objectPrefix = DatasetService.objectPrefix(d.id, d.leaseToken),
                generation = GenerationTask(d.id, d.attemptCount, d.shape, d.seed, d.generatorVersion),
            )
        }

        val claimed = tx.inTx {
            val run = runs.claimNext() ?: return@inTx null
            run to attempts.insertClaimed(run.id, run.attemptCount, owner, lease)
        } ?: return null
        val (run, attempt) = claimed
        log.info("Claimed run {} attempt {} for {} (token {})", run.id, attempt.attemptNumber, owner, attempt.leaseToken)
        return TaskAssignment(
            type = "RUN",
            leaseToken = attempt.leaseToken,
            leaseExpiresAt = attempt.leaseExpiresAt,
            leaseDurationSeconds = lease.toMillis() / 1000.0,
            heartbeatIntervalSeconds = props.queue.heartbeatInterval.toMillis() / 1000.0,
            objectPrefix = runObjectPrefix(run.id, attempt.attemptNumber, attempt.leaseToken),
            run = RunTask(run.id, attempt.id, attempt.attemptNumber, run.sweepId, run.sweepOrdinal, run.configSnapshot),
        )
    }

    // ---- Dataset generation ----

    fun heartbeatGeneration(datasetId: UUID, request: LeaseRequest): LeaseResponse {
        val token = requireToken(request.leaseToken)
        val expires = datasetService.heartbeat(datasetId, token) ?: throw leaseLost(token)
        return LeaseResponse(expires)
    }

    fun completeGeneration(datasetId: UUID, request: CompleteGenerationRequest) {
        val token = requireToken(request.leaseToken)
        validate { required(request.artifact, "artifact") }
        datasetService.complete(datasetId, token, request.artifact!!)
    }

    fun failGeneration(datasetId: UUID, request: FailRequest): FailResponse {
        val token = requireToken(request.leaseToken)
        val (category, message) = parseFailure(request)
        return FailResponse(datasetService.fail(datasetId, token, category, message).name)
    }

    // ---- Run attempts ----

    fun heartbeatAttempt(attemptId: UUID, request: LeaseRequest): LeaseResponse {
        val token = requireToken(request.leaseToken)
        val expires = attempts.renewLease(attemptId, token, props.queue.leaseDuration) ?: throw leaseLost(token)
        return LeaseResponse(expires)
    }

    fun completeAttempt(attemptId: UUID, request: CompleteRunRequest) {
        val token = requireToken(request.leaseToken)
        val attempt = attempts.find(attemptId) ?: throw notFound("Attempt", attemptId)
        val run = runs.find(attempt.runId)!!

        // A retried completion request after a lost response: report success again, change nothing.
        if (attempt.outcome == AttemptOutcome.SUCCEEDED && attempt.leaseToken == token && run.acceptedAttemptId == attemptId) {
            return
        }
        // Cheap pre-check so a stale worker does not cause storage lookups. The
        // authoritative check happens again under a row lock below.
        if (attempt.leaseToken != token || attempt.outcome != null) throw leaseLost(token)

        val outputShape = run.configSnapshot.get("output").get("shape").elementList().map { it.asInt() }
        val errors = FieldErrors()
        val computeStarted = errors.required(request.computeStartedAt, "computeStartedAt")
        val computeFinished = errors.required(request.computeFinishedAt, "computeFinishedAt")
        if (computeStarted != null && computeFinished != null) {
            errors.require(!computeFinished.isBefore(computeStarted), "computeFinishedAt") { "must not be before computeStartedAt" }
        }
        val summary = errors.required(request.summary, "summary")
        listOf("min", "max", "mean", "std").forEach { f ->
            errors.require(summary?.get(f)?.isNumber == true && summary.get(f).asDouble().isFinite(), "summary.$f") {
                "must be a finite number"
            }
        }
        errors.required(request.output, "output")
        errors.required(request.preview, "preview")
        PreviewLayout.validateReported(
            errors, "preview.metadata", request.preview?.metadata, outputShape,
            props.limits.previewMaxDimension, request.preview?.sizeBytes,
        )
        errors.throwIfAny()

        // Network I/O outside the transaction: confirm both uploads exist with the reported checksums.
        val prefix = runObjectPrefix(run.id, attempt.attemptNumber, attempt.leaseToken)
        val output = verifier.verify(request.output!!, "output", ArtifactKind.OUTPUT_TENSOR, prefix, "float32", outputShape)
        val preview = verifier.verify(request.preview!!, "preview", ArtifactKind.PREVIEW_STACK, prefix, "uint8", null)

        tx.inTx {
            attempts.lockOwned(attemptId, token) ?: throw leaseLost(token)
            attempts.finishSucceeded(attemptId, computeStarted!!, computeFinished!!, summary!!)
            if (!runs.markSucceeded(run.id, attemptId)) {
                throw conflict("INVALID_STATE_TRANSITION", "Run ${run.id} can no longer accept attempt ${attempt.attemptNumber}")
            }
            artifacts.insert(ArtifactKind.OUTPUT_TENSOR, null, attemptId, output)
            artifacts.insert(ArtifactKind.PREVIEW_STACK, null, attemptId, preview)
        }
        log.info("Run {} succeeded on attempt {}", run.id, attempt.attemptNumber)
    }

    fun failAttempt(attemptId: UUID, request: FailRequest): FailResponse {
        val token = requireToken(request.leaseToken)
        val (category, message) = parseFailure(request)
        val next = tx.inTx {
            val attempt = attempts.lockOwned(attemptId, token) ?: throw leaseLost(token)
            val run = runs.lock(attempt.runId)!!
            attempts.finishUnsuccessful(
                attemptId, AttemptOutcome.FAILED, category.name, message, request.computeStartedAt, request.computeFinishedAt,
            )
            settleRun(run, category, message)
        }
        log.info("Attempt {} failed with {} -> run is {}", attemptId, category, next)
        return FailResponse(next.name)
    }

    /**
     * Lease recovery: an attempt that stopped heartbeating is closed as LEASE_EXPIRED
     * and its run follows the retry policy. This cannot stop a paused worker; the
     * fencing token stops it from publishing if it wakes up later.
     */
    fun recoverExpiredAttempts(): Int = tx.inTx {
        val expired = attempts.lockExpiredOpen(50)
        expired.forEach { attempt ->
            val run = runs.lock(attempt.runId)!!
            val message = "Lease held by ${attempt.leaseOwner} expired without a heartbeat"
            attempts.finishUnsuccessful(attempt.id, AttemptOutcome.LEASE_EXPIRED, ErrorCategory.LEASE_EXPIRED.name, message, null, null)
            val next = settleRun(run, ErrorCategory.LEASE_EXPIRED, message)
            log.warn("Recovered expired attempt {} of run {} -> {}", attempt.attemptNumber, run.id, next)
        }
        expired.size
    }

    private fun settleRun(run: Run, category: ErrorCategory, message: String): RunState =
        when (val decision = retryPolicy.decide(category, run.attemptCount, run.maxAttempts)) {
            is RetryDecision.RetryAfter -> {
                runs.requeue(run.id, decision.delay, category.name, message)
                RunState.QUEUED
            }
            RetryDecision.GiveUp -> {
                runs.markFailed(run.id, category.name, message)
                RunState.FAILED
            }
        }

    private fun parseFailure(request: FailRequest): Pair<ErrorCategory, String> {
        val errors = FieldErrors()
        val raw = errors.required(request.category, "category")
        val category = raw?.let { r -> ErrorCategory.entries.firstOrNull { it.name == r } }
        if (raw != null) errors.require(category != null, "category") { "must be one of ${ErrorCategory.entries.joinToString()}" }
        errors.throwIfAny()
        return category!! to (request.message ?: "").take(2000).ifBlank { category.name }
    }

    private fun requireToken(token: Long?): Long {
        validate { required(token, "leaseToken") }
        return token!!
    }

    private fun leaseLost(token: Long) =
        conflict("LEASE_NOT_HELD", "Lease token $token does not hold an open, unexpired lease; stop work and discard results")

    companion object {
        fun runObjectPrefix(runId: UUID, attemptNumber: Int, token: Long) = "runs/$runId/attempt-$attemptNumber-$token/"
    }
}
