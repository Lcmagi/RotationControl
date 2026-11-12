package io.github.benbaobaoshigemi.rotationcontrol

/**
 * Exactly one input channel owns a DOWN..UP/CANCEL stream. The first channel to
 * deliver DOWN wins; registration alone does not prove that WMS delivers events.
 *
 * Keep the completed stream's downTime, too: the other channel may replay a
 * whole tap after its owner has already delivered UP. A last-event fingerprint
 * or an adjacent-duplicate check cannot handle that ordering. Do not compare
 * coordinates: IMS and WMS may use different coordinate spaces / MOVE batches.
 *
 * Call under the same lock as detector dispatch. One default-display touchscreen
 * stream is supported, matching the WMS listener registered on display 0.
 */
internal class TouchStreamArbiter {
    enum class Decision { DROP, ACCEPT, RESTART }

    private var downTime = Long.MIN_VALUE
    private var deviceId = 0
    private var owner: String? = null
    private var finished = false

    fun accept(timeDown: Long, device: Int, action: Int, channel: String): Decision {
        if (action == 0 /* ACTION_DOWN */) {
            // A duplicate or late DOWN must never rewind any detector's state.
            if (timeDown <= downTime) return Decision.DROP
            val interrupted = downTime != Long.MIN_VALUE && !finished
            downTime = timeDown
            deviceId = device
            owner = channel
            finished = false
            return if (interrupted) Decision.RESTART else Decision.ACCEPT
        }

        if (timeDown != downTime || device != deviceId || channel != owner || finished) {
            return Decision.DROP
        }
        if (action == 1 /* ACTION_UP */ || action == 3 /* ACTION_CANCEL */) {
            finished = true
        }
        // Never merge MOVE samples using millisecond timestamps: distinct samples
        // and POINTER_DOWN/UP transitions can share the same eventTime.
        return Decision.ACCEPT
    }
}
