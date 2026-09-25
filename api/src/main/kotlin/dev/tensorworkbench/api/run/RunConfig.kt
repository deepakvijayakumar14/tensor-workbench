package dev.tensorworkbench.api.run

import dev.tensorworkbench.api.artifact.Artifact
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.Dataset
import dev.tensorworkbench.api.idempotency.RequestHash
import dev.tensorworkbench.api.web.FieldErrors
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/** Deterministic failures a client can request explicitly. Demo and tests only. */
enum class FaultInjection {
    /** Fails with a TRANSIENT error on attempt 1, then succeeds. */
    TRANSIENT_ON_FIRST_ATTEMPT,

    /** Fails with a permanent INVALID_INPUT error on every attempt. */
    INVALID_INPUT,
}

/**
 * Builds the immutable configuration snapshot a worker executes. The snapshot pins
 * the implementation version and the exact input object, so re-running a run means
 * the same computation, and a different version or parameter means a new run.
 */
@Component
class RunConfigFactory(private val props: WorkbenchProperties, private val mapper: ObjectMapper) {

    fun snapshot(
        dataset: Dataset,
        input: Artifact,
        gain: BigDecimal,
        bias: BigDecimal,
        implementationVersion: String,
        faultInjection: FaultInjection?,
    ): Pair<JsonNode, String> {
        val node = mapper.createObjectNode().apply {
            put("function", props.computation.functionName)
            put("implementationVersion", implementationVersion)
            putObject("parameters").apply {
                // Decimal strings keep the exact requested values.
                put("gain", gain.stripTrailingZeros().toPlainString())
                put("bias", bias.stripTrailingZeros().toPlainString())
            }
            putObject("input").apply {
                put("datasetId", dataset.id.toString())
                put("objectKey", input.objectKey)
                put("sha256", input.sha256)
                put("dtype", input.dtype)
                putArray("shape").apply { input.shape.forEach { add(it) } }
            }
            putObject("output").apply {
                put("dtype", "float32")
                putArray("shape").apply { input.shape.forEach { add(it) } }
            }
            putObject("preview").put("maxDimension", props.limits.previewMaxDimension)
            if (faultInjection != null) put("faultInjection", faultInjection.name) else putNull("faultInjection")
        }
        val hash = RequestHash.of(
            mapOf(
                "function" to props.computation.functionName,
                "implementationVersion" to implementationVersion,
                "gain" to gain,
                "bias" to bias,
                "inputSha256" to input.sha256,
                "shape" to input.shape,
                "previewMaxDimension" to props.limits.previewMaxDimension,
                "faultInjection" to faultInjection?.name,
            ),
        )
        return node to hash
    }

    fun validateParameter(errors: FieldErrors, field: String, value: BigDecimal?) {
        if (value == null) return
        val max = props.limits.maxAbsParameter
        errors.require(value.abs() <= max, field) { "must be between -$max and $max" }
        errors.require(value.stripTrailingZeros().scale() <= MAX_DECIMAL_PLACES, field) {
            "may have at most $MAX_DECIMAL_PLACES decimal places"
        }
    }

    fun validateVersion(errors: FieldErrors, version: String?) {
        val v = errors.required(version, "implementationVersion") ?: return
        errors.require(v in props.computation.supportedVersions, "implementationVersion") {
            "'$v' is not supported by the deployed worker; supported: ${props.computation.supportedVersions.joinToString()}"
        }
    }

    fun parseFaultInjection(errors: FieldErrors, raw: String?): FaultInjection? {
        if (raw == null) return null
        val parsed = FaultInjection.entries.firstOrNull { it.name == raw }
        errors.require(parsed != null, "faultInjection") { "must be one of ${FaultInjection.entries.joinToString()}" }
        errors.require(props.demo.allowFaultInjection, "faultInjection") { "is disabled in this deployment" }
        return parsed
    }

    companion object {
        const val MAX_DECIMAL_PLACES = 6
    }
}
