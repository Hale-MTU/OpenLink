package tech.mmarca.openlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLinkSettingKeys.GOAL_STEPS
import tech.mmarca.openlink.OpenLinkSettingKeys.GOAL_WATER_ML
import tech.mmarca.openlink.OpenLinkSettingKeys.ZONES_LOWER_BPM

class OpenLinkSettingsTest {

    private val now = 1_800_000_000_000L
    private val day = OpenLinkSettings.MAX_CLOCK_AHEAD_MILLIS

    private fun of(vararg entries: Pair<String, OpenLinkSetting>) = OpenLinkSettings(entries.toMap())

    @Test
    fun `only keys OpenLink knows cross`() {
        val settings = of(
            GOAL_STEPS.name to OpenLinkSetting(9_000.0, now),
            "profile.name" to OpenLinkSetting(1.0, now),
        )

        assertThat(settings.valid(now).entries.keys).containsExactly(GOAL_STEPS.name)
    }

    @Test
    fun `a value outside its key's range, not finite, or of the wrong count is dropped`() {
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(1_000_000.0, now)).valid(now).isEmpty).isTrue()
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(Double.NaN, now)).valid(now).isEmpty).isTrue()
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(listOf(8_000.0, 9_000.0), now)).valid(now).isEmpty).isTrue()
        assertThat(of(ZONES_LOWER_BPM.name to OpenLinkSetting(listOf(100.0, 120.0, 140.0), now)).valid(now).isEmpty).isTrue()
    }

    @Test
    fun `zone limits must rise from zone 1 to zone 5`() {
        val rising = listOf(100.0, 120.0, 140.0, 160.0, 180.0)
        assertThat(of(ZONES_LOWER_BPM.name to OpenLinkSetting(rising, now)).valid(now).numbers(ZONES_LOWER_BPM)).isEqualTo(rising)
        val falling = rising.reversed()
        assertThat(of(ZONES_LOWER_BPM.name to OpenLinkSetting(falling, now)).valid(now).isEmpty).isTrue()
    }

    @Test
    fun `a cleared setting crosses, so a removal reaches the other side`() {
        val cleared = of(GOAL_WATER_ML.name to OpenLinkSetting(emptyList(), now)).valid(now)

        assertThat(cleared.entries.keys).containsExactly(GOAL_WATER_ML.name)
        assertThat(cleared.number(GOAL_WATER_ML)).isNull()
    }

    @Test
    fun `a change without a time, or dated more than a day ahead, is dropped`() {
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(9_000.0, 0)).valid(now).isEmpty).isTrue()
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(9_000.0, now + day + 1)).valid(now).isEmpty).isTrue()
        assertThat(of(GOAL_STEPS.name to OpenLinkSetting(9_000.0, now + day)).valid(now).isEmpty).isFalse()
    }

    @Test
    fun `the newer value wins key by key, and a tie keeps this side's`() {
        val mine = OpenLinkSettings()
            .with(GOAL_STEPS, listOf(8_000.0), 100)
            .with(GOAL_WATER_ML, listOf(2_000.0), 300)
        val theirs = OpenLinkSettings()
            .with(GOAL_STEPS, listOf(10_000.0), 200)
            .with(GOAL_WATER_ML, listOf(2_500.0), 300)

        val merged = mine.merge(theirs)

        assertThat(merged.number(GOAL_STEPS)).isEqualTo(10_000.0)
        assertThat(merged.number(GOAL_WATER_ML)).isEqualTo(2_000.0)
    }
}
