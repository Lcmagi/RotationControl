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

    private fun record(panel: Panel, before: Settings, after: Settings) =
        policy.apply(panel, before, after) { _, _ -> }

    private fun restore(panel: Panel, read: () -> Settings): Settings? {
        var written: Settings? = null
        return policy.restore(panel, { written ?: read() }) { _, to -> written = to }
    }

    @Test fun queuedInnerGestureIsRejectedOnOuterPanel() {
        assertFalse(outer.acceptsGestureFrom(inner))
        assertFalse(outer.acceptsGestureFrom(null))
        assertEquals(true, inner.acceptsGestureFrom(inner))
    }

    @Test fun failedSecondWriteCanRecoverOnOriginalPanel() {
        var current = automatic
        runCatching {
            policy.apply(inner, current, landscape) { _, to ->
                if (to == landscape) error("write failed")
                current = to
            }
        }
        assertEquals(Settings(1, 1), current)
        assertEquals(automatic, policy.restore(inner, { current }) { _, to -> current = to })
        assertEquals(automatic, current)
    }

    @Test fun partialRestoreRetriesAfterReadOrWriteFailure() {
        record(inner, automatic, landscape)
        var current = landscape
        runCatching { policy.restore(outer, { error("read failed") }) { _, _ -> } }
        runCatching {
            policy.restore(outer, { current }) { _, to ->
                if (to == automatic) error("write failed")
                current = to
            }
        }
        assertEquals(portrait, current)
        assertEquals(automatic, policy.restore(outer, { current }) { _, to -> current = to })
        assertEquals(automatic, current)
    }

    @Test fun restoresOriginalPortraitLockAfterRepeatedGesturesOnTheSamePanel() {
        record(inner, portrait, landscape)
        record(inner, landscape, reversed)
        assertEquals(portrait, restore(outer) { reversed })
        assertFalse(policy.isActive)
    }

    @Test fun restoresAutomaticRotationIfItWasOnBeforeTheGesture() {
        record(inner, automatic, landscape)
        assertEquals(automatic, restore(outer) { landscape })
    }

    @Test fun samePhysicalSizeDoesNotReadSettingsOrRestoreTheOverride() {
        record(inner, portrait, landscape)
        assertNull(restore(inner) { error("Should not read settings") })
        assertEquals(portrait, restore(outer) { landscape })
    }

    @Test fun preservesAUserChangeMadeAfterTheGesture() {
        record(inner, portrait, landscape)
        assertNull(restore(outer) { automatic })
        assertFalse(policy.isActive)
    }

    @Test fun explicitRotationLockToggleDropsTheModuleOverride() {
        record(inner, portrait, landscape)
        policy.clear()
        assertNull(restore(outer) { error("Should not read settings") })
    }

    @Test fun noGestureMeansNoAutomaticSettingsWriteOnFold() {
        assertNull(restore(outer) { error("Should not read settings") })
    }
}
