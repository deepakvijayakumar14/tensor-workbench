package dev.tensorworkbench.api.sweep

import dev.tensorworkbench.api.db.normalized
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.DatasetService
import dev.tensorworkbench.api.dataset.Submission
import dev.tensorworkbench.api.db.inTx
import dev.tensorworkbench.api.db.instant
import dev.tensorworkbench.api.db.uuid
import dev.tensorworkbench.api.idempotency.IdempotencyStore
import dev.tensorworkbench.api.idempotency.RequestHash
import dev.tensorworkbench.api.idempotency.Reservation
import dev.tensorworkbench.api.run.AttemptInterval
import dev.tensorworkbench.api.run.AttemptRepository
import dev.tensorworkbench.api.run.FaultInjection
import dev.tensorworkbench.api.run.NewRun
import dev.tensorworkbench.api.run.RunConfigFactory
import dev.tensorworkbench.api.run.RunRepository
import dev.tensorworkbench.api.run.RunState
import dev.tensorworkbench.api.run.RunView
import dev.tensorworkbench.api.web.FieldErrors
import dev.tensorworkbench.api.web.Page
import dev.tensorworkbench.api.web.PageRequest
import dev.tensorworkbench.api.web.notFound
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SubmitSweepRequest(
    val datasetId: UUID?,
    /** "gain" or "bias". */
    val parameter: String?,
    val start: BigDecimal?,
    val end: BigDecimal?,
    val step: BigDecimal?,
    /** Fixed gain when sweeping bias (default 1). */
    val gain: BigDecimal?,
    /** Fixed bias when sweeping gain (default 0). */
    val bias: BigDecimal?,
    val implementationVersion: String?,
    /** Demo option: one child fails transiently on its first attempt, then succeeds. */
    val demoTransientFailure: Boolean?,
)

data class SweepCounts(val queued: Long, val running: Long, val succeeded: Long, val failed: Long) {
    val total: Long get() = queued + running + succeeded + failed
    val done: Boolean get() = queued == 0L && running == 0L
}

data class SweepView(
    val id: UUID,
    val datasetId: UUID,
    val parameter: String,
    val start: BigDecimal,
    val end: BigDecimal,
    val step: BigDecimal,
    val variantCount: Int,
    val implementationVersion: String,
    val fixedGain: BigDecimal?,
    val fixedBias: BigDecimal?,
    val demoTransientFailureOrdinal: Int?,
    val counts: SweepCounts,
    val retries: Long,
    val done: Boolean,
    val createdAt: Instant,
)

data class SweepTimeline(
    val sweepId: UUID,
    val workerSlots: Int?,
    /** Maximum number of attempts whose intervals overlapped at any instant. */
    val peakConcurrency: Int,
    val intervals: List<AttemptInterval>,
    val now: Instant,
)

private data class SweepRecord(
    val id: UUID,
    val datasetId: UUID,
    val parameter: String,
    val start: BigDecimal,
    val end: BigDecimal,
    val step: BigDecimal,
    val variantCount: Int,
    val implementationVersion: String,
    val snapshot: JsonNode,
    val createdAt: Instant,
)

@Service
class SweepService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val runs: RunRepository,
    private val attempts: AttemptRepository,
    private val datasetService: DatasetService,
    private val configFactory: RunConfigFactory,
    private val idempotency: IdempotencyStore,
    private val workerRegistry: dev.tensorworkbench.api.queue.WorkerRegistry,
    private val props: WorkbenchProperties,
    private val tx: TransactionTemplate,
) {
    private val sweepMapper = RowMapper { rs, _ ->
        SweepRecord(
            id = rs.uuid("id"),
            datasetId = rs.uuid("dataset_id"),
            parameter = rs.getString("parameter_name"),
            start = rs.getBigDecimal("range_start"),
            end = rs.getBigDecimal("range_end"),
            step = rs.getBigDecimal("range_step"),
            variantCount = rs.getInt("variant_count"),
            implementationVersion = rs.getString("implementation_version"),
            snapshot = mapper.readTree(rs.getString("config_snapshot")),
            createdAt = rs.instant("created_at"),
        )
    }

    fun submit(idempotencyKey: String, request: SubmitSweepRequest): Submission<SweepView> {
        val values = validate(request)
        val parameter = request.parameter!!
        val fixedGain = if (parameter == "bias") request.gain ?: BigDecimal.ONE else null
        val fixedBias = if (parameter == "gain") request.bias ?: BigDecimal.ZERO else null
        val demo = request.demoTransientFailure == true
        // Deterministic demo target: the 4th child (or the last one in a short sweep).
        val demoOrdinal = if (demo) minOf(3, values.size - 1) else null

        val hash = RequestHash.of(
            mapOf(
                "datasetId" to request.datasetId,
                "parameter" to parameter,
                "start" to request.start,
                "end" to request.end,
                "step" to request.step,
                "fixedGain" to fixedGain,
                "fixedBias" to fixedBias,
                "implementationVersion" to request.implementationVersion,
                "demoTransientFailure" to demo,
            ),
        )

        val reservation = tx.inTx {
            val r = idempotency.reserve("submit_sweep", idempotencyKey, hash, "sweep")
            if (r is Reservation.New) {
                val (dataset, input) = datasetService.readyInput(request.datasetId!!)
                val sweepSnapshot = mapper.createObjectNode().apply {
                    put("function", props.computation.functionName)
                    put("implementationVersion", request.implementationVersion)
                    put("parameter", parameter)
                    putObject("range").apply {
                        put("start", request.start!!.stripTrailingZeros().toPlainString())
                        put("end", request.end!!.stripTrailingZeros().toPlainString())
                        put("step", request.step!!.stripTrailingZeros().toPlainString())
                        put("endpoint", "inclusive when end = start + k*step exactly")
                    }
                    putObject("fixed").apply {
                        fixedGain?.let { put("gain", it.stripTrailingZeros().toPlainString()) }
                        fixedBias?.let { put("bias", it.stripTrailingZeros().toPlainString()) }
                    }
                    put("inputSha256", input.sha256)
                    if (demoOrdinal != null) put("demoTransientFailureOrdinal", demoOrdinal) else putNull("demoTransientFailureOrdinal")
                }
                jdbc.sql(
                    """
                    INSERT INTO sweeps (id, dataset_id, parameter_name, range_start, range_end, range_step,
                                        variant_count, implementation_version, config_snapshot)
                    VALUES (:id, :datasetId, :parameter, :start, :end, :step, :count, :version, CAST(:snapshot AS jsonb))
                    """,
                )
                    .param("id", r.resourceId)
                    .param("datasetId", dataset.id)
                    .param("parameter", parameter)
                    .param("start", request.start)
                    .param("end", request.end)
                    .param("step", request.step)
                    .param("count", values.size)
                    .param("version", request.implementationVersion)
                    .param("snapshot", mapper.writeValueAsString(sweepSnapshot))
                    .update()

                values.forEachIndexed { ordinal, value ->
                    val gain = if (parameter == "gain") value else fixedGain!!
                    val bias = if (parameter == "bias") value else fixedBias!!
                    val fault = if (ordinal == demoOrdinal) FaultInjection.TRANSIENT_ON_FIRST_ATTEMPT else null
                    val (snapshot, configHash) =
                        configFactory.snapshot(dataset, input, gain, bias, request.implementationVersion!!, fault)
                    runs.insert(
                        NewRun(
                            id = UUID.randomUUID(),
                            datasetId = dataset.id,
                            sweepId = r.resourceId,
                            sweepOrdinal = ordinal,
                            gain = gain,
                            bias = bias,
                            implementationVersion = request.implementationVersion,
                            configSnapshot = snapshot,
                            configHash = configHash,
                            maxAttempts = props.queue.maxAttempts,
                        ),
                    )
                }
            }
            r
        }
        return Submission(get(reservation.resourceId), replayed = reservation is Reservation.Replay)
    }

    fun get(id: UUID): SweepView = view(find(id))

    fun list(page: PageRequest): Page<SweepView> {
        val items = jdbc.sql("SELECT * FROM sweeps ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
            .param("limit", page.size)
            .param("offset", page.offset)
            .query(sweepMapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM sweeps").query(Long::class.java).single()
        return Page(items.map(::view), page.page, page.size, total)
    }

    fun runs(id: UUID, page: PageRequest): Page<RunView> {
        find(id)
        val result = runs.pageForSweep(id, page)
        val now = Instant.now()
        return Page(result.items.map { RunView.of(it, now) }, result.page, result.size, result.totalItems)
    }

    /** Requeues only FAILED children. Succeeded children keep their accepted results. */
    fun retryFailed(id: UUID): Int {
        find(id)
        return tx.inTx { runs.requeueFailedInSweep(id, props.queue.maxAttempts) }
    }

    fun timeline(id: UUID): SweepTimeline {
        find(id)
        val intervals = attempts.intervalsForSweep(id)
        val now = Instant.now()
        return SweepTimeline(id, workerRegistry.totalSlots(), peakOverlap(intervals, now), intervals, now)
    }

    private fun find(id: UUID): SweepRecord =
        jdbc.sql("SELECT * FROM sweeps WHERE id = :id").param("id", id).query(sweepMapper).optional().orElse(null)
            ?: throw notFound("Sweep", id)

    private fun view(s: SweepRecord): SweepView {
        val byState = runs.countsForSweep(s.id)
        val counts = SweepCounts(
            queued = byState[RunState.QUEUED] ?: 0,
            running = byState[RunState.RUNNING] ?: 0,
            succeeded = byState[RunState.SUCCEEDED] ?: 0,
            failed = byState[RunState.FAILED] ?: 0,
        )
        val fixed = s.snapshot.get("fixed")
        return SweepView(
            id = s.id,
            datasetId = s.datasetId,
            parameter = s.parameter,
            start = s.start.normalized(),
            end = s.end.normalized(),
            step = s.step.normalized(),
            variantCount = s.variantCount,
            implementationVersion = s.implementationVersion,
            fixedGain = fixed?.get("gain")?.asString()?.let(::BigDecimal),
            fixedBias = fixed?.get("bias")?.asString()?.let(::BigDecimal),
            demoTransientFailureOrdinal = s.snapshot.get("demoTransientFailureOrdinal")?.takeUnless { it.isNull }?.asInt(),
            counts = counts,
            retries = attempts.retriesInSweep(s.id),
            done = counts.done,
            createdAt = s.createdAt,
        )
    }

    private fun validate(request: SubmitSweepRequest): List<BigDecimal> {
        val errors = FieldErrors()
        errors.required(request.datasetId, "datasetId")
        val parameter = errors.required(request.parameter, "parameter")
        if (parameter != null) {
            errors.require(parameter in setOf("gain", "bias"), "parameter") { "must be 'gain' or 'bias'" }
            errors.require(!(parameter == "gain" && request.gain != null), "gain") { "must not be set when sweeping gain" }
            errors.require(!(parameter == "bias" && request.bias != null), "bias") { "must not be set when sweeping bias" }
        }
        val start = errors.required(request.start, "start")
        val end = errors.required(request.end, "end")
        val step = errors.required(request.step, "step")
        configFactory.validateParameter(errors, "start", start)
        configFactory.validateParameter(errors, "end", end)
        configFactory.validateParameter(errors, "step", step)
        configFactory.validateParameter(errors, "gain", request.gain)
        configFactory.validateParameter(errors, "bias", request.bias)
        configFactory.validateVersion(errors, request.implementationVersion)
        if (request.demoTransientFailure == true) {
            errors.require(props.demo.allowFaultInjection, "demoTransientFailure") { "is disabled in this deployment" }
        }
        if (step != null) errors.require(step.signum() > 0, "step") { "must be greater than zero" }
        if (start != null && end != null) errors.require(start <= end, "end") { "must be greater than or equal to start" }
        errors.throwIfAny()

        val count = SweepRange.count(start!!, end!!, step!!)
        val max = props.limits.maxSweepVariants
        if (count > max) {
            errors.reject("step", "produces $count variants; the configured maximum is $max")
            errors.throwIfAny()
        }
        return SweepRange.expand(start, end, step)
    }

    companion object {
        /** Sweep-line over [start, end) intervals; open intervals end "now". */
        fun peakOverlap(intervals: List<AttemptInterval>, now: Instant): Int {
            val events = intervals.flatMap { i ->
                val end = i.end ?: now
                listOf(i.start to +1, end to -1)
            }
            // At equal instants process ends before starts: back-to-back work is not overlap.
            val sorted = events.sortedWith(compareBy<Pair<Instant, Int>> { it.first }.thenBy { it.second })
            var current = 0
            var peak = 0
            sorted.forEach { (_, delta) ->
                current += delta
                peak = maxOf(peak, current)
            }
            return peak
        }
    }
}
