package dev.tensorworkbench.api.queue

import dev.tensorworkbench.api.dataset.DatasetService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Periodically requeues work whose lease expired (for example, after a worker was killed). */
@Component
@ConditionalOnProperty("workbench.queue.recovery-enabled", havingValue = "true", matchIfMissing = true)
class LeaseRecoveryJob(
    private val queue: WorkQueueService,
    private val datasets: DatasetService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${workbench.queue.recovery-interval:2s}")
    fun recover() {
        try {
            datasets.recoverExpiredLeases()
            queue.recoverExpiredAttempts()
        } catch (e: Exception) {
            log.error("Lease recovery pass failed; will retry next tick", e)
        }
    }
}
