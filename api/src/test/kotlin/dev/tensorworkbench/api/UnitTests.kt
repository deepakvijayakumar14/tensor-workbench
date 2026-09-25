package dev.tensorworkbench.api

import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.idempotency.RequestHash
import dev.tensorworkbench.api.queue.ErrorCategory
import dev.tensorworkbench.api.queue.RetryDecision
import dev.tensorworkbench.api.queue.RetryPolicy
import dev.tensorworkbench.api.run.AttemptInterval
import dev.tensorworkbench.api.sweep.SweepRange
import dev.tensorworkbench.api.sweep.SweepService
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SweepRangeTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `inclusive end when a step lands on it`() {
        assertEquals((1..20).map { BigDecimal(it) }, SweepRange.expand(d("1"), d("20"), d("1")))
    }

    @Test
    fun `end is excluded when not reached exactly`() {
        assertEquals(listOf("0", "0.3", "0.6", "0.9"), SweepRange.expand(d("0"), d("1"), d("0.3")).map { it.toPlainString() })
    }

    @Test
    fun `no floating point drift`() {
        val values = SweepRange.expand(d("0"), d("1"), d("0.1"))
        assertEquals(11, values.size)
        assertEquals("1", values.last().toPlainString())
    }

    @Test
    fun `count is computed without expanding`() {
        assertEquals(1_000_001, SweepRange.count(d("0"), d("1000000"), d("1")))
    }
}

class RetryPolicyTest {
    private val props = WorkbenchProperties(
        storage = WorkbenchProperties.Storage("b", URI("http://s"), URI("http://s"), accessKey = "a", secretKey = "s"),
        worker = WorkbenchProperties.Worker("t"),
        queue = WorkbenchProperties.Queue(backoffBase = Duration.ofSeconds(2), backoffMax = Duration.ofSeconds(10)),
    )
    private val policy = RetryPolicy(props).also { it.random = Random(42) }

    @Test
    fun `only transient categories retry, and only within the attempt budget`() {
        assertIs<RetryDecision.RetryAfter>(policy.decide(ErrorCategory.TRANSIENT, 1, 3))
        assertIs<RetryDecision.RetryAfter>(policy.decide(ErrorCategory.LEASE_EXPIRED, 2, 3))
        assertEquals(RetryDecision.GiveUp, policy.decide(ErrorCategory.TRANSIENT, 3, 3))
        assertEquals(RetryDecision.GiveUp, policy.decide(ErrorCategory.INVALID_INPUT, 1, 3))
        assertEquals(RetryDecision.GiveUp, policy.decide(ErrorCategory.RESOURCE_EXHAUSTED, 1, 3))
        assertEquals(RetryDecision.GiveUp, policy.decide(ErrorCategory.UNSUPPORTED_VERSION, 1, 3))
    }

    @Test
    fun `backoff grows exponentially, is capped and jittered within half to full delay`() {
        repeat(50) {
            val first = policy.backoff(1).toMillis()
            val third = policy.backoff(3).toMillis()
            val tenth = policy.backoff(10).toMillis()
            assertTrue(first in 1000..2000, "first=$first")
            assertTrue(third in 4000..8000, "third=$third")
            assertTrue(tenth in 5000..10000, "tenth=$tenth")
        }
    }
}

class RequestHashTest {
    @Test
    fun `hash ignores key order and trailing zeros but not values`() {
        val a = RequestHash.of(mapOf("gain" to BigDecimal("2.50"), "shape" to listOf(1, 2, 3)))
        val b = RequestHash.of(mapOf("shape" to listOf(1, 2, 3), "gain" to BigDecimal("2.5")))
        val c = RequestHash.of(mapOf("shape" to listOf(1, 2, 3), "gain" to BigDecimal("2.6")))
        assertEquals(a, b)
        assertTrue(a != c)
    }
}

class PeakOverlapTest {
    private fun interval(start: Long, end: Long?) = AttemptInterval(
        UUID.randomUUID(), 0, 1, "w", null, null, Instant.ofEpochSecond(start), end?.let { Instant.ofEpochSecond(it) }, true,
    )

    @Test
    fun `back to back intervals do not overlap and open intervals run until now`() {
        val intervals = listOf(interval(0, 10), interval(10, 20), interval(5, 15), interval(12, null))
        assertEquals(3, SweepService.peakOverlap(intervals, Instant.ofEpochSecond(30)))
        assertEquals(1, SweepService.peakOverlap(listOf(interval(0, 1), interval(1, 2)), Instant.ofEpochSecond(3)))
    }
}
