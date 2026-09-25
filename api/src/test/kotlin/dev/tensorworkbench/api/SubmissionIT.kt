package dev.tensorworkbench.api

import dev.tensorworkbench.api.support.IntegrationTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Request validation and request idempotency (acceptance check 3). */
class SubmissionIT : IntegrationTest() {

    private fun countRuns() = jdbc.sql("SELECT count(*) FROM runs").query(Long::class.java).single()

    private fun sweepBody(datasetId: UUID, end: Any = 20) = mapOf(
        "datasetId" to datasetId, "parameter" to "gain", "start" to 1, "end" to end, "step" to 1,
        "bias" to 0, "implementationVersion" to "1.0.0", "demoTransientFailure" to false,
    )

    @Test
    fun `same key and payload returns the original sweep without adding runs`() {
        val datasetId = readyDataset()
        val key = newKey()

        val first = post("/api/sweeps", sweepBody(datasetId), key)
        val second = post("/api/sweeps", sweepBody(datasetId), key)

        assertEquals(202, first.status)
        assertEquals(202, second.status)
        assertEquals(first.body!!["id"], second.body!!["id"])
        assertEquals("false", first.header("Idempotent-Replayed"))
        assertEquals("true", second.header("Idempotent-Replayed"))
        assertEquals(20, countRuns())
    }

    @Test
    fun `same key with a different payload is a conflict and creates nothing`() {
        val datasetId = readyDataset()
        val key = newKey()
        post("/api/sweeps", sweepBody(datasetId), key)

        val changed = post("/api/sweeps", sweepBody(datasetId, end = 10), key)

        assertEquals(409, changed.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.body!!["error"].asString())
        assertEquals(20, countRuns())
    }

    @Test
    fun `equivalent decimals normalize to the same request`() {
        val datasetId = readyDataset()
        val key = newKey()
        val a = submitRun(datasetId, gain = 2.5, key = key)
        val b = post(
            "/api/runs",
            mapOf("datasetId" to datasetId, "gain" to 2.50, "bias" to 0.0, "implementationVersion" to "1.0.0"),
            key,
        )
        assertEquals(a.body!!["id"], b.body!!["id"])
    }

    @Test
    fun `concurrent duplicate submissions create exactly one run`() {
        val datasetId = readyDataset()
        val key = newKey()
        val pool = Executors.newFixedThreadPool(8)
        val ids = pool.invokeAll((1..8).map { Callable { submitRun(datasetId, key = key).body!!["id"].asString() } }).map { it.get() }
        pool.shutdown()

        assertEquals(1, ids.toSet().size)
        assertEquals(1, countRuns())
    }

    @Test
    fun `invalid sweeps are rejected with field errors`() {
        val datasetId = readyDataset()
        val bad = post(
            "/api/sweeps",
            mapOf("datasetId" to datasetId, "parameter" to "gain", "start" to 5, "end" to 1, "step" to 0, "implementationVersion" to "1.0.0"),
            newKey(),
        )
        assertEquals(400, bad.status)
        val fields = bad.body!!["fieldErrors"].let { f -> (0 until f.size()).map { f[it]["field"].asString() } }
        assertTrue("step" in fields && "end" in fields, "fields were $fields")

        val tooMany = post("/api/sweeps", sweepBody(datasetId, end = 1000), newKey())
        assertEquals(400, tooMany.status)
        assertTrue(tooMany.body!!["fieldErrors"][0]["message"].asString().contains("1000 variants"))
    }

    @Test
    fun `decimal sweep ranges do not drift`() {
        val datasetId = readyDataset()
        val sweep = post(
            "/api/sweeps",
            mapOf("datasetId" to datasetId, "parameter" to "gain", "start" to 0.1, "end" to 0.3, "step" to 0.1, "implementationVersion" to "1.0.0"),
            newKey(),
        )
        assertEquals(3, sweep.body!!["variantCount"].asInt())
        val gains = jdbc.sql("SELECT gain::text FROM runs ORDER BY sweep_ordinal").query(String::class.java).list()
        assertEquals(listOf("0.1", "0.2", "0.3"), gains)
    }

    @Test
    fun `unsupported implementation versions are rejected`() {
        val datasetId = readyDataset()
        val response = post(
            "/api/runs", mapOf("datasetId" to datasetId, "gain" to 1, "implementationVersion" to "2.0.0"), newKey(),
        )
        assertEquals(400, response.status)
        assertEquals("implementationVersion", response.body!!["fieldErrors"][0]["field"].asString())
    }

    @Test
    fun `runs cannot be submitted before the dataset is ready, and the key stays usable`() {
        val created = post("/api/datasets", mapOf("shape" to listOf(2, 2, 2), "seed" to 3), newKey())
        val datasetId = UUID.fromString(created.body!!["id"].asString())
        val key = newKey()

        val early = submitRun(datasetId, key = key)
        assertEquals(409, early.status)
        assertEquals("DATASET_NOT_READY", early.body!!["error"].asString())
        assertEquals(0, countRuns())
    }

    @Test
    fun `missing idempotency key and oversize datasets are validation errors`() {
        val noKey = post("/api/datasets", mapOf("shape" to listOf(2, 2, 2), "seed" to 1))
        assertEquals(400, noKey.status)
        assertEquals("Idempotency-Key", noKey.body!!["fieldErrors"][0]["field"].asString())

        val huge = post("/api/datasets", mapOf("shape" to listOf(512, 512, 512), "seed" to 1), newKey())
        assertEquals(400, huge.status)
        assertEquals("shape", huge.body!!["fieldErrors"][0]["field"].asString())
    }

    @Test
    fun `internal endpoints require the worker token`() {
        val response = post("/internal/tasks/claim", mapOf("workerId" to "intruder"))
        assertEquals(401, response.status)
    }
}
