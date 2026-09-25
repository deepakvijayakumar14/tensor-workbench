package dev.tensorworkbench.api.idempotency

import dev.tensorworkbench.api.web.conflict
import dev.tensorworkbench.api.web.validate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

/**
 * Normalized request fingerprint: sorted keys, decimals without trailing zeros.
 * "gain": 2.50 and "gain": 2.5 therefore produce the same hash.
 */
object RequestHash {
    fun of(fields: Map<String, Any?>): String {
        val canonical = fields.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=${normalize(v)}" }
        return sha256Hex(canonical.toByteArray())
    }

    private fun normalize(value: Any?): String = when (value) {
        null -> "null"
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is List<*> -> value.joinToString(",", "[", "]") { normalize(it) }
        else -> value.toString()
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

sealed interface Reservation {
    val resourceId: UUID

    /** The key is new; the caller must create the resource with this id in the same transaction. */
    data class New(override val resourceId: UUID) : Reservation

    /** The same key and payload were seen before; return the original resource. */
    data class Replay(override val resourceId: UUID) : Reservation
}

@Component
class IdempotencyStore(private val jdbc: JdbcClient) {

    /**
     * Must run inside the transaction that creates the resource. The primary key on
     * (operation, key) makes concurrent duplicates wait for, then observe, the first insert.
     */
    fun reserve(operation: String, key: String, requestHash: String, resourceType: String): Reservation {
        validateKey(key)
        val candidateId = UUID.randomUUID()
        val inserted = jdbc.sql(
            """
            INSERT INTO idempotency_keys (operation, key, request_hash, resource_type, resource_id)
            VALUES (:operation, :key, :hash, :type, :id)
            ON CONFLICT (operation, key) DO NOTHING
            """,
        )
            .param("operation", operation)
            .param("key", key)
            .param("hash", requestHash)
            .param("type", resourceType)
            .param("id", candidateId)
            .update()
        if (inserted == 1) return Reservation.New(candidateId)

        val existing = jdbc.sql("SELECT request_hash, resource_id FROM idempotency_keys WHERE operation = :operation AND key = :key")
            .param("operation", operation)
            .param("key", key)
            .query { rs, _ -> rs.getString("request_hash") to rs.getObject("resource_id", UUID::class.java) }
            .single()
        if (existing.first != requestHash) {
            throw conflict(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key '$key' was already used for a different $operation request. Generate a new key for a new submission.",
            )
        }
        return Reservation.Replay(existing.second)
    }

    private fun validateKey(key: String) = validate {
        require(key.length in 8..200, "Idempotency-Key") { "must be between 8 and 200 characters" }
        require(key.all { it.isLetterOrDigit() || it in "-_.:" }, "Idempotency-Key") {
            "may contain only letters, digits and - _ . :"
        }
    }
}
