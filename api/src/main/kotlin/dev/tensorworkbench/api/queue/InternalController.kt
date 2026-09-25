package dev.tensorworkbench.api.queue

import dev.tensorworkbench.api.artifact.OrphanCleanupService
import dev.tensorworkbench.api.artifact.OrphanReport
import dev.tensorworkbench.api.config.WorkbenchProperties
import dev.tensorworkbench.api.web.ErrorBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

const val WORKER_TOKEN_HEADER = "X-Worker-Token"

/**
 * Worker-only operations. The web proxy does not route /internal, the API port is
 * not published outside the Compose network, and every call needs the dev token.
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal (worker)", description = "Claim, heartbeat, complete and fail. Requires X-Worker-Token.")
class InternalController(
    private val queue: WorkQueueService,
    private val orphans: OrphanCleanupService,
) {

    @PostMapping("/tasks/claim")
    @Operation(summary = "Atomically claim the next eligible task; 204 when there is nothing to do")
    fun claim(@RequestBody request: ClaimRequest): ResponseEntity<TaskAssignment> =
        queue.claim(request)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PostMapping("/datasets/{id}/heartbeat")
    fun heartbeatGeneration(@PathVariable id: UUID, @RequestBody request: LeaseRequest): LeaseResponse =
        queue.heartbeatGeneration(id, request)

    @PostMapping("/datasets/{id}/complete")
    fun completeGeneration(@PathVariable id: UUID, @RequestBody request: CompleteGenerationRequest): ResponseEntity<Void> {
        queue.completeGeneration(id, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/datasets/{id}/fail")
    fun failGeneration(@PathVariable id: UUID, @RequestBody request: FailRequest): FailResponse =
        queue.failGeneration(id, request)

    @PostMapping("/attempts/{id}/heartbeat")
    fun heartbeatAttempt(@PathVariable id: UUID, @RequestBody request: LeaseRequest): LeaseResponse =
        queue.heartbeatAttempt(id, request)

    @PostMapping("/attempts/{id}/complete")
    @Operation(summary = "Publish an attempt's verified uploads as the run's accepted result")
    fun completeAttempt(@PathVariable id: UUID, @RequestBody request: CompleteRunRequest): ResponseEntity<Void> {
        queue.completeAttempt(id, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/attempts/{id}/fail")
    fun failAttempt(@PathVariable id: UUID, @RequestBody request: FailRequest): FailResponse =
        queue.failAttempt(id, request)

    @PostMapping("/maintenance/orphans")
    @Operation(summary = "List (dryRun=true) or delete unreferenced objects older than minAgeMinutes")
    fun cleanupOrphans(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @RequestParam(defaultValue = "30") minAgeMinutes: Long,
    ): OrphanReport = orphans.cleanup(Duration.ofMinutes(minAgeMinutes), dryRun)
}

class WorkerTokenInterceptor(private val expected: String, private val mapper: ObjectMapper) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val presented = request.getHeader(WORKER_TOKEN_HEADER) ?: ""
        if (MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())) return true
        response.status = 401
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(response.outputStream, ErrorBody(401, "UNAUTHORIZED", "Missing or invalid $WORKER_TOKEN_HEADER"))
        return false
    }
}

@Configuration
class InternalSecurityConfig(private val props: WorkbenchProperties, private val mapper: ObjectMapper) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(WorkerTokenInterceptor(props.worker.token, mapper)).addPathPatterns("/internal/**")
    }
}
