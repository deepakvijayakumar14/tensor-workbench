package dev.tensorworkbench.api.run

import dev.tensorworkbench.api.artifact.ArtifactKind
import dev.tensorworkbench.api.artifact.ArtifactRepository
import dev.tensorworkbench.api.artifact.ArtifactView
import dev.tensorworkbench.api.artifact.PreviewLayout
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.DatasetService
import dev.tensorworkbench.api.dataset.Submission
import dev.tensorworkbench.api.db.inTx
import dev.tensorworkbench.api.idempotency.IdempotencyStore
import dev.tensorworkbench.api.idempotency.RequestHash
import dev.tensorworkbench.api.idempotency.Reservation
import dev.tensorworkbench.api.web.FieldErrors
import dev.tensorworkbench.api.web.Page
import dev.tensorworkbench.api.web.PageRequest
import dev.tensorworkbench.api.web.conflict
import dev.tensorworkbench.api.web.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

data class SubmitRunRequest(
    val datasetId: UUID?,
    val gain: BigDecimal?,
    val bias: BigDecimal?,
    val implementationVersion: String?,
    val faultInjection: String?,
)

@Service
class RunService(
    private val runs: RunRepository,
    private val attempts: AttemptRepository,
    private val artifacts: ArtifactRepository,
    private val datasetService: DatasetService,
    private val configFactory: RunConfigFactory,
    private val idempotency: IdempotencyStore,
    private val props: WorkbenchProperties,
    private val tx: TransactionTemplate,
) {

    fun submit(idempotencyKey: String, request: SubmitRunRequest): Submission<RunView> {
        val errors = FieldErrors()
        errors.required(request.datasetId, "datasetId")
        errors.required(request.gain, "gain")
        configFactory.validateParameter(errors, "gain", request.gain)
        configFactory.validateParameter(errors, "bias", request.bias)
        configFactory.validateVersion(errors, request.implementationVersion)
        val fault = configFactory.parseFaultInjection(errors, request.faultInjection)
        errors.throwIfAny()

        val gain = request.gain!!
        val bias = request.bias ?: BigDecimal.ZERO
        val hash = RequestHash.of(
            mapOf(
                "datasetId" to request.datasetId,
                "gain" to gain,
                "bias" to bias,
                "implementationVersion" to request.implementationVersion,
                "faultInjection" to fault?.name,
            ),
        )
        val reservation = tx.inTx {
            val r = idempotency.reserve("submit_run", idempotencyKey, hash, "run")
            if (r is Reservation.New) {
                // Rolls back the reservation too, so the client may retry with the same key later.
                val (dataset, input) = datasetService.readyInput(request.datasetId!!)
                val (snapshot, configHash) =
                    configFactory.snapshot(dataset, input, gain, bias, request.implementationVersion!!, fault)
                runs.insert(
                    NewRun(
                        id = r.resourceId,
                        datasetId = dataset.id,
                        sweepId = null,
                        sweepOrdinal = null,
                        gain = gain,
                        bias = bias,
                        implementationVersion = request.implementationVersion,
                        configSnapshot = snapshot,
                        configHash = configHash,
                        maxAttempts = props.queue.maxAttempts,
                    ),
                )
            }
            r
        }
        return Submission(view(reservation.resourceId), replayed = reservation is Reservation.Replay)
    }

    fun view(id: UUID): RunView = RunView.of(runs.findRow(id) ?: throw notFound("Run", id))

    fun detail(id: UUID): RunDetail {
        val row = runs.findRow(id) ?: throw notFound("Run", id)
        val acceptedId = row.run.acceptedAttemptId
        val accepted = acceptedId?.let { artifacts.forAttempt(it) } ?: emptyList()
        val preview = accepted.firstOrNull { it.kind == ArtifactKind.PREVIEW_STACK }?.let { PreviewLayout.parse(it.metadata) }
        return RunDetail(
            run = RunView.of(row),
            attempts = attempts.forRun(id).map {
                AttemptView(
                    attemptNumber = it.attemptNumber,
                    outcome = it.outcome,
                    accepted = it.id == acceptedId,
                    leaseOwner = it.leaseOwner,
                    claimedAt = it.claimedAt,
                    computeStartedAt = it.computeStartedAt,
                    computeFinishedAt = it.computeFinishedAt,
                    finishedAt = it.finishedAt,
                    errorCategory = it.errorCategory,
                    errorMessage = it.errorMessage,
                )
            },
            artifacts = accepted.map(ArtifactView::of),
            preview = preview?.toInfo(),
            configSnapshot = row.run.configSnapshot,
            configHash = row.run.configHash,
        )
    }

    fun listStandalone(page: PageRequest): Page<RunView> {
        val result = runs.pageStandalone(page)
        return Page(result.items.map { RunView.of(it) }, result.page, result.size, result.totalItems)
    }

    /** Explicit retry for a terminally failed run. Any other state is an invalid transition. */
    fun retry(id: UUID): RunView {
        tx.inTx {
            val run = runs.lock(id) ?: throw notFound("Run", id)
            if (!runs.requeueFailed(id, props.queue.maxAttempts)) {
                throw conflict("INVALID_STATE_TRANSITION", "Run $id is ${run.state}; only FAILED runs can be retried")
            }
        }
        return view(id)
    }
}
