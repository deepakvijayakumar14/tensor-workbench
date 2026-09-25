package dev.tensorworkbench.api

import dev.tensorworkbench.api.queue.WorkQueueService
import dev.tensorworkbench.api.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Claims, retries, lease recovery and fencing (acceptance checks 4, 5 and 6). */
class QueueIT : IntegrationTest() {

    @Autowired
    lateinit var queue: WorkQueueService

    private fun fail(task: tools.jackson.databind.JsonNode, category: String) =
        internal("/attempts/${attemptId(task)}/fail", mapOf("leaseToken" to task["leaseToken"].asLong(), "category" to category, "message" to "test"))

    private fun complete(task: tools.jackson.databind.JsonNode, body: Map<String, Any?> = completionBody(task)) =
        internal("/attempts/${attemptId(task)}/complete", body)

    @Test
    fun `concurrent claimers never receive the same run`() {
        val datasetId = readyDataset()
        repeat(12) { submitRun(datasetId, gain = it + 1) }
        val pool = Executors.newFixedThreadPool(6)
        val claimed = pool.invokeAll((1..12).map { i -> Callable { claim("w/slot-$i")!!["run"]["runId"].asString() } }).map { it.get() }
        pool.shutdown()

        assertEquals(12, claimed.toSet().size)
        assertNull(claim())
    }

    @Test
    fun `transient failure is retried under the same run and the retry becomes authoritative`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        val first = claim()!!
        assertEquals("QUEUED", fail(first, "TRANSIENT").body!!["nextState"].asString())

        // Backoff: not claimable immediately, claimable once the delay has passed.
        assertNull(claim())
        skipBackoff()
        val second = claim()!!
        assertEquals(runId, second["run"]["runId"].asString())
        assertEquals(2, second["run"]["attemptNumber"].asInt())
        assertNotEquals(first["objectPrefix"].asString(), second["objectPrefix"].asString())

        assertEquals(204, complete(second).status)
        val detail = get("/api/runs/$runId").body!!
        assertEquals("SUCCEEDED", detail["run"]["state"].asString())
        assertEquals(2, detail["run"]["result"]["acceptedAttemptNumber"].asInt())
        val attempts = detail["attempts"]
        assertEquals(listOf("FAILED", "SUCCEEDED"), (0 until attempts.size()).map { attempts[it]["outcome"].asString() })
    }

    @Test
    fun `permanent invalid input fails immediately without consuming transient retries`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        val task = claim()!!
        assertEquals("FAILED", fail(task, "INVALID_INPUT").body!!["nextState"].asString())

        val run = get("/api/runs/$runId").body!!["run"]
        assertEquals(1, run["attemptCount"].asInt())
        assertEquals("INVALID_INPUT", run["lastErrorCategory"].asString())
        assertNull(claim())
    }

    @Test
    fun `transient failures stop after max attempts, then an explicit retry is allowed once`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        repeat(3) {
            skipBackoff()
            fail(claim()!!, "TRANSIENT")
        }
        assertEquals("FAILED", runState(runId))

        val retry = post("/api/runs/$runId/retry")
        assertEquals(202, retry.status)
        assertEquals("QUEUED", retry.body!!["state"].asString())
        assertEquals(409, post("/api/runs/$runId/retry").status) // no longer FAILED

        val fourth = claim()!!
        assertEquals(4, fourth["run"]["attemptNumber"].asInt()) // history is kept, numbering continues
        complete(fourth)
        assertEquals(409, post("/api/runs/$runId/retry").status) // succeeded runs are never re-run
    }

    @Test
    fun `sweep retry requeues only failed children`() {
        val datasetId = readyDataset()
        val sweep = post(
            "/api/sweeps",
            mapOf("datasetId" to datasetId, "parameter" to "gain", "start" to 1, "end" to 3, "step" to 1, "implementationVersion" to "1.0.0"),
            newKey(),
        ).body!!
        val tasks = (1..3).map { claim()!! }
        complete(tasks[0])
        complete(tasks[1])
        fail(tasks[2], "INVALID_INPUT")

        val requeued = post("/api/sweeps/${sweep["id"].asString()}/retry-failed").body!!["requeued"].asInt()
        assertEquals(1, requeued)
        val counts = get("/api/sweeps/${sweep["id"].asString()}").body!!["counts"]
        assertEquals(2, counts["succeeded"].asInt())
        assertEquals(1, counts["queued"].asInt())
        assertEquals(2, jdbc.sql("SELECT count(*) FROM run_attempts WHERE outcome = 'SUCCEEDED'").query(Long::class.java).single())
    }

    @Test
    fun `expired lease is recovered and the work is retried`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        val task = claim()!!
        expireLease(attemptId(task))

        assertEquals(1, queue.recoverExpiredAttempts())
        assertEquals("QUEUED", runState(runId))
        val outcome = jdbc.sql("SELECT outcome FROM run_attempts WHERE id = :id")
            .param("id", UUID.fromString(attemptId(task))).query(String::class.java).single()
        assertEquals("LEASE_EXPIRED", outcome)

        skipBackoff()
        assertEquals(2, claim()!!["run"]["attemptNumber"].asInt())
    }

    @Test
    fun `a superseded attempt cannot publish, heartbeat or fail, even after uploading files`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        val stale = claim()!!
        expireLease(attemptId(stale))
        queue.recoverExpiredAttempts()
        skipBackoff()
        val current = claim()!!

        // The stale worker wakes up, uploads its (attempt-scoped) files and tries to publish.
        val staleBody = completionBody(stale, outputBytes = ByteArray(224) { 99 })
        assertEquals(409, complete(stale, staleBody).status)
        assertEquals(409, internal("/attempts/${attemptId(stale)}/heartbeat", mapOf("leaseToken" to stale["leaseToken"].asLong())).status)
        assertEquals(409, fail(stale, "TRANSIENT").status)
        assertEquals("RUNNING", runState(runId)) // the current attempt is unaffected

        assertEquals(204, complete(current).status)
        // The stale attempt still cannot overwrite the accepted result afterwards.
        assertEquals(409, complete(stale, staleBody).status)

        val detail = get("/api/runs/$runId").body!!
        assertEquals(2, detail["run"]["result"]["acceptedAttemptNumber"].asInt())
        val keys = detail["artifacts"].let { a -> (0 until a.size()).map { a[it]["objectKey"].asString() } }
        assertTrue(keys.all { it.startsWith(current["objectPrefix"].asString()) }, "accepted keys were $keys")
    }

    @Test
    fun `a wrong token cannot act on the current attempt`() {
        submitRun(readyDataset())
        val task = claim()!!
        val forged = internal("/attempts/${attemptId(task)}/heartbeat", mapOf("leaseToken" to task["leaseToken"].asLong() + 1000))
        assertEquals(409, forged.status)
        assertEquals(200, internal("/attempts/${attemptId(task)}/heartbeat", mapOf("leaseToken" to task["leaseToken"].asLong())).status)
    }

    @Test
    fun `completion is verified against storage before publication`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        val task = claim()!!
        val body = completionBody(task)

        @Suppress("UNCHECKED_CAST")
        val output = body["output"] as Map<String, Any?>
        val missing = body + ("output" to output + ("objectKey" to task["objectPrefix"].asString() + "missing.npy"))
        assertEquals(422, complete(task, missing).status)

        val wrongChecksum = body + ("output" to output + ("sha256" to "0".repeat(64)))
        assertEquals(422, complete(task, wrongChecksum).status)

        val otherPrefix = body + ("output" to output + ("objectKey" to "runs/elsewhere/output.npy"))
        assertEquals(400, complete(task, otherPrefix).status)

        assertEquals("RUNNING", runState(runId))
        assertEquals(204, complete(task, body).status)
        // A retried completion after a lost response is answered idempotently.
        assertEquals(204, complete(task, body).status)
        assertEquals("SUCCEEDED", runState(runId))
    }

    @Test
    fun `timeline reports peak concurrency from attempt intervals`() {
        val datasetId = readyDataset()
        val sweep = post(
            "/api/sweeps",
            mapOf("datasetId" to datasetId, "parameter" to "gain", "start" to 1, "end" to 4, "step" to 1, "implementationVersion" to "1.0.0"),
            newKey(),
        ).body!!
        val tasks = (1..4).map { claim()!! }
        // Worker-reported compute intervals: three overlap, the fourth starts when one ends.
        val windows = listOf("00:00" to "00:10", "00:01" to "00:05", "00:02" to "00:06", "00:05" to "00:09")
        tasks.zip(windows).forEach { (t, w) ->
            complete(t, completionBody(t) + mapOf("computeStartedAt" to "2026-01-01T00:${w.first}Z", "computeFinishedAt" to "2026-01-01T00:${w.second}Z"))
        }
        val timeline = get("/api/sweeps/${sweep["id"].asString()}/timeline").body!!
        assertEquals(3, timeline["peakConcurrency"].asInt())
        assertEquals(4, timeline["intervals"].size())
    }
}
