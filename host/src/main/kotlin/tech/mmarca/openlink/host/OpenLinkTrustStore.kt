package tech.mmarca.openlink.host

import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust

/** What the host knows about one client app. */
data class OpenLinkClientRecord(
    val packageName: String,
    /** Hex SHA-256 of the signing certificate the trust was given to. */
    val signerSha256: String,
    val trust: OpenLinkTrust,
    /** Granted; empty unless trusted. */
    val capabilities: Set<String> = emptySet(),
    /** What the client asked for in its last hello, shown on the consent screen. */
    val requested: Set<String> = emptySet(),
    /** The client's own label, if it has told us one (its app version, for the settings row). */
    val appVersion: String = "",
    val updatedAtMillis: Long = 0L,
)

/** Where the records live. The Android side backs it with SharedPreferences; tests with a map. */
interface OpenLinkTrustStorage {
    fun read(): Map<String, String>
    fun write(key: String, value: String)
    fun remove(key: String)
}

/**
 * The client records, one string per package. The encoding is a tab-separated line, so a
 * newer field is appended and an older reader ignores it.
 */
class OpenLinkTrustStore(private val storage: OpenLinkTrustStorage) {

    @Synchronized
    fun all(): List<OpenLinkClientRecord> =
        storage.read().mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX)) decode(key.removePrefix(KEY_PREFIX), value) else null
        }.sortedBy { it.packageName }

    @Synchronized
    fun get(packageName: String): OpenLinkClientRecord? =
        storage.read()[KEY_PREFIX + packageName]?.let { decode(packageName, it) }

    @Synchronized
    fun put(record: OpenLinkClientRecord) {
        storage.write(KEY_PREFIX + record.packageName, encode(record))
    }

    @Synchronized
    fun remove(packageName: String) {
        storage.remove(KEY_PREFIX + packageName)
    }

    private fun encode(record: OpenLinkClientRecord): String = listOf(
        record.signerSha256,
        record.trust.name,
        record.capabilities.sorted().joinToString(","),
        record.requested.sorted().joinToString(","),
        record.updatedAtMillis.toString(),
        record.appVersion.replace('\t', ' '),
    ).joinToString("\t")

    private fun decode(packageName: String, value: String): OpenLinkClientRecord? {
        val parts = value.split('\t')
        if (parts.size < 5) return null
        val trust = OpenLinkTrust.entries.firstOrNull { it.name == parts[1] } ?: return null
        return OpenLinkClientRecord(
            packageName = packageName,
            signerSha256 = parts[0],
            trust = trust,
            capabilities = parts[2].splitSet() intersect OpenLink.ALL_CAPABILITIES,
            requested = parts[3].splitSet() intersect OpenLink.ALL_CAPABILITIES,
            updatedAtMillis = parts[4].toLongOrNull() ?: 0L,
            appVersion = parts.getOrNull(5).orEmpty(),
        )
    }

    private fun String.splitSet(): Set<String> = split(',').filter { it.isNotBlank() }.toSet()

    private companion object {
        const val KEY_PREFIX = "client."
    }
}
