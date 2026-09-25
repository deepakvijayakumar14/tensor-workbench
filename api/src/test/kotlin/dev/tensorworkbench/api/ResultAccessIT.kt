package dev.tensorworkbench.api

import dev.tensorworkbench.api.support.IntegrationTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bounded previews and presigned downloads against real object storage. */
class ResultAccessIT : IntegrationTest() {

    private fun succeededRun(shape: List<Int>, output: ByteArray): Pair<String, ByteArray> {
        val runId = submitRun(readyDataset(shape)).body!!["id"].asString()
        val task = claim()!!
        val body = completionBody(task, output)
        assertEquals(204, internal("/attempts/${attemptId(task)}/complete", body).status)
        return runId to previewFor(shape).first
    }

    @Test
    fun `preview returns exactly one slice from the stored stack`() {
        val shape = listOf(4, 3, 2)
        val (runId, stack) = succeededRun(shape, ByteArray(24 * 4 + 128) { 1 })

        // Axis 1 slices are 4x2 = 8 bytes; axis 1 starts after the 4 axis-0 slices of 3x2 = 6 bytes.
        val response = get("/api/runs/$runId/preview?axis=1&index=2")
        assertEquals(200, response.status)
        val slice = response.body!!
        assertEquals(2, slice["width"].asInt())
        assertEquals(4, slice["height"].asInt())
        val expected = stack.copyOfRange(4 * 6 + 2 * 8, 4 * 6 + 3 * 8)
        assertContentEquals(expected, Base64.getDecoder().decode(slice["data"].asString()))
        assertTrue(response.header("Cache-Control")!!.contains("immutable"))

        assertEquals(400, get("/api/runs/$runId/preview?axis=1&index=3").status)
        assertEquals(400, get("/api/runs/$runId/preview?axis=5&index=0").status)
    }

    @Test
    fun `results are unavailable until a run succeeds`() {
        val runId = submitRun(readyDataset()).body!!["id"].asString()
        assertEquals(409, get("/api/runs/$runId/preview?axis=0&index=0").status)
        assertEquals(409, get("/api/runs/$runId/download").status)
    }

    @Test
    fun `download redirects to a presigned URL on the public endpoint that serves the full object`() {
        val output = ByteArray(24 * 4 + 128) { (it * 7).toByte() }
        val (runId, _) = succeededRun(listOf(4, 3, 2), output)

        val redirect = get("/api/runs/$runId/download")
        assertEquals(302, redirect.status)
        val location = URI(redirect.header("Location")!!)
        assertEquals(URI(storageEndpoint).authority, location.authority) // signed for the browser-reachable host
        assertTrue(location.query.contains("X-Amz-Signature"))

        val fetched = HttpClient.newHttpClient().send(HttpRequest.newBuilder(location).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, fetched.statusCode())
        assertContentEquals(output, fetched.body())
        assertTrue(fetched.headers().firstValue("Content-Disposition").orElse("").contains("attachment"))
    }

    @Test
    fun `run detail exposes the accepted artifacts and preview layout, not tensor data`() {
        val (runId, _) = succeededRun(listOf(4, 3, 2), ByteArray(24 * 4 + 128))
        val detail = get("/api/runs/$runId").body!!
        assertEquals(3, detail["preview"]["axes"].size())
        assertEquals(2, detail["artifacts"].size())
        assertEquals("1.0.0", detail["configSnapshot"]["implementationVersion"].asString())
        assertTrue(detail.toString().length < 5_000, "detail payload should be metadata only")
    }
}
