package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings

class OpenLinkSettingsHandOverTest {

    private val now = 1_800_000_000_000L
    private val log = mutableListOf<String>()
    private val handedOver = mutableListOf<OpenLinkSettings>()

    private fun handOver(received: OpenLinkSettings, fails: Boolean = false) =
        handOverSettings(received, now, { log += it }) {
            if (fails) error("storage not ready")
            handedOver += it
        }

    @Test
    fun `valid settings go to the host app, whose publish is the answer`() {
        val steps = OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0), now)

        assertThat(handOver(steps)).isFalse()
        assertThat(handedOver).containsExactly(steps)
        assertThat(log).isEmpty()
    }

    @Test
    fun `only the valid ones go over, and the dropped ones are counted, not named`() {
        val received = OpenLinkSettings(
            mapOf(
                OpenLinkSettingKeys.GOAL_STEPS.name to OpenLinkSetting(9_000.0, now),
                "profile.name" to OpenLinkSetting(1.0, now),
                OpenLinkSettingKeys.GOAL_FLOORS.name to OpenLinkSetting(1_000_000.0, now),
            ),
        )

        assertThat(handOver(received)).isFalse()
        assertThat(handedOver.single().entries.keys).containsExactly(OpenLinkSettingKeys.GOAL_STEPS.name)
        assertThat(log).containsExactly("dropped 2 settings")
    }

    @Test
    fun `when every entry is dropped the app gets nothing and the library answers`() {
        val received = OpenLinkSettings(mapOf("profile.name" to OpenLinkSetting(1.0, now)))

        assertThat(handOver(received)).isTrue()
        assertThat(handedOver).isEmpty()
        assertThat(handOver(OpenLinkSettings.EMPTY)).isTrue()
    }

    @Test
    fun `when the host app throws the library answers, and the log names no value`() {
        val steps = OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0), now)

        assertThat(handOver(steps, fails = true)).isTrue()
        assertThat(log).containsExactly("the host app refused settings: IllegalStateException")
    }
}
