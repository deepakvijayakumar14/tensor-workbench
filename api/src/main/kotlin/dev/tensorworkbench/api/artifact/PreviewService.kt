package dev.tensorworkbench.api.artifact

import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.DatasetService
import dev.tensorworkbench.api.db.elementList
import dev.tensorworkbench.api.run.PreviewAxis
import dev.tensorworkbench.api.run.PreviewInfo
import dev.tensorworkbench.api.run.RunRepository
import dev.tensorworkbench.api.run.RunState
import dev.tensorworkbench.api.storage.ArtifactStorage
import dev.tensorworkbench.api.web.FieldErrors
import dev.tensorworkbench.api.web.conflict
import dev.tensorworkbench.api.web.notFound
import dev.tensorworkbench.api.web.validate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Layout of a PREVIEW_STACK object: for each axis, every slice downsampled to at most
 * maxDimension x maxDimension and quantized to uint8 using the run's global min/max.
 * Slices are stored back to back, so one slice is one small byte-range read.
 */
data class PreviewLayout(
    val maxDimension: Int,
    val valueMin: Double,
    val valueMax: Double,
    val axes: List<AxisLayout>,
) {
    data class AxisLayout(
        val axis: Int,
        val sliceCount: Int,
        val height: Int,
        val width: Int,
        val offset: Long,
        val sourceHeight: Int,
        val sourceWidth: Int,
    ) {
        val sliceBytes: Int get() = height * width
    }

    val totalBytes: Long get() = axes.sumOf { it.sliceCount.toLong() * it.sliceBytes }

    fun toInfo() = PreviewInfo(
        maxDimension, valueMin, valueMax,
        axes.map { PreviewAxis(it.axis, it.sliceCount, it.height, it.width, it.sourceHeight, it.sourceWidth) },
    )

    companion object {
        fun parse(node: JsonNode) = PreviewLayout(
            maxDimension = node.get("maxDimension").asInt(),
            valueMin = node.get("valueMin").asDouble(),
            valueMax = node.get("valueMax").asDouble(),
            axes = node.get("axes").elementList().map {
                AxisLayout(
                    axis = it.get("axis").asInt(),
                    sliceCount = it.get("sliceCount").asInt(),
                    height = it.get("height").asInt(),
                    width = it.get("width").asInt(),
                    offset = it.get("offset").asLong(),
                    sourceHeight = it.get("sourceHeight").asInt(),
                    sourceWidth = it.get("sourceWidth").asInt(),
                )
            },
        )

        /** Validates a worker-reported layout before it can be published. */
        fun validateReported(
            errors: FieldErrors,
            field: String,
            metadata: JsonNode?,
            outputShape: List<Int>,
            maxDimension: Int,
            objectSize: Long?,
        ) {
            val layout = try {
                metadata?.let { parse(it) }
            } catch (e: RuntimeException) {
                null
            }
            if (layout == null) {
                errors.reject(field, "must describe maxDimension, valueMin, valueMax and three axes")
                return
            }
            errors.require(layout.valueMin.isFinite() && layout.valueMax.isFinite(), field) { "value range must be finite" }
            errors.require(layout.axes.map { it.axis } == listOf(0, 1, 2), field) { "must list axes 0, 1, 2 in order" }
            var expectedOffset = 0L
            layout.axes.forEach { a ->
                val others = outputShape.filterIndexed { i, _ -> i != a.axis }
                errors.require(a.sliceCount == outputShape.getOrNull(a.axis), field) { "axis ${a.axis} slice count mismatch" }
                errors.require(a.sourceHeight == others.getOrNull(0) && a.sourceWidth == others.getOrNull(1), field) {
                    "axis ${a.axis} source size mismatch"
                }
                errors.require(a.height in 1..maxDimension && a.width in 1..maxDimension, field) {
                    "axis ${a.axis} preview must be at most ${maxDimension}x$maxDimension"
                }
                errors.require(a.offset == expectedOffset, field) { "axis ${a.axis} offset must be $expectedOffset" }
                expectedOffset += a.sliceCount.toLong() * a.sliceBytes
            }
            errors.require(objectSize == null || objectSize == layout.totalBytes, field) {
                "layout describes ${layout.totalBytes} bytes but the object has $objectSize"
            }
        }
    }
}

data class PreviewSlice(
    val runId: UUID,
    val axis: Int,
    val index: Int,
    val sliceCount: Int,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val valueMin: Double,
    val valueMax: Double,
    val encoding: String,
    /** Base64 of width*height uint8 values, row-major. value = valueMin + q/255 * (valueMax - valueMin). */
    val data: String,
    val etag: String,
)

@Service
class PreviewService(
    private val runs: RunRepository,
    private val artifacts: ArtifactRepository,
    private val datasetService: DatasetService,
    private val storage: ArtifactStorage,
    private val props: WorkbenchProperties,
) {

    /** Reads exactly one downsampled slice (at most 128x128 bytes by default) from storage. */
    fun slice(runId: UUID, axis: Int, index: Int): PreviewSlice {
        val preview = acceptedArtifact(runId, ArtifactKind.PREVIEW_STACK)
        val layout = PreviewLayout.parse(preview.metadata)
        validate {
            require(axis in 0..2, "axis") { "must be 0, 1 or 2" }
        }
        val a = layout.axes[axis]
        validate {
            require(index in 0 until a.sliceCount, "index") { "must be between 0 and ${a.sliceCount - 1} for axis $axis" }
        }
        val bytes = storage.readRange(preview.objectKey, a.offset + index.toLong() * a.sliceBytes, a.sliceBytes)
        check(bytes.size == a.sliceBytes) { "Short preview read: ${bytes.size} of ${a.sliceBytes} bytes" }
        return PreviewSlice(
            runId = runId,
            axis = axis,
            index = index,
            sliceCount = a.sliceCount,
            width = a.width,
            height = a.height,
            sourceWidth = a.sourceWidth,
            sourceHeight = a.sourceHeight,
            valueMin = layout.valueMin,
            valueMax = layout.valueMax,
            encoding = "uint8",
            data = Base64.getEncoder().encodeToString(bytes),
            etag = "\"${preview.id}-$axis-$index\"",
        )
    }

    /** A short-lived URL for the full result. Nothing is buffered in the API. */
    fun outputDownloadUrl(runId: UUID): URI {
        val output = acceptedArtifact(runId, ArtifactKind.OUTPUT_TENSOR)
        val run = runs.find(runId)!!
        val gain = run.gain.stripTrailingZeros().toPlainString()
        val filename = "run-${runId.toString().take(8)}-gain-$gain-v${run.implementationVersion}.npy"
        return storage.presignDownload(output.objectKey, filename, props.storage.downloadUrlTtl)
    }

    fun inputDownloadUrl(datasetId: UUID): URI {
        val (dataset, input) = datasetService.readyInput(datasetId)
        val filename = "dataset-${datasetId.toString().take(8)}-seed-${dataset.seed}.npy"
        return storage.presignDownload(input.objectKey, filename, props.storage.downloadUrlTtl)
    }

    private fun acceptedArtifact(runId: UUID, kind: ArtifactKind): Artifact {
        val run = runs.find(runId) ?: throw notFound("Run", runId)
        if (run.state != RunState.SUCCEEDED || run.acceptedAttemptId == null) {
            throw conflict("RUN_NOT_SUCCEEDED", "Run $runId is ${run.state}; results exist only for SUCCEEDED runs")
        }
        return artifacts.forAttempt(run.acceptedAttemptId, kind)
            ?: error("Accepted attempt ${run.acceptedAttemptId} has no $kind artifact")
    }
}
