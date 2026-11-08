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
    private fun core() = OpenLinkHostCore(OpenLinkTrustStore(storage), knownSigners = setOf(firstParty), clock = { now })

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
}
