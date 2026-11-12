package io.github.benbaobaoshigemi.rotationcontrol

import android.view.InputDevice
import android.view.MotionEvent

/** Both input callbacks share one arbiter and one lock for detector state. */
internal class GestureInputDispatcher(
    loadDisplay: () -> GestureDisplaySnapshot,
    private val nowMillis: () -> Long,
    private val enabledGestures: () -> Int,
    onRotate: (Int, String) -> Unit,
    onToggleLock: () -> Unit
) {
    companion object {
        const val TWO_FINGER = 1
        const val BOTTOM_SWIPE = 2
        const val TRIPLE_TAP = 4
    }

    private val lock = Any()
    private val arbiter = TouchStreamArbiter()
    private val display = GestureDisplay(loadDisplay)
    private val twoFinger = ScreenRotationGestureDetector(display, onRotate)
    private val swipe = ThreeFingerSwipeGestureDetector(display, onRotate)
    private val tripleTap = ThreeFingerTripleTapDetector(display, onToggleLock)

    fun onPointerEvent(event: MotionEvent, channel: String) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        val action = event.actionMasked
        val pointers = event.pointerCount
        val isMove = action == MotionEvent.ACTION_MOVE
        // A one-pointer MOVE cannot change ownership or gesture state. DOWN,
        // POINTER_DOWN/UP and UP/CANCEL still reach the state machines.
        if (isMove && pointers == 1) return

        synchronized(lock) {
            when (arbiter.accept(event.downTime, event.deviceId, action, channel)) {
                TouchStreamArbiter.Decision.DROP -> return
                TouchStreamArbiter.Decision.RESTART -> {
                    twoFinger.resetToIdle()
                    swipe.resetStream()
                    tripleTap.resetToIdle()
                }
                TouchStreamArbiter.Decision.ACCEPT -> Unit
            }

            val enabled = enabledGestures()
            if (enabled == 0) return
            display.beginEvent()
            val now = nowMillis()
            if (enabled and TWO_FINGER != 0) {
                twoFinger.onPointerEvent(event, action, pointers, now)
            }
            if (!isMove || pointers >= 3) {
                if (enabled and BOTTOM_SWIPE != 0) {
                    swipe.onPointerEvent(event, action, pointers, now)
                }
                if (enabled and TRIPLE_TAP != 0) {
                    tripleTap.onPointerEvent(event, action, pointers, now)
                }
            }
        }
    }
}
