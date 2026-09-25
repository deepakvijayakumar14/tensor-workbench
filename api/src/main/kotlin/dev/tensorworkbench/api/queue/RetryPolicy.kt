package dev.tensorworkbench.api.queue

import dev.tensorworkbench.api.config.WorkbenchProperties
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.math.min
import kotlin.random.Random

/**
 * Failure categories reported by the worker (or assigned by lease recovery).
 * Only transient categories are retried automatically.
 */
enum class ErrorCategory(val retryable: Boolean) {
    /** Network/storage/service hiccup; the same request may well succeed next time. */
    TRANSIENT(true),

    /** The worker stopped on purpose (deploy/restart) before finishing. */
    WORKER_SHUTDOWN(true),

    /** No heartbeat within the lease; the worker may be dead or partitioned. */
    LEASE_EXPIRED(true),

    /** The input is malformed. Retrying the same request cannot help. */
    INVALID_INPUT(false),

    /** The requested implementation version is not the deployed one. */
    UNSUPPORTED_VERSION(false),

    /** The unchanged request exhausted memory or disk; retrying blindly would repeat that. */
    RESOURCE_EXHAUSTED(false),

    /** Unexpected bug. Needs a human; an explicit retry is still available. */
    INTERNAL(false),
}

sealed interface RetryDecision {
    data class RetryAfter(val delay: Duration) : RetryDecision
    data object GiveUp : RetryDecision
}

@Component
class RetryPolicy(private val props: WorkbenchProperties) {
    /** Replaceable in tests for deterministic jitter. */
    internal var random: Random = Random.Default

    fun decide(category: ErrorCategory, attemptsSoFar: Int, maxAttempts: Int): RetryDecision {
        if (!category.retryable || attemptsSoFar >= maxAttempts) return RetryDecision.GiveUp
        return RetryDecision.RetryAfter(backoff(attemptsSoFar))
    }

    /**
     * Exponential backoff with "equal jitter": half the capped delay is fixed and
     * half is random, so retries spread out but never fire immediately.
     */
    fun backoff(attemptsSoFar: Int): Duration {
        val baseMs = props.queue.backoffBase.toMillis().toDouble()
        val maxMs = props.queue.backoffMax.toMillis().toDouble()
        val exponential = baseMs * Math.pow(2.0, (attemptsSoFar - 1).coerceAtLeast(0).toDouble())
        val capped = min(maxMs, exponential)
        val jittered = capped / 2 + random.nextDouble() * (capped / 2)
        return Duration.ofMillis(jittered.toLong())
    }
}
