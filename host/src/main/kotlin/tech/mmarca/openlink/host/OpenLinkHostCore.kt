package tech.mmarca.openlink.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkSigners
import tech.mmarca.openlink.OpenLinkTrust

/** Who is calling, as the binder and the package manager say, never as the caller says. */
data class OpenLinkCaller(
    val uid: Int,
    val packageName: String,
    val signerSha256: String,
    /**
     * The certificates the caller's key was rotated from, oldest first, without the current
     * one. The platform proves this lineage, so trust given to a past signer carries over.
     */
    val pastSignersSha256: Set<String> = emptySet(),
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
 * - a first-party client (a known package name under one of its known signers) is TRUSTED
 *   for what it asked for, without a prompt; the same signer under another name is not;
 * - what the user took back from a client stays taken back until the user gives it again;
 * - a known package with another signer drops back to PENDING, its grants cleared, unless
 *   the platform shows the new key was rotated from the trusted one;
 * - at most [MAX_PENDING] pending records are kept, and one not seen for [PENDING_MAX_AGE_MILLIS]
 *   is dropped, so an app cannot fill the host's screens;
 * - BLOCKED stays blocked until the user forgets the app, whatever key the package comes back under;
 * - a capability the caller never asked for is never granted.
 *
 * Changes are published on [clients], which the host app's UI (the pending card, the
 * connected-apps screen) observes, and passed to [onTrustChanged], which the binder layer
 * uses to tell the client and to end what a revoked capability covered.
 */
class OpenLinkHostCore(
    private val store: OpenLinkTrustStore,
    /**
     * The first-party clients: package name to the hex SHA-256 of the certificates that
     * package may be signed with. Any other app, and a known signer under another package
     * name, asks the user.
     */
    knownClients: Map<String, Set<String>>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val knownClients: Map<String, Set<String>> =
        knownClients.mapValues { (_, signers) -> signers.map(::normalizeSha256).toSet() }

    private val _clients = MutableStateFlow(store.all())
    val clients: StateFlow<List<OpenLinkClientRecord>> = _clients.asStateFlow()

    /**
     * Set by the binder layer: (package, before, after). Called after this object's lock is
     * released, so the binder layer may take its own locks and call back in here.
     */
    @Volatile
    var onTrustChanged: (String, OpenLinkClientRecord?, OpenLinkClientRecord?) -> Unit = { _, _, _ -> }

    /**
     * Set by the binder layer: whether [packageName] is bound with a listener now. Such a
     * client does not say hello again until it rebinds, so its record is never dropped under it.
     */
    @Volatile
    var isConnected: (packageName: String) -> Boolean = { false }

    /** The trust changes made under the lock, told to [onTrustChanged] once it is released. */
    private val notices = ArrayList<() -> Unit>()

    /**
     * Runs [block] under the lock and tells [onTrustChanged] afterwards. The binder layer
     * holds its listener lock while it asks [allows]; telling it from inside this lock would
     * take the two in the opposite order.
     */
    private inline fun <T> changing(block: () -> T): T {
        val (result, fire) = synchronized(this) {
            val result = block()
            val fire = notices.toList()
            notices.clear()
            result to fire
        }
        fire.forEach { it() }
        return result
    }

    fun hello(caller: OpenLinkCaller, hello: OpenLinkHello): OpenLinkDecision = changing {
        val requested = hello.requestedCapabilities intersect OpenLink.ALL_CAPABILITIES
        val version = minOf(hello.protocolVersion.coerceAtLeast(1), OpenLink.PROTOCOL_VERSION)
        val appVersion = hello.appVersion.take(MAX_APP_VERSION_LENGTH)
        val signer = normalizeSha256(caller.signerSha256)
        val existing = store.get(caller.packageName)?.let { followRotation(it, caller) }
        val now = clock()
        val known = isKnownSigner(caller)
        // The record this hello continues: the same key, or another of a first-party package's
        // own keys (the same app from another channel). Anything else under the name is another app.
        val same = existing?.takeIf { it.signerSha256 == signer || (known && it.signerSha256 in knownClients[caller.packageName].orEmpty()) }
        val next = when {
            // Blocked is the user's word on the package, whatever key it comes back under.
            existing?.trust == OpenLinkTrust.BLOCKED -> existing.copy(signerSha256 = signer, requested = requested, appVersion = appVersion)
            same == null -> {
                if (known) {
                    OpenLinkClientRecord(caller.packageName, signer, OpenLinkTrust.TRUSTED, requested, requested, appVersion, now)
                } else {
                    // New, or the same name under another key: that is another app until the user says otherwise.
                    OpenLinkClientRecord(caller.packageName, signer, OpenLinkTrust.PENDING, emptySet(), requested, appVersion, now)
                }
            }
            same.trust == OpenLinkTrust.PENDING && known ->
                // Asked before its signer was known (a host update added it): trusted now, as a first hello would be.
                same.copy(signerSha256 = signer, trust = OpenLinkTrust.TRUSTED, capabilities = requested, requested = requested, appVersion = appVersion, updatedAtMillis = now)
            same.trust == OpenLinkTrust.TRUSTED && known ->
                // A first-party client gains what it newly asks for, never what the user took back; what
                // it stops asking for it keeps until revoked, so an older build does not shrink a newer one's grant.
                same.copy(signerSha256 = signer, capabilities = same.capabilities + (requested - same.revoked), requested = requested, appVersion = appVersion)
            else -> same.copy(requested = requested, appVersion = appVersion)
        }
        save(existing, next.copy(lastSeenAtMillis = now, knownSigner = known))
        prunePending(keep = caller.packageName, now = now)
        decisionFor(next, version)
    }

    /** The decision for a call after hello. Null record: the caller never said hello, or was forgotten. */
    @Synchronized
    fun decision(caller: OpenLinkCaller): OpenLinkDecision {
        val record = store.get(caller.packageName)
        if (record == null || !signedBy(record, caller)) {
            return OpenLinkDecision(OpenLink.PROTOCOL_VERSION, OpenLinkTrust.PENDING, emptySet())
        }
        return decisionFor(record, OpenLink.PROTOCOL_VERSION)
    }

    /**
     * Whether [caller] may register a listener: it said hello under this key and is not
     * blocked. A pending client may, so it hears the user's answer.
     */
    @Synchronized
    fun mayListen(caller: OpenLinkCaller): Boolean {
        val record = store.get(caller.packageName) ?: return false
        return signedBy(record, caller) && record.trust != OpenLinkTrust.BLOCKED
    }

    fun allows(caller: OpenLinkCaller, capability: String): Boolean {
        val decision = decision(caller)
        return decision.trust == OpenLinkTrust.TRUSTED && capability in decision.capabilities
    }

    /** The user allowed [packageName]; grants what it asked for, or [capabilities] if given (a subset of it). */
    fun allow(packageName: String, capabilities: Set<String>? = null) = changing {
        val existing = store.get(packageName) ?: return@changing
        val granted = (capabilities ?: existing.requested) intersect existing.requested
        save(existing, existing.copy(trust = OpenLinkTrust.TRUSTED, capabilities = granted, revoked = existing.requested - granted, updatedAtMillis = clock()))
    }

    fun block(packageName: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        save(existing, existing.copy(trust = OpenLinkTrust.BLOCKED, capabilities = emptySet(), updatedAtMillis = clock()))
    }

    /** Takes one capability back from a trusted client. It stays taken back through every later hello, until [grant]. */
    fun revoke(packageName: String, capability: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        save(existing, existing.copy(capabilities = existing.capabilities - capability, revoked = existing.revoked + capability, updatedAtMillis = clock()))
    }

    /** Gives one capability back to a trusted client, if it asked for it. */
    fun grant(packageName: String, capability: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        if (existing.trust != OpenLinkTrust.TRUSTED || capability !in existing.requested) return@changing
        save(existing, existing.copy(capabilities = existing.capabilities + capability, revoked = existing.revoked - capability, updatedAtMillis = clock()))
    }

    /** The client went away (unbound, stopped, or died): its "last connected" time. */
    fun onDisconnected(packageName: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        save(existing, existing.copy(lastSeenAtMillis = clock()))
    }

    /** The host took a body profile from [packageName]. */
    fun onProfileReceived(packageName: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        save(existing, existing.copy(profileReceivedAtMillis = clock()))
    }

    /**
     * Drops the record: the app is unknown again and its next hello starts over. An app that
     * is bound now says no hello until it rebinds, so its record starts over here instead:
     * as its first hello left it, with nothing the user decided since.
     */
    fun forget(packageName: String) = changing {
        val existing = store.get(packageName) ?: return@changing
        if (isConnected(packageName)) {
            val granted = if (existing.knownSigner) existing.requested else emptySet()
            save(
                existing,
                OpenLinkClientRecord(
                    packageName = packageName,
                    signerSha256 = existing.signerSha256,
                    trust = if (existing.knownSigner) OpenLinkTrust.TRUSTED else OpenLinkTrust.PENDING,
                    capabilities = granted,
                    requested = existing.requested,
                    appVersion = existing.appVersion,
                    updatedAtMillis = clock(),
                    lastSeenAtMillis = existing.lastSeenAtMillis,
                    knownSigner = existing.knownSigner,
                ),
            )
        } else {
            store.remove(packageName)
            _clients.value = store.all()
            notices += { onTrustChanged(packageName, existing, null) }
        }
    }

    /** Whether [caller] carries the key [record] trusts: its current one, or one it was rotated from. */
    private fun signedBy(record: OpenLinkClientRecord, caller: OpenLinkCaller): Boolean =
        record.signerSha256 == normalizeSha256(caller.signerSha256) ||
            record.signerSha256 in caller.pastSignersSha256.map(::normalizeSha256)

    /** Known by name and key together: one of the package's own signers, now or before a rotation. */
    private fun isKnownSigner(caller: OpenLinkCaller): Boolean {
        val signers = knownClients[caller.packageName] ?: return false
        return normalizeSha256(caller.signerSha256) in signers ||
            caller.pastSignersSha256.any { normalizeSha256(it) in signers }
    }

    /** A record for a key the caller was rotated from moves to the current key, trust kept. */
    private fun followRotation(record: OpenLinkClientRecord, caller: OpenLinkCaller): OpenLinkClientRecord {
        val current = normalizeSha256(caller.signerSha256)
        if (record.signerSha256 == current || !signedBy(record, caller)) return record
        val moved = record.copy(signerSha256 = current)
        store.put(moved)
        _clients.value = store.all()
        return moved
    }

    /**
     * Drops pending records not seen for [PENDING_MAX_AGE_MILLIS], then the oldest beyond
     * [MAX_PENDING]. Run on every hello, whoever says it, so a stray record goes even when only
     * trusted clients connect. A client bound now is kept: it waits for the user's answer.
     */
    private fun prunePending(keep: String, now: Long) {
        val pending = store.all().filter { it.trust == OpenLinkTrust.PENDING && it.packageName != keep && !isConnected(it.packageName) }
        val stale = pending.filter { now - it.lastSeenAtMillis > PENDING_MAX_AGE_MILLIS }
        val overflow = (pending - stale.toSet()).sortedByDescending { it.lastSeenAtMillis }.drop(MAX_PENDING - 1)
        val dropped = stale + overflow
        if (dropped.isEmpty()) return
        dropped.forEach { store.remove(it.packageName) }
        _clients.value = store.all()
        dropped.forEach { record -> notices += { onTrustChanged(record.packageName, record, null) } }
    }

    private fun save(before: OpenLinkClientRecord?, after: OpenLinkClientRecord) {
        if (before == after) return
        store.put(after)
        _clients.value = store.all()
        val trustChanged = before?.trust != after.trust || before.capabilities != after.capabilities
        if (trustChanged) notices += { onTrustChanged(after.packageName, before, after) }
    }

    private fun decisionFor(record: OpenLinkClientRecord, version: Int): OpenLinkDecision = OpenLinkDecision(
        protocolVersion = version,
        trust = record.trust,
        capabilities = if (record.trust == OpenLinkTrust.TRUSTED) record.capabilities else emptySet(),
    )

    companion object {
        /** The most pending records kept, the newest one included. */
        const val MAX_PENDING = 10

        /** A pending record not seen for 30 days is dropped. */
        const val PENDING_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** An app's own version label is kept this long at most: any app may say hello. */
        const val MAX_APP_VERSION_LENGTH = 64

        fun normalizeSha256(value: String): String = OpenLinkSigners.normalize(value)
    }
}
