package io.github.benbaobaoshigemi.rotationcontrol

import io.github.benbaobaoshigemi.rotationcontrol.PanelRotationOverride.Panel
import io.github.benbaobaoshigemi.rotationcontrol.PanelRotationOverride.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PanelRotationOverrideTest {
    private val inner = Panel(2100, 2400)
    private val outer = Panel(1000, 2400)
    private val portrait = Settings(0, 0)
    private val landscape = Settings(0, 1)
    private val reversed = Settings(0, 2)
    private val automatic = Settings(1, 0)
    private val policy = PanelRotationOverride()

    @Test fun restoresOriginalPortraitLockAfterRepeatedGesturesOnTheSamePanel() {
        policy.record(inner, portrait, landscape)
        policy.record(inner, landscape, reversed)
        assertEquals(portrait, policy.onPanelChanged(outer) { reversed })
        assertFalse(policy.isActive)
    }

    @Test fun restoresAutomaticRotationIfItWasOnBeforeTheGesture() {
        policy.record(inner, automatic, landscape)
        assertEquals(automatic, policy.onPanelChanged(outer) { landscape })
    }

    @Test fun samePhysicalSizeDoesNotReadSettingsOrRestoreTheOverride() {
        policy.record(inner, portrait, landscape)
        assertNull(policy.onPanelChanged(inner) { error("Should not read settings") })
        assertEquals(portrait, policy.onPanelChanged(outer) { landscape })
    }

    @Test fun preservesAUserChangeMadeAfterTheGesture() {
        policy.record(inner, portrait, landscape)
        assertNull(policy.onPanelChanged(outer) { automatic })
        assertFalse(policy.isActive)
    }

    @Test fun explicitRotationLockToggleDropsTheModuleOverride() {
        policy.record(inner, portrait, landscape)
        policy.clear()
        assertNull(policy.onPanelChanged(outer) { error("Should not read settings") })
    }

    @Test fun noGestureMeansNoAutomaticSettingsWriteOnFold() {
        assertNull(policy.onPanelChanged(outer) { error("Should not read settings") })
    }
}
