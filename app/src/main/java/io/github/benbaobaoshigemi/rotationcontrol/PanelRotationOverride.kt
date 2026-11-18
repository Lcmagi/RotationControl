package io.github.benbaobaoshigemi.rotationcontrol

/** A module-owned freeze can be restored only while the system settings match it. */
internal class PanelRotationOverride {
    data class Panel(val width: Int, val height: Int) {
        fun acceptsGestureFrom(origin: Panel?): Boolean = this == origin
    }
    data class Settings(val autoRotate: Int, val angle: Int)

    private data class Active(val panel: Panel, val previous: Settings, var applied: Settings, var recovering: Boolean)
    private var active: Active? = null

    val isActive: Boolean get() = active != null

    val needsRecovery: Boolean get() = active?.recovering == true

    fun apply(panel: Panel, before: Settings, target: Settings, write: (Settings, Settings) -> Unit) {
        val old = active
        val original = if (old?.panel == panel && old.applied == before) old.previous else before
        val entry = Active(panel, original, before, true)
        active = entry
        update(entry, target, write)
        entry.recovering = false
    }

    fun restore(panel: Panel, read: () -> Settings, write: (Settings, Settings) -> Unit): Settings? {
        val entry = active ?: return null
        if (panel == entry.panel && !entry.recovering) return null
        if (read() != entry.applied) {
            clear()
            return null
        }
        entry.recovering = true
        update(entry, entry.previous, write)
        if (read() != entry.previous) return null
        clear()
        return entry.previous
    }

    private fun update(entry: Active, target: Settings, write: (Settings, Settings) -> Unit) {
        val angleFirst = entry.applied.copy(angle = target.angle)
        for (next in arrayOf(angleFirst, target)) {
            if (entry.applied == next) continue
            write(entry.applied, next)
            entry.applied = next
        }
    }

    fun clear() {
        active = null
    }
}
