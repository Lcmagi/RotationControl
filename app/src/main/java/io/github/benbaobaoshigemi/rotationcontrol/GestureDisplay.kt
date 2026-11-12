package io.github.benbaobaoshigemi.rotationcontrol

/** Display coordinates and dp scale used by a gesture candidate. */
internal data class GestureDisplaySnapshot(
    val width: Float,
    val height: Float,
    val rotation: Int,
    val density: Float
)

/**
 * Query lazily, at most once for a dispatched event. Detectors keep their own
 * candidate snapshot for MOVE and read again before accepting a tap or swipe.
 * A new candidate always starts with current geometry, even after folding.
 * Access is confined to the dispatcher lock.
 */
internal class GestureDisplay(private val load: () -> GestureDisplaySnapshot) {
    private var snapshot: GestureDisplaySnapshot? = null

    fun beginEvent() {
        snapshot = null
    }

    fun read(): GestureDisplaySnapshot = snapshot ?: load().also { snapshot = it }
}
