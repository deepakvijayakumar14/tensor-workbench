package dev.tensorworkbench.api.db

import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.intList(column: String): List<Int> =
    (getArray(column).array as Array<*>).map { (it as Number).toInt() }

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

/** Postgres array literal for binding an integer[] parameter via CAST(:p AS integer[]). */
fun List<Int>.toPgIntArray(): String = joinToString(",", "{", "}")

/** Runs [block] in a short transaction. Keep network I/O and computation outside. */
fun <T> TransactionTemplate.inTx(block: () -> T): T = execute { block() } as T

/** Elements of a JSON array node as a Kotlin list. */
fun JsonNode.elementList(): List<JsonNode> = (0 until size()).map { get(it) }
