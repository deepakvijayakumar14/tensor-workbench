package dev.tensorworkbench.api.artifact

import dev.tensorworkbench.api.storage.ArtifactStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

data class OrphanReport(
    val scannedObjects: Int,
    val referencedObjects: Int,
    val orphanedKeys: List<String>,
    val deleted: Int,
    val dryRun: Boolean,
)

/**
 * PostgreSQL and object storage are not updated atomically. An attempt that uploads
 * files and then loses its lease (or crashes before publishing) leaves objects that
 * no artifacts row references. Those orphans are harmless because only referenced
 * keys are ever served, but they use space. This removes unreferenced objects that
 * are older than a grace period, so in-flight uploads are left alone.
 */
@Service
class OrphanCleanupService(
    private val storage: ArtifactStorage,
    private val artifacts: ArtifactRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanup(minAge: Duration, dryRun: Boolean): OrphanReport {
        val cutoff = Instant.now().minus(minAge)
        // Read the referenced set before listing, so an object published during the
        // scan is at worst considered young (and skipped), never deleted.
        val referenced = artifacts.allObjectKeys()
        val objects = PREFIXES.flatMap { storage.list(it).toList() }
        val orphans = objects.filter { it.key !in referenced && it.lastModified.isBefore(cutoff) }.map { it.key }
        if (!dryRun) orphans.forEach(storage::delete)
        log.info("Orphan cleanup: scanned={}, orphans={}, dryRun={}", objects.size, orphans.size, dryRun)
        return OrphanReport(objects.size, referenced.size, orphans, if (dryRun) 0 else orphans.size, dryRun)
    }

    companion object {
        val PREFIXES = listOf("datasets/", "runs/")
    }
}
