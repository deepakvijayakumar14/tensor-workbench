package dev.tensorworkbench.api.queue

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class WorkerInfo(val instance: String, val slots: Int, val lastSeen: Instant)

/**
 * In-memory record of worker processes seen polling recently. Used only for the
 * dashboard's capacity display; it plays no part in correctness.
 */
@Component
class WorkerRegistry {
    private val workers = ConcurrentHashMap<String, WorkerInfo>()

    fun seen(instance: String, slots: Int) {
        workers[instance] = WorkerInfo(instance, slots, Instant.now())
    }

    fun active(within: Duration = Duration.ofSeconds(30)): List<WorkerInfo> {
        val cutoff = Instant.now().minus(within)
        return workers.values.filter { it.lastSeen.isAfter(cutoff) }.sortedBy { it.instance }
    }

    fun totalSlots(): Int? = active().takeIf { it.isNotEmpty() }?.sumOf { it.slots }
}
