package dev.tensorworkbench.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.net.URI
import java.time.Duration

@ConfigurationProperties("workbench")
data class WorkbenchProperties(
    val limits: Limits = Limits(),
    val queue: Queue = Queue(),
    val storage: Storage,
    val worker: Worker,
    val computation: Computation = Computation(),
    val demo: Demo = Demo(),
) {
    /** Conservative defaults so normal use fits on a development laptop. */
    data class Limits(
        val maxDimension: Int = 512,
        val maxElements: Long = 16_777_216, // 256^3: 64 MiB int32 input, 64 MiB float32 output
        val maxSweepVariants: Int = 100,
        val maxAbsParameter: BigDecimal = BigDecimal("1000000"),
        val previewMaxDimension: Int = 128,
    )

    data class Queue(
        val leaseDuration: Duration = Duration.ofSeconds(15),
        val heartbeatInterval: Duration = Duration.ofSeconds(5),
        val maxAttempts: Int = 3,
        val backoffBase: Duration = Duration.ofSeconds(2),
        val backoffMax: Duration = Duration.ofSeconds(60),
        val recoveryInterval: Duration = Duration.ofSeconds(2),
    )

    data class Storage(
        val bucket: String,
        /** Endpoint used by the API inside the deployment network, e.g. http://seaweedfs:8333. */
        val endpoint: URI,
        /** Endpoint reachable by the browser; presigned download URLs are signed for this host. */
        val publicEndpoint: URI,
        val region: String = "us-east-1",
        val accessKey: String,
        val secretKey: String,
        val createBucket: Boolean = true,
        val downloadUrlTtl: Duration = Duration.ofMinutes(5),
    )

    data class Worker(
        /** Shared development token for /internal endpoints. */
        val token: String,
    )

    data class Computation(
        val functionName: String = "affine-material-map",
        /** The deployed worker implements exactly these versions; anything else is rejected. */
        val supportedVersions: List<String> = listOf("1.0.0"),
        val generatorVersion: String = "layered-inclusions-1",
    )

    data class Demo(
        /** Allows explicitly requested, deterministic fault injection (demo and tests only). */
        val allowFaultInjection: Boolean = true,
    )
}
