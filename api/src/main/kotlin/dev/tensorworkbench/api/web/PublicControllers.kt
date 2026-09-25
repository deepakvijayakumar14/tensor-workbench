package dev.tensorworkbench.api.web

import dev.tensorworkbench.api.artifact.PreviewService
import dev.tensorworkbench.api.artifact.PreviewSlice
import dev.tensorworkbench.api.dataset.CreateDatasetRequest
import dev.tensorworkbench.api.dataset.DatasetService
import dev.tensorworkbench.api.dataset.DatasetView
import dev.tensorworkbench.api.dataset.Submission
import dev.tensorworkbench.api.run.RunDetail
import dev.tensorworkbench.api.run.RunService
import dev.tensorworkbench.api.run.RunView
import dev.tensorworkbench.api.run.SubmitRunRequest
import dev.tensorworkbench.api.sweep.SubmitSweepRequest
import dev.tensorworkbench.api.sweep.SweepService
import dev.tensorworkbench.api.sweep.SweepTimeline
import dev.tensorworkbench.api.sweep.SweepView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

const val IDEMPOTENCY_HEADER = "Idempotency-Key"

/** 202 Accepted for asynchronous work, whether newly created or replayed from the idempotency key. */
private fun <T : Any> accepted(submission: Submission<T>, location: String): ResponseEntity<T> =
    ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create(location))
        .header("Idempotent-Replayed", submission.replayed.toString())
        .body(submission.resource)

private fun redirect(url: URI): ResponseEntity<Void> =
    ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(CacheControl.noStore()).build()

@RestController
@RequestMapping("/api/datasets")
@Tag(name = "Datasets", description = "Reproducible synthetic int32 input tensors")
class DatasetController(private val datasets: DatasetService, private val previews: PreviewService) {

    @PostMapping
    @Operation(summary = "Queue generation of a synthetic int32 tensor (asynchronous)")
    fun create(
        @RequestHeader(IDEMPOTENCY_HEADER) key: String,
        @RequestBody request: CreateDatasetRequest,
    ): ResponseEntity<DatasetView> {
        val submission = datasets.create(key, request)
        return accepted(submission, "/api/datasets/${submission.resource.id}")
    }

    @GetMapping
    fun list(@RequestParam page: Int?, @RequestParam size: Int?): Page<DatasetView> =
        datasets.list(PageRequest.of(page, size))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): DatasetView = datasets.get(id)

    @GetMapping("/{id}/download")
    @Operation(summary = "Redirect to a short-lived URL for the full input .npy file")
    fun download(@PathVariable id: UUID): ResponseEntity<Void> = redirect(previews.inputDownloadUrl(id))
}

@RestController
@RequestMapping("/api/runs")
@Tag(name = "Runs", description = "Individual computations, their attempts and results")
class RunController(private val runs: RunService, private val previews: PreviewService) {

    @PostMapping
    @Operation(summary = "Submit one computation (asynchronous)")
    fun submit(
        @RequestHeader(IDEMPOTENCY_HEADER) key: String,
        @RequestBody request: SubmitRunRequest,
    ): ResponseEntity<RunView> {
        val submission = runs.submit(key, request)
        return accepted(submission, "/api/runs/${submission.resource.id}")
    }

    @GetMapping
    @Operation(summary = "List individual (non-sweep) runs, newest first")
    fun list(@RequestParam page: Int?, @RequestParam size: Int?): Page<RunView> =
        runs.listStandalone(PageRequest.of(page, size))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): RunDetail = runs.detail(id)

    @PostMapping("/{id}/retry")
    @Operation(summary = "Explicitly retry a terminally FAILED run (409 for any other state)")
    fun retry(@PathVariable id: UUID): ResponseEntity<RunView> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(runs.retry(id))

    @GetMapping("/{id}/preview")
    @Operation(summary = "One downsampled, 8-bit quantized slice of the accepted output (bounded size)")
    fun preview(@PathVariable id: UUID, @RequestParam axis: Int, @RequestParam index: Int): ResponseEntity<PreviewSlice> {
        val slice = previews.slice(id, axis, index)
        // Accepted artifacts are immutable, so the browser may cache slices freely.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate().immutable())
            .header(HttpHeaders.ETAG, slice.etag)
            .body(slice)
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Redirect to a short-lived URL for the full float32 .npy result")
    fun download(@PathVariable id: UUID): ResponseEntity<Void> = redirect(previews.outputDownloadUrl(id))
}

data class RetryFailedResponse(val requeued: Int)

@RestController
@RequestMapping("/api/sweeps")
@Tag(name = "Sweeps", description = "Parameter sweeps that fan out into child runs")
class SweepController(private val sweeps: SweepService) {

    @PostMapping
    @Operation(summary = "Submit a parameter sweep (asynchronous)")
    fun submit(
        @RequestHeader(IDEMPOTENCY_HEADER) key: String,
        @RequestBody request: SubmitSweepRequest,
    ): ResponseEntity<SweepView> {
        val submission = sweeps.submit(key, request)
        return accepted(submission, "/api/sweeps/${submission.resource.id}")
    }

    @GetMapping
    fun list(@RequestParam page: Int?, @RequestParam size: Int?): Page<SweepView> =
        sweeps.list(PageRequest.of(page, size))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): SweepView = sweeps.get(id)

    @GetMapping("/{id}/runs")
    @Operation(summary = "Paginated child runs ordered by sweep ordinal, with compact summaries")
    fun runs(@PathVariable id: UUID, @RequestParam page: Int?, @RequestParam size: Int?): Page<RunView> =
        sweeps.runs(id, PageRequest.of(page, size))

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Attempt intervals and the peak number of simultaneous computations")
    fun timeline(@PathVariable id: UUID): SweepTimeline = sweeps.timeline(id)

    @PostMapping("/{id}/retry-failed")
    @Operation(summary = "Requeue only FAILED children; succeeded children are untouched")
    fun retryFailed(@PathVariable id: UUID): ResponseEntity<RetryFailedResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(RetryFailedResponse(sweeps.retryFailed(id)))
}
