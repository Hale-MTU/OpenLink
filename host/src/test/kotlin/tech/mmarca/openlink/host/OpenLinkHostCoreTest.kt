package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkTrust

class OpenLinkHostCoreTest {

    private class MapStorage : OpenLinkTrustStorage {
        val map = mutableMapOf<String, String>()
        override fun read(): Map<String, String> = map.toMap()
        override fun write(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val storage = MapStorage()
    private val firstParty = "AA:BB:cc:dd"
    private var now = 1_000L
    private fun core(known: Map<String, Set<String>> = mapOf("tech.mmarca.openvitals" to setOf(firstParty))) =
        OpenLinkHostCore(OpenLinkTrustStore(storage), knownClients = known, clock = { now })

    private val vitals = OpenLinkCaller(10_001, "tech.mmarca.openvitals", "aabbccdd")
    private val stranger = OpenLinkCaller(10_002, "com.example.other", "1234")
    private val all = OpenLinkHello(requestedCapabilities = OpenLink.ALL_CAPABILITIES)

    @Test
    fun `a first-party signer is trusted for what it asked, without a prompt`() {
        val decision = core().hello(vitals, OpenLinkHello(requestedCapabilities = setOf("profile", "devices")))

        assertThat(decision.trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(decision.capabilities).containsExactly("profile", "devices")
    }

    @Test
    fun `an unknown app is pending and gets nothing until the user allows it`() {
        val core = core()

        val decision = core.hello(stranger, all)

        assertThat(decision.trust).isEqualTo(OpenLinkTrust.PENDING)
        assertThat(decision.capabilities).isEmpty()
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_LIVE)).isFalse()
        assertThat(core.clients.value.single().requested).isEqualTo(OpenLink.ALL_CAPABILITIES)

        core.allow(stranger.packageName, setOf(OpenLink.CAPABILITY_DEVICES))

        assertThat(core.allows(stranger, OpenLink.CAPABILITY_DEVICES)).isTrue()
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_LIVE)).isFalse()
    }

    @Test
    fun `a capability the app never asked for is never granted`() {
        val core = core()
        core.hello(stranger, OpenLinkHello(requestedCapabilities = setOf("devices", "made-up")))

        core.allow(stranger.packageName, OpenLink.ALL_CAPABILITIES)

        assertThat(core.decision(stranger).capabilities).containsExactly("devices")
    }

    @Test
    fun `the same package under another key is another app and starts over`() {
        val core = core()
        core.hello(stranger, all)
        core.allow(stranger.packageName)
        val impostor = stranger.copy(signerSha256 = "9999")

        assertThat(core.allows(impostor, OpenLink.CAPABILITY_DEVICES)).isFalse()
        val decision = core.hello(impostor, all)

        assertThat(decision.trust).isEqualTo(OpenLinkTrust.PENDING)
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_DEVICES)).isFalse()
    }

    @Test
    fun `blocked stays blocked through a new hello, until forgotten`() {
        val core = core()
        core.hello(stranger, all)
        core.block(stranger.packageName)

        assertThat(core.hello(stranger, all).trust).isEqualTo(OpenLinkTrust.BLOCKED)

        core.forget(stranger.packageName)
        assertThat(core.hello(stranger, all).trust).isEqualTo(OpenLinkTrust.PENDING)
    }

    @Test
    fun `revoking tells the binder layer, and the grant survives a restart`() {
        val changes = mutableListOf<Pair<Set<String>?, Set<String>?>>()
        val core = core().apply { onTrustChanged = { _, before, after -> changes += before?.capabilities to after?.capabilities } }
        core.hello(vitals, all)
        changes.clear()

        core.revoke(vitals.packageName, OpenLink.CAPABILITY_LIVE)

        assertThat(changes.single().second).doesNotContain(OpenLink.CAPABILITY_LIVE)
        val reloaded = core()
        assertThat(reloaded.allows(vitals, OpenLink.CAPABILITY_LIVE)).isFalse()
        assertThat(reloaded.allows(vitals, OpenLink.CAPABILITY_PROFILE)).isTrue()
    }

    @Test
    fun `a newer protocol speaks this side's version`() {
        assertThat(core().hello(vitals, OpenLinkHello(protocolVersion = 9)).protocolVersion).isEqualTo(OpenLink.PROTOCOL_VERSION)
    }

    @Test
    fun `hello, leaving and a received profile are remembered across a restart, without a trust change`() {
        val changes = mutableListOf<String>()
        val core = core().apply { onTrustChanged = { packageName, _, _ -> changes += packageName } }
        core.hello(vitals, all)
        changes.clear()

        now = 2_000L
        core.onProfileReceived(vitals.packageName)
        now = 3_000L
        core.onDisconnected(vitals.packageName)

        val record = core().clients.value.single()
        assertThat(record.profileReceivedAtMillis).isEqualTo(2_000L)
        assertThat(record.lastSeenAtMillis).isEqualTo(3_000L)
        assertThat(record.trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(changes).isEmpty()
    }

    @Test
    fun `a record written before the activity fields reads with them unset`() {
        storage.map["client.com.example.old"] = "1234\tTRUSTED\tdevices\tdevices\t5\t1.0"

        val record = core().clients.value.single()

        assertThat(record.capabilities).containsExactly("devices")
        assertThat(record.lastSeenAtMillis).isEqualTo(0L)
        assertThat(record.profileReceivedAtMillis).isEqualTo(0L)
    }

    @Test
    fun `a rotated key keeps the trust given to the key it was rotated from`() {
        val core = core()
        core.hello(stranger, all)
        core.allow(stranger.packageName)
        val rotated = stranger.copy(signerSha256 = "5678", pastSignersSha256 = setOf("1234"))

        assertThat(core.allows(rotated, OpenLink.CAPABILITY_DEVICES)).isTrue()
        assertThat(core.hello(rotated, all).trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(core.clients.value.single().signerSha256).isEqualTo("5678")
        // Once moved, the old key alone is another app.
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_DEVICES)).isFalse()
    }

    @Test
    fun `a key rotated from a known signer is a known signer`() {
        val rotated = vitals.copy(signerSha256 = "eeff", pastSignersSha256 = setOf("aabbccdd"))

        assertThat(core().hello(rotated, all).trust).isEqualTo(OpenLinkTrust.TRUSTED)
    }

    @Test
    fun `a past signer the trust was never given to proves nothing`() {
        val core = core()
        core.hello(stranger, all)
        core.allow(stranger.packageName)
        val other = stranger.copy(signerSha256 = "9999", pastSignersSha256 = setOf("4321"))

        assertThat(core.allows(other, OpenLink.CAPABILITY_DEVICES)).isFalse()
        assertThat(core.hello(other, all).trust).isEqualTo(OpenLinkTrust.PENDING)
    }

    @Test
    fun `pending records are capped, the oldest dropped first`() {
        val core = core()
        repeat(OpenLinkHostCore.MAX_PENDING + 3) { i ->
            now = 1_000L + i
            core.hello(OpenLinkCaller(20_000 + i, "com.example.app$i", "ff$i"), all)
        }

        val pending = core.clients.value.map { it.packageName }
        assertThat(pending).hasSize(OpenLinkHostCore.MAX_PENDING)
        assertThat(pending).doesNotContain("com.example.app0")
        assertThat(pending).contains("com.example.app${OpenLinkHostCore.MAX_PENDING + 2}")
    }

    @Test
    fun `a pending record not seen for 30 days is dropped, a trusted one is not`() {
        val core = core()
        core.hello(stranger, all)
        core.hello(vitals, all)

        now += OpenLinkHostCore.PENDING_MAX_AGE_MILLIS + 1
        core.hello(OpenLinkCaller(20_001, "com.example.new", "ff"), all)

        assertThat(core.clients.value.map { it.packageName })
            .containsExactly("com.example.new", vitals.packageName)
    }

    @Test
    fun `an app that waited before its signer was known is trusted on its next hello, a blocked one is not`() {
        core(known = emptyMap()).apply {
            hello(vitals, all)
            hello(stranger, all)
            block(stranger.packageName)
        }
        val updated = core(known = mapOf(vitals.packageName to setOf(firstParty), stranger.packageName to setOf(stranger.signerSha256)))

        assertThat(updated.hello(vitals, all).trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(updated.allows(vitals, OpenLink.CAPABILITY_PROFILE)).isTrue()
        assertThat(updated.hello(stranger, all).trust).isEqualTo(OpenLinkTrust.BLOCKED)
    }

    @Test
    fun `a waiting app whose key was rotated from a newly known signer is trusted on its next hello`() {
        val rotated = vitals.copy(signerSha256 = "eeff", pastSignersSha256 = setOf("aabbccdd"))
        core(known = emptyMap()).hello(rotated, all)

        assertThat(core().hello(rotated, all).trust).isEqualTo(OpenLinkTrust.TRUSTED)
    }

    @Test
    fun `what the user took back from a first-party client stays taken back through its next hello`() {
        val core = core()
        core.hello(vitals, all)

        core.revoke(vitals.packageName, OpenLink.CAPABILITY_LIVE)

        assertThat(core.hello(vitals, all).capabilities).doesNotContain(OpenLink.CAPABILITY_LIVE)
        assertThat(core().allows(vitals, OpenLink.CAPABILITY_LIVE)).isFalse()

        core.grant(vitals.packageName, OpenLink.CAPABILITY_LIVE)
        assertThat(core.hello(vitals, all).capabilities).contains(OpenLink.CAPABILITY_LIVE)
    }

    @Test
    fun `a first-party client still gains what a newer build of it newly asks for`() {
        val core = core()
        core.hello(vitals, OpenLinkHello(requestedCapabilities = setOf("profile")))
        core.revoke(vitals.packageName, OpenLink.CAPABILITY_PROFILE)

        val decision = core.hello(vitals, OpenLinkHello(requestedCapabilities = setOf("profile", "devices")))

        assertThat(decision.capabilities).containsExactly("devices")
    }

    @Test
    fun `a known signer under another package name is not first-party`() {
        val core = core()
        val other = vitals.copy(packageName = "com.example.same.key")

        assertThat(core.hello(other, all).trust).isEqualTo(OpenLinkTrust.PENDING)
        assertThat(core.clients.value.single().knownSigner).isFalse()
        assertThat(core.hello(vitals, all).trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(core.clients.value.single { it.packageName == vitals.packageName }.knownSigner).isTrue()
    }

    @Test
    fun `a blocked package stays blocked under another key`() {
        val core = core()
        core.hello(stranger, all)
        core.block(stranger.packageName)
        val other = stranger.copy(signerSha256 = "9999")

        assertThat(core.hello(other, all).trust).isEqualTo(OpenLinkTrust.BLOCKED)
        assertThat(core.decision(other).trust).isEqualTo(OpenLinkTrust.BLOCKED)
    }

    @Test
    fun `a first-party client that comes back under another of its keys keeps what the user decided`() {
        val twoKeys = mapOf(vitals.packageName to setOf(firstParty, "eeff"))
        val fromPlay = vitals.copy(signerSha256 = "eeff")
        val core = core(twoKeys)
        core.hello(vitals, all)
        core.revoke(vitals.packageName, OpenLink.CAPABILITY_LIVE)

        val decision = core.hello(fromPlay, all)

        assertThat(decision.trust).isEqualTo(OpenLinkTrust.TRUSTED)
        assertThat(decision.capabilities).doesNotContain(OpenLink.CAPABILITY_LIVE)
        assertThat(core.allows(fromPlay, OpenLink.CAPABILITY_PROFILE)).isTrue()

        core.block(vitals.packageName)
        assertThat(core.hello(vitals, all).trust).isEqualTo(OpenLinkTrust.BLOCKED)
    }

    @Test
    fun `a stale pending record goes on any hello, a trusted client's too`() {
        val core = core()
        core.hello(stranger, all)

        now += OpenLinkHostCore.PENDING_MAX_AGE_MILLIS + 1
        core.hello(vitals, all)

        assertThat(core.clients.value.map { it.packageName }).containsExactly(vitals.packageName)
    }

    @Test
    fun `a pending client that is bound now is not dropped under it`() {
        val core = core().apply { isConnected = { it == stranger.packageName } }
        core.hello(stranger, all)

        now += OpenLinkHostCore.PENDING_MAX_AGE_MILLIS + 1
        repeat(OpenLinkHostCore.MAX_PENDING + 1) { i -> core.hello(OpenLinkCaller(20_000 + i, "com.example.app$i", "ff$i"), all) }

        core.allow(stranger.packageName)
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_DEVICES)).isTrue()
    }

    @Test
    fun `forgetting a client that is bound now starts it over where it can be allowed again`() {
        val core = core().apply { isConnected = { true } }
        core.hello(stranger, all)
        core.allow(stranger.packageName)
        core.hello(vitals, all)
        core.revoke(vitals.packageName, OpenLink.CAPABILITY_LIVE)

        core.forget(stranger.packageName)
        core.forget(vitals.packageName)

        assertThat(core.decision(stranger).trust).isEqualTo(OpenLinkTrust.PENDING)
        core.allow(stranger.packageName)
        assertThat(core.allows(stranger, OpenLink.CAPABILITY_DEVICES)).isTrue()
        // A first-party client starts over as its first hello would leave it.
        assertThat(core.allows(vitals, OpenLink.CAPABILITY_LIVE)).isTrue()
    }

    @Test
    fun `a trust change is told after the lock is released`() {
        val core = core()
        var heldLock = true
        core.onTrustChanged = { _, _, _ -> heldLock = Thread.holdsLock(core) }

        core.hello(vitals, all)

        assertThat(heldLock).isFalse()
    }

    @Test
    fun `only a client that said hello and is not blocked may register a listener`() {
        val core = core()

        assertThat(core.mayListen(stranger)).isFalse()
        core.hello(stranger, all)
        assertThat(core.mayListen(stranger)).isTrue()
        core.block(stranger.packageName)
        assertThat(core.mayListen(stranger)).isFalse()
    }

    @Test
    fun `an app's version label is cut to a fixed length`() {
        val core = core()

        core.hello(stranger, OpenLinkHello(appVersion = "x".repeat(10_000)))

        assertThat(core.clients.value.single().appVersion).hasLength(OpenLinkHostCore.MAX_APP_VERSION_LENGTH)
    }
}
