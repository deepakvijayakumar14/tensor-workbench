package dev.tensorworkbench.api.dataset

import dev.tensorworkbench.api.artifact.ArtifactKind
import dev.tensorworkbench.api.artifact.ArtifactRepository
import dev.tensorworkbench.api.artifact.ArtifactView
import dev.tensorworkbench.api.artifact.UploadVerifier
import dev.tensorworkbench.api.artifact.UploadedArtifact
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.db.inTx
import dev.tensorworkbench.api.idempotency.IdempotencyStore
import dev.tensorworkbench.api.idempotency.RequestHash
import dev.tensorworkbench.api.idempotency.Reservation
import dev.tensorworkbench.api.queue.ErrorCategory
import dev.tensorworkbench.api.queue.RetryDecision
import dev.tensorworkbench.api.queue.RetryPolicy
import dev.tensorworkbench.api.web.Page
import dev.tensorworkbench.api.web.PageRequest
import dev.tensorworkbench.api.web.conflict
import dev.tensorworkbench.api.web.notFound
import dev.tensorworkbench.api.web.validate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

data class CreateDatasetRequest(val shape: List<Int>?, val seed: Long?)

data class DatasetView(
    val id: UUID,
    val shape: List<Int>,
    val dtype: String,
    val seed: Long,
    val generatorVersion: String,
    val status: DatasetStatus,
    val attemptCount: Int,
    val elementCount: Long,
    val estimatedInputBytes: Long,
    val errorCategory: String?,
    val errorMessage: String?,
    val input: ArtifactView?,
    val createdAt: Instant,
    val readyAt: Instant?,
)

data class Submission<T>(val resource: T, val replayed: Boolean)

@Service
class DatasetService(
    private val datasets: DatasetRepository,
    private val artifacts: ArtifactRepository,
    private val idempotency: IdempotencyStore,
    private val verifier: UploadVerifier,
    private val retryPolicy: RetryPolicy,
    private val props: WorkbenchProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(idempotencyKey: String, request: CreateDatasetRequest): Submission<DatasetView> {
        val shape = validateShape(request)
        val seed = request.seed!!
        val hash = RequestHash.of(
            mapOf("shape" to shape, "seed" to seed, "generatorVersion" to props.computation.generatorVersion),
        )
        val reservation = tx.inTx {
            val r = idempotency.reserve("create_dataset", idempotencyKey, hash, "dataset")
            if (r is Reservation.New) {
                datasets.insert(r.resourceId, shape, seed, props.computation.generatorVersion, props.queue.maxAttempts)
            }
            r
        }
        return Submission(get(reservation.resourceId), replayed = reservation is Reservation.Replay)
    }

    fun get(id: UUID): DatasetView {
        val dataset = datasets.find(id) ?: throw notFound("Dataset", id)
        return view(dataset)
    }

    fun list(page: PageRequest): Page<DatasetView> {
        val result = datasets.page(page)
        return Page(result.items.map(::view), result.page, result.size, result.totalItems)
    }

    fun readyInput(id: UUID): Pair<Dataset, dev.tensorworkbench.api.artifact.Artifact> {
        val dataset = datasets.find(id) ?: throw notFound("Dataset", id)
        if (dataset.status != DatasetStatus.READY) {
            throw conflict("DATASET_NOT_READY", "Dataset $id is ${dataset.status}; wait until it is READY")
        }
        val input = artifacts.inputForDataset(id) ?: error("READY dataset $id has no input artifact")
        return dataset to input
    }

    // ---- Worker lifecycle (called from /internal endpoints) ----

    fun heartbeat(id: UUID, token: Long): Instant? = datasets.renewLease(id, token, props.queue.leaseDuration)

    fun complete(id: UUID, token: Long, upload: UploadedArtifact) {
        val dataset = datasets.find(id) ?: throw notFound("Dataset", id)
        // Network I/O first, outside the transaction.
        val verified = verifier.verify(
            upload, "artifact", ArtifactKind.INPUT_TENSOR, objectPrefix(id, token), "int32", dataset.shape,
        )
        tx.inTx {
            if (!datasets.markReady(id, token)) {
                throw conflict("LEASE_NOT_HELD", "Lease token $token no longer owns dataset $id")
            }
            artifacts.insert(ArtifactKind.INPUT_TENSOR, id, null, verified)
        }
        log.info("Dataset {} ready (token {})", id, token)
    }

    fun fail(id: UUID, token: Long, category: ErrorCategory, message: String): DatasetStatus = tx.inTx {
        val dataset = datasets.lockOwned(id, token)
            ?: throw conflict("LEASE_NOT_HELD", "Lease token $token no longer owns dataset $id")
        settleFailure(dataset, category, message)
    }

    /** Requeues or fails datasets whose generation lease expired. */
    fun recoverExpiredLeases(): Int = tx.inTx {
        val expired = datasets.lockExpiredLeases(50)
        expired.forEach {
            settleFailure(it, ErrorCategory.LEASE_EXPIRED, "Lease held by ${it.leaseOwner} expired without completion")
        }
        expired.size
    }

    private fun settleFailure(dataset: Dataset, category: ErrorCategory, message: String): DatasetStatus =
        when (val decision = retryPolicy.decide(category, dataset.attemptCount, dataset.maxAttempts)) {
            is RetryDecision.RetryAfter -> {
                datasets.requeue(dataset.id, decision.delay, category.name, message)
                DatasetStatus.QUEUED
            }
            RetryDecision.GiveUp -> {
                datasets.markFailed(dataset.id, category.name, message)
                DatasetStatus.FAILED
            }
        }

    private fun validateShape(request: CreateDatasetRequest): List<Int> {
        val limits = props.limits
        validate {
            val shape = required(request.shape, "shape")
            if (shape != null) {
                require(shape.size == 3, "shape") { "must have exactly 3 dimensions" }
                shape.forEachIndexed { i, d ->
                    require(d in 1..limits.maxDimension, "shape[$i]") { "must be between 1 and ${limits.maxDimension}" }
                }
                if (shape.size == 3 && shape.all { it > 0 }) {
                    val elements = shape.fold(1L) { acc, d -> acc * d }
                    require(elements <= limits.maxElements, "shape") {
                        "has $elements elements; the configured limit is ${limits.maxElements}"
                    }
                }
            }
            val seed = required(request.seed, "seed")
            if (seed != null) require(seed in 0..Int.MAX_VALUE.toLong(), "seed") { "must be between 0 and ${Int.MAX_VALUE}" }
        }
        return request.shape!!
    }

    private fun view(d: Dataset) = DatasetView(
        id = d.id,
        shape = d.shape,
        dtype = d.dtype,
        seed = d.seed,
        generatorVersion = d.generatorVersion,
        status = d.status,
        attemptCount = d.attemptCount,
        elementCount = d.elementCount,
        estimatedInputBytes = NPY_HEADER_BYTES + d.elementCount * 4,
        errorCategory = d.errorCategory,
        errorMessage = d.errorMessage,
        input = if (d.status == DatasetStatus.READY) artifacts.inputForDataset(d.id)?.let(ArtifactView::of) else null,
        createdAt = d.createdAt,
        readyAt = d.readyAt,
    )

    companion object {
        const val NPY_HEADER_BYTES = 128L

        /** Object keys are scoped to the lease, so a stale generator can never overwrite a newer upload. */
        fun objectPrefix(datasetId: UUID, token: Long) = "datasets/$datasetId/generation-$token/"
    }
}
