package dev.tensorworkbench.api.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class FieldError(val field: String, val message: String)

/** One error shape for every non-2xx response. */
data class ErrorBody(
    val status: Int,
    val error: String,
    val message: String,
    val fieldErrors: List<FieldError> = emptyList(),
)

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val fieldErrors: List<FieldError> = emptyList(),
) : RuntimeException(message)

fun notFound(what: String, id: Any) = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what $id was not found")

fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)

fun badRequest(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)

/** Collects field-level problems so a client sees all of them at once. */
class FieldErrors {
    private val errors = mutableListOf<FieldError>()

    fun reject(field: String, message: String) {
        errors += FieldError(field, message)
    }

    fun require(condition: Boolean, field: String, message: () -> String) {
        if (!condition) reject(field, message())
    }

    fun <T : Any> required(value: T?, field: String): T? {
        if (value == null) reject(field, "is required")
        return value
    }

    fun hasErrors() = errors.isNotEmpty()

    fun throwIfAny() {
        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", errors.toList())
        }
    }
}

inline fun validate(block: FieldErrors.() -> Unit) {
    FieldErrors().apply(block).throwIfAny()
}

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(e.status).body(ErrorBody(e.status.value(), e.code, e.message, e.fieldErrors))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorBody> =
        ResponseEntity.badRequest().body(
            ErrorBody(400, "MALFORMED_REQUEST", "Request body is not valid JSON for this operation"),
        )

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ErrorBody> =
        ResponseEntity.badRequest().body(
            ErrorBody(
                400, "VALIDATION_FAILED", "Request validation failed",
                listOf(FieldError(e.headerName, "header is required")),
            ),
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ErrorBody> =
        ResponseEntity.badRequest().body(
            ErrorBody(
                400, "VALIDATION_FAILED", "Request validation failed",
                listOf(FieldError(e.parameterName, "query parameter is required")),
            ),
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorBody> =
        ResponseEntity.badRequest().body(
            ErrorBody(
                400, "VALIDATION_FAILED", "Request validation failed",
                listOf(FieldError(e.name, "has an invalid value")),
            ),
        )
}
