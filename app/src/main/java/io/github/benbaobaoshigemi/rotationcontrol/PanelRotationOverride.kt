package io.github.benbaobaoshigemi.rotationcontrol

/** A module-owned freeze can be restored only while the system settings match it. */
internal class PanelRotationOverride {
    data class Panel(val width: Int, val height: Int)
    data class Settings(val autoRotate: Int, val angle: Int)

    private data class Active(val panel: Panel, val previous: Settings, val applied: Settings)
    private var active: Active? = null

    val isActive: Boolean get() = active != null

    fun record(panel: Panel, before: Settings, applied: Settings) {
        val existing = active
        val previous = if (existing?.panel == panel && existing.applied == before) {
            existing.previous
        } else {
            before
        }
        active = Active(panel, previous, applied)
    }

    fun onPanelChanged(panel: Panel, current: () -> Settings): Settings? {
        val existing = active ?: return null
        if (existing.panel == panel) return null
        active = null
        return existing.previous.takeIf { existing.applied == current() }
    }

    fun clear() {
        active = null
    }
}
