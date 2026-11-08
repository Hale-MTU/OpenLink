package tech.mmarca.openlink.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkTrust

/** Who is calling, as the binder and the package manager say, never as the caller says. */
data class OpenLinkCaller(
    val uid: Int,
    val packageName: String,
    val signerSha256: String,
)

/** The trust decision for one call: the version spoken, the trust, the capabilities. */
data class OpenLinkDecision(
    val protocolVersion: Int,
    val trust: OpenLinkTrust,
    val capabilities: Set<String>,
)

/**
 * The trust rules of docs/spec.md, with no Android in them:
 *
 * - an unknown caller becomes PENDING with what it asked for, and gets nothing;
 * - a caller signed by a known signer is TRUSTED for what it asked for, without a prompt;
 * - a known package with another signer drops back to PENDING, its grants cleared;
 * - BLOCKED stays blocked until the user forgets the app;
 * - a capability the caller never asked for is never granted.
 *
 * Changes are published on [clients], which the host app's UI (the pending card, the
 * connected-apps screen) observes, and passed to [onTrustChanged], which the binder layer
 * uses to tell the client and to end what a revoked capability covered.
 */
class OpenLinkHostCore(
    private val store: OpenLinkTrustStore,
    /** Hex SHA-256 (lowercase, no colons) of first-party signing certificates. */
    knownSigners: Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val knownSigners: Set<String> = knownSigners.map(::normalizeSha256).toSet()

    private val _clients = MutableStateFlow(store.all())
    val clients: StateFlow<List<OpenLinkClientRecord>> = _clients.asStateFlow()

    /** Set by the binder layer: (package, before, after). */
    @Volatile
    var onTrustChanged: (String, OpenLinkClientRecord?, OpenLinkClientRecord?) -> Unit = { _, _, _ -> }

    @Synchronized
    fun hello(caller: OpenLinkCaller, hello: OpenLinkHello): OpenLinkDecision {
        val requested = hello.requestedCapabilities intersect OpenLink.ALL_CAPABILITIES
        val version = minOf(hello.protocolVersion.coerceAtLeast(1), OpenLink.PROTOCOL_VERSION)
        val existing = store.get(caller.packageName)
        val signer = normalizeSha256(caller.signerSha256)
        val now = clock()
        val next = when {
            existing == null || existing.signerSha256 != signer -> {
                if (signer in knownSigners) {
                    OpenLinkClientRecord(caller.packageName, signer, OpenLinkTrust.TRUSTED, requested, requested, hello.appVersion, now)
                } else {
                    // New, or the same name under another key: that is another app until the user says otherwise.
                    OpenLinkClientRecord(caller.packageName, signer, OpenLinkTrust.PENDING, emptySet(), requested, hello.appVersion, now)
                }
            }
            existing.trust == OpenLinkTrust.BLOCKED -> existing.copy(requested = requested, appVersion = hello.appVersion)
            existing.trust == OpenLinkTrust.TRUSTED && signer in knownSigners ->
                // A first-party client gains what it newly asks for; what it stops asking for it keeps
                // until revoked, so an older build of it does not shrink a newer one's grant.
                existing.copy(capabilities = existing.capabilities + requested, requested = requested, appVersion = hello.appVersion)
            else -> existing.copy(requested = requested, appVersion = hello.appVersion)
        }
        save(existing, next)
        return decisionFor(next, version)
    }

    /** The decision for a call after hello. Null record: the caller never said hello, or was forgotten. */
    @Synchronized
    fun decision(caller: OpenLinkCaller): OpenLinkDecision {
        val record = store.get(caller.packageName)
        if (record == null || record.signerSha256 != normalizeSha256(caller.signerSha256)) {
            return OpenLinkDecision(OpenLink.PROTOCOL_VERSION, OpenLinkTrust.PENDING, emptySet())
        }
        return decisionFor(record, OpenLink.PROTOCOL_VERSION)
    }

    fun allows(caller: OpenLinkCaller, capability: String): Boolean {
        val decision = decision(caller)
        return decision.trust == OpenLinkTrust.TRUSTED && capability in decision.capabilities
    }

    /** The user allowed [packageName]; grants what it asked for, or [capabilities] if given (a subset of it). */
    @Synchronized
    fun allow(packageName: String, capabilities: Set<String>? = null) {
        val existing = store.get(packageName) ?: return
        val granted = (capabilities ?: existing.requested) intersect existing.requested
        save(existing, existing.copy(trust = OpenLinkTrust.TRUSTED, capabilities = granted, updatedAtMillis = clock()))
    }

    @Synchronized
    fun block(packageName: String) {
        val existing = store.get(packageName) ?: return
        save(existing, existing.copy(trust = OpenLinkTrust.BLOCKED, capabilities = emptySet(), updatedAtMillis = clock()))
    }

    /** Takes one capability back from a trusted client. */
    @Synchronized
    fun revoke(packageName: String, capability: String) {
        val existing = store.get(packageName) ?: return
        save(existing, existing.copy(capabilities = existing.capabilities - capability, updatedAtMillis = clock()))
    }

    /** Gives one capability back to a trusted client, if it asked for it. */
    @Synchronized
    fun grant(packageName: String, capability: String) {
        val existing = store.get(packageName) ?: return
        if (existing.trust != OpenLinkTrust.TRUSTED || capability !in existing.requested) return
        save(existing, existing.copy(capabilities = existing.capabilities + capability, updatedAtMillis = clock()))
    }

    /** Drops the record: the app is unknown again and its next hello starts over. */
    @Synchronized
    fun forget(packageName: String) {
        val existing = store.get(packageName) ?: return
        store.remove(packageName)
        _clients.value = store.all()
        onTrustChanged(packageName, existing, null)
    }

    private fun save(before: OpenLinkClientRecord?, after: OpenLinkClientRecord) {
        if (before == after) return
        store.put(after)
        _clients.value = store.all()
        val trustChanged = before?.trust != after.trust || before.capabilities != after.capabilities
        if (trustChanged) onTrustChanged(after.packageName, before, after)
    }

    private fun decisionFor(record: OpenLinkClientRecord, version: Int): OpenLinkDecision = OpenLinkDecision(
        protocolVersion = version,
        trust = record.trust,
        capabilities = if (record.trust == OpenLinkTrust.TRUSTED) record.capabilities else emptySet(),
    )

    companion object {
        fun normalizeSha256(value: String): String = value.replace(":", "").trim().lowercase()
    }
}
