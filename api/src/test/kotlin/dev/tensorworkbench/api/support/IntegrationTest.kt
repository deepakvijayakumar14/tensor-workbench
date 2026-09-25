package dev.tensorworkbench.api.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.security.MessageDigest
import java.util.UUID

/**
 * Integration tests run against real PostgreSQL and real SeaweedFS, because the
 * behaviour under test (SKIP LOCKED claims, conditional updates, HEAD checks,
 * range reads, presigned URLs) depends on their semantics.
 *
 * Lease recovery is disabled as a background job and invoked explicitly, and
 * "time passing" is simulated by moving lease/eligibility timestamps in SQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "workbench.queue.recovery-enabled=false",
        "workbench.queue.max-attempts=3",
        "workbench.worker.token=test-token",
        "workbench.limits.max-sweep-variants=50",
    ],
)
abstract class IntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var s3: S3Client

    lateinit var http: RestClient

    @BeforeEach
    fun setUpClientAndCleanDatabase() {
        http = RestClient.builder()
            .baseUrl("http://localhost:$port")
            // Never follow redirects: download tests inspect the Location header.
            .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()))
            .build()
        jdbc.sql("TRUNCATE artifacts, run_attempts, runs, sweeps, datasets, idempotency_keys CASCADE").update()
    }

    data class Response(val status: Int, val body: JsonNode?, val headers: Map<String, List<String>>) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    fun call(method: HttpMethod, path: String, body: Any? = null, headers: Map<String, String> = emptyMap()): Response {
        val spec = http.method(method).uri(path)
        headers.forEach { (k, v) -> spec.header(k, v) }
        if (body != null) {
            spec.header("Content-Type", "application/json")
            spec.body(mapper.writeValueAsString(body))
        }
        return spec.exchange { _, response ->
            val text = response.body.readAllBytes().toString(Charsets.UTF_8)
            Response(
                response.statusCode.value(),
                text.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) },
                response.headers.headerSet().associate { it.key to it.value },
            )
        }
    }

    fun get(path: String) = call(HttpMethod.GET, path)

    fun post(path: String, body: Any? = emptyMap<String, Any>(), key: String? = null) =
        call(HttpMethod.POST, path, body, if (key != null) mapOf("Idempotency-Key" to key) else emptyMap())

    fun internal(path: String, body: Any) = call(HttpMethod.POST, "/internal$path", body, mapOf("X-Worker-Token" to "test-token"))

    fun newKey() = "test-${UUID.randomUUID()}"

    // ---- A scripted "worker" that speaks the internal protocol ----

    fun claim(workerId: String = "test-worker/slot-0"): JsonNode? {
        val response = internal("/tasks/claim", mapOf("workerId" to workerId, "workerInstance" to "test-worker", "slotCount" to 3))
        return if (response.status == 204) null else response.body
    }

    /** Uploads bytes the way the worker does: attempt-scoped key, SHA-256 in object metadata. */
    fun upload(key: String, bytes: ByteArray, sha256: String = sha256(bytes)) {
        s3.putObject({ it.bucket(BUCKET).key(key).metadata(mapOf("sha256" to sha256)) }, RequestBody.fromBytes(bytes))
    }

    /** Creates a dataset and plays the worker's generation step so the dataset is READY. */
    fun readyDataset(shape: List<Int> = listOf(4, 3, 2)): UUID {
        val created = post("/api/datasets", mapOf("shape" to shape, "seed" to 1), newKey())
        check(created.status == 202) { "dataset creation failed: $created" }
        val task = claim()!!
        check(task["type"].asString() == "GENERATE_DATASET")
        val bytes = ByteArray(shape.fold(1) { a, b -> a * b } * 4 + 128) { it.toByte() }
        val key = task["objectPrefix"].asString() + "input.npy"
        upload(key, bytes)
        val done = internal(
            "/datasets/${task["generation"]["datasetId"].asString()}/complete",
            mapOf(
                "leaseToken" to task["leaseToken"].asLong(),
                "artifact" to mapOf(
                    "kind" to "INPUT_TENSOR", "objectKey" to key, "sizeBytes" to bytes.size, "sha256" to sha256(bytes),
                    "dtype" to "int32", "shape" to shape,
                ),
            ),
        )
        check(done.status == 204) { "generation completion failed: $done" }
        return UUID.fromString(created.body!!["id"].asString())
    }

    fun submitRun(datasetId: UUID, gain: Number = 2, fault: String? = null, key: String = newKey()): Response =
        post(
            "/api/runs",
            mapOf("datasetId" to datasetId, "gain" to gain, "bias" to 0, "implementationVersion" to "1.0.0", "faultInjection" to fault),
            key,
        )

    /** Builds a valid preview stack for [shape] (max dimension 128, no downsampling for small shapes). */
    fun previewFor(shape: List<Int>): Pair<ByteArray, Map<String, Any>> {
        val (x, y, z) = shape
        val axes = listOf(
            mapOf("axis" to 0, "sliceCount" to x, "height" to y, "width" to z, "sourceHeight" to y, "sourceWidth" to z),
            mapOf("axis" to 1, "sliceCount" to y, "height" to x, "width" to z, "sourceHeight" to x, "sourceWidth" to z),
            mapOf("axis" to 2, "sliceCount" to z, "height" to x, "width" to y, "sourceHeight" to x, "sourceWidth" to y),
        )
        var offset = 0
        val withOffsets = axes.map { a ->
            val entry = a + ("offset" to offset)
            offset += (a["sliceCount"] as Int) * (a["height"] as Int) * (a["width"] as Int)
            entry
        }
        val bytes = ByteArray(offset) { (it % 251).toByte() }
        return bytes to mapOf("encoding" to "uint8", "maxDimension" to 128, "valueMin" to 0.0, "valueMax" to 10.0, "axes" to withOffsets)
    }

    /** Uploads output + preview under the attempt's prefix and returns the completion body. */
    fun completionBody(task: JsonNode, outputBytes: ByteArray = ByteArray(24 * 4 + 128) { 7 }): Map<String, Any?> {
        val prefix = task["objectPrefix"].asString()
        val shape = task["run"]["config"]["output"]["shape"].let { s -> (0 until s.size()).map { s[it].asInt() } }
        val (previewBytes, previewMeta) = previewFor(shape)
        upload(prefix + "output.npy", outputBytes)
        upload(prefix + "preview.u8", previewBytes)
        return mapOf(
            "leaseToken" to task["leaseToken"].asLong(),
            "computeStartedAt" to "2026-01-01T00:00:00Z",
            "computeFinishedAt" to "2026-01-01T00:00:01Z",
            "summary" to mapOf("min" to 0.0, "max" to 10.0, "mean" to 5.0, "std" to 1.0, "elementCount" to 24),
            "output" to mapOf(
                "kind" to "OUTPUT_TENSOR", "objectKey" to prefix + "output.npy", "sizeBytes" to outputBytes.size,
                "sha256" to sha256(outputBytes), "dtype" to "float32", "shape" to shape,
            ),
            "preview" to mapOf(
                "kind" to "PREVIEW_STACK", "objectKey" to prefix + "preview.u8", "sizeBytes" to previewBytes.size,
                "sha256" to sha256(previewBytes), "dtype" to "uint8", "shape" to listOf(previewBytes.size), "metadata" to previewMeta,
            ),
        )
    }

    fun attemptId(task: JsonNode): String = task["run"]["attemptId"].asString()

    fun runState(runId: String): String =
        jdbc.sql("SELECT state FROM runs WHERE id = :id").param("id", UUID.fromString(runId)).query(String::class.java).single()

    /** Simulates the passage of time: the attempt's lease is now in the past. */
    fun expireLease(attemptId: String) {
        jdbc.sql("UPDATE run_attempts SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
            .param("id", UUID.fromString(attemptId)).update()
    }

    /** Simulates backoff elapsing: every queued run is eligible now. */
    fun skipBackoff() {
        jdbc.sql("UPDATE runs SET next_attempt_at = now() WHERE state = 'QUEUED'").update()
    }

    companion object {
        const val BUCKET = "tensor-workbench"
        const val ACCESS_KEY = "test-access"
        const val SECRET_KEY = "test-secret"

        private val postgres = PostgreSQLContainer("postgres:18.6-alpine").apply { start() }

        private val seaweed = GenericContainer("chrislusf/seaweedfs:4.47")
            .withCommand("mini", "-dir=/data", "-webdav=false", "-admin.ui=false", "-s3.port.iceberg=0", "-s3.port.lance=0")
            .withEnv(mapOf("AWS_ACCESS_KEY_ID" to ACCESS_KEY, "AWS_SECRET_ACCESS_KEY" to SECRET_KEY, "S3_BUCKET" to BUCKET))
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333))
            .apply { start() }

        val storageEndpoint: String get() = "http://${seaweed.host}:${seaweed.getMappedPort(8333)}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("workbench.storage.endpoint") { storageEndpoint }
            registry.add("workbench.storage.public-endpoint") { storageEndpoint }
            registry.add("workbench.storage.bucket") { BUCKET }
            registry.add("workbench.storage.access-key") { ACCESS_KEY }
            registry.add("workbench.storage.secret-key") { SECRET_KEY }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
