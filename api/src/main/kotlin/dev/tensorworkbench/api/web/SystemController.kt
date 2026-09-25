package dev.tensorworkbench.api.web

import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.dataset.DatasetRepository
import dev.tensorworkbench.api.dataset.DatasetStatus
import dev.tensorworkbench.api.queue.WorkerInfo
import dev.tensorworkbench.api.queue.WorkerRegistry
import dev.tensorworkbench.api.run.RunRepository
import dev.tensorworkbench.api.run.RunState
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SystemInfo(
    val disclaimer: String,
    val functionName: String,
    val functionDescription: String,
    val supportedVersions: List<String>,
    val generatorVersion: String,
    val limits: WorkbenchProperties.Limits,
    val maxAttempts: Int,
    val leaseSeconds: Double,
    val allowFaultInjection: Boolean,
)

data class SystemStatus(
    val workers: List<WorkerInfo>,
    val totalSlots: Int?,
    val runs: Map<RunState, Long>,
    val datasets: Map<DatasetStatus, Long>,
)

@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Configuration, limits and live capacity")
class SystemController(
    private val props: WorkbenchProperties,
    private val registry: WorkerRegistry,
    private val runs: RunRepository,
    private val datasets: DatasetRepository,
) {

    @GetMapping
    fun info() = SystemInfo(
        disclaimer = "Synthetic workload for demonstrating asynchronous array processing. Not a physics solver.",
        functionName = props.computation.functionName,
        functionDescription = "output[x,y,z] = gain * coefficient[input[x,y,z]] + bias, as float32, " +
            "where input holds int32 material codes 0-7 and coefficient is a fixed synthetic table",
        supportedVersions = props.computation.supportedVersions,
        generatorVersion = props.computation.generatorVersion,
        limits = props.limits,
        maxAttempts = props.queue.maxAttempts,
        leaseSeconds = props.queue.leaseDuration.toMillis() / 1000.0,
        allowFaultInjection = props.demo.allowFaultInjection,
    )

    @GetMapping("/status")
    fun status(): SystemStatus {
        val runCounts = runs.countByState()
        val datasetCounts = datasets.countByStatus()
        return SystemStatus(
            workers = registry.active(),
            totalSlots = registry.totalSlots(),
            runs = RunState.entries.associateWith { runCounts[it] ?: 0 },
            datasets = DatasetStatus.entries.associateWith { datasetCounts[it] ?: 0 },
        )
    }
}
