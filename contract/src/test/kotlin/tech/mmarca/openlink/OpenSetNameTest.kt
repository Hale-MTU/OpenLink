package tech.mmarca.openlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A kind or an integration newer than the library is kept; text that is no name is not. */
class OpenSetNameTest {

    @Test
    fun `the known names and a newer one read, and equal their constants`() {
        assertThat(OpenLinkDeviceKind.of("SCALE")).isEqualTo(OpenLinkDeviceKind.SCALE)
        assertThat(OpenLinkIntegration.of("WEAROS")).isEqualTo(OpenLinkIntegration.WEAROS)
        assertThat(OpenLinkDeviceKind.of("HR_STRAP")?.name).isEqualTo("HR_STRAP")
        assertThat(OpenLinkIntegration.of("POLAR2")?.name).isEqualTo("POLAR2")
    }

    @Test
    fun `text that is no name reads as nothing`() {
        for (text in listOf(null, "", "scale", "1WATCH", "WATCH ", "WATCH-2", "W".repeat(33))) {
            assertThat(OpenLinkDeviceKind.of(text)).isNull()
            assertThat(OpenLinkIntegration.of(text)).isNull()
        }
        assertThat(OpenLinkDeviceKind.of("W".repeat(32))).isNotNull()
    }
}
