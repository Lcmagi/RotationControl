package io.github.benbaobaoshigemi.rotationcontrol

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.benbaobaoshigemi.rotationcontrol.TouchStreamArbiter.Decision.*

class TouchStreamArbiterTest {
    private val ims = "IMS-Filter"
    private val wms = "WMS-PointerListener"
    private data class Event(val down: Long, val action: Int, val time: Long)
    private fun tap(down: Long, fingers: Int): List<Event> = buildList {
        add(Event(down, 0, down))
        for (i in 1 until fingers) add(Event(down, 5 or (i shl 8), down + 1))
        add(Event(down, 2, down + 2))
        for (i in fingers - 1 downTo 1) add(Event(down, 6 or (i shl 8), down + 3))
        add(Event(down, 1, down + 4))
    }
    private fun TouchStreamArbiter.route(event: Event, channel: String) =
        accept(event.down, 1, event.action and 255, channel)

    @Test fun duplicatedTripleTapPreservesExactlyThreeCompleteTaps() {
        for ((first, second) in listOf(ims to wms, wms to ims)) {
            val gate = TouchStreamArbiter()
            val expected = (1L..3L).flatMap { tap(it * 100, 3) }
            val actual = mutableListOf<Event>()
            for (event in expected) {
                for (channel in listOf(first, second)) {
                    if (gate.route(event, channel) != DROP) actual.add(event)
                }
            }
            assertEquals(expected, actual)
            assertEquals(3, actual.count { it.action == 1 })
        }
    }

    @Test fun delayedWholeStreamAndPreviousTapCannotReplay() {
        val gate = TouchStreamArbiter()
        val first = tap(100, 3)
        val next = tap(200, 3)
        first.forEach { assertEquals(ACCEPT, gate.route(it, ims)) }
        assertEquals(ACCEPT, gate.route(next.first(), wms))
        first.forEach { assertEquals(DROP, gate.route(it, wms)) }
        next.drop(1).forEach { assertEquals(ACCEPT, gate.route(it, wms)) }
        next.forEach { assertEquals(DROP, gate.route(it, ims)) }
    }

    @Test fun twoFingerDoubleTapPreservesPointerTransitions() {
        val gate = TouchStreamArbiter()
        for (down in listOf(100L, 200L)) {
            for (event in tap(down, 2)) {
                assertEquals(ACCEPT, gate.route(event, wms))
                assertEquals(DROP, gate.route(event, ims))
            }
        }
    }

    @Test fun swipeKeepsAllOwnerMovesEvenWithEqualMillisecondTimes() {
        val gate = TouchStreamArbiter()
        assertEquals(ACCEPT, gate.accept(100, 1, 0, ims))
        // Different MOVE samples / batched payloads must not be merged by time.
        repeat(5) { assertEquals(ACCEPT, gate.route(Event(100, 2, 101), ims)) }
        assertEquals(DROP, gate.route(Event(100, 2, 101), wms))
        assertEquals(ACCEPT, gate.accept(100, 1, 1, ims))
    }

    @Test fun cancelIsDeliveredOnceAndNextStreamCanChooseFallback() {
        val gate = TouchStreamArbiter()
        assertEquals(ACCEPT, gate.accept(100, 1, 0, wms))
        assertEquals(ACCEPT, gate.accept(100, 1, 3, wms))
        assertEquals(DROP, gate.accept(100, 1, 3, ims))
        assertEquals(DROP, gate.accept(100, 1, 0, ims))
        assertEquals(ACCEPT, gate.accept(200, 1, 0, ims))
        assertEquals(ACCEPT, gate.accept(200, 1, 1, ims))
    }

    @Test fun missingUpRequestsCancellationBeforeNewDown() {
        val gate = TouchStreamArbiter()
        assertEquals(ACCEPT, gate.accept(100, 1, 0, ims))
        assertEquals(RESTART, gate.accept(200, 1, 0, wms))
        assertEquals(DROP, gate.accept(100, 1, 1, ims))
        assertEquals(ACCEPT, gate.accept(200, 1, 1, wms))
    }

    @Test fun orphanAndForeignDeviceOrChannelCannotJoinMidStream() {
        val gate = TouchStreamArbiter()
        assertEquals(DROP, gate.accept(100, 1, 5, ims))
        assertEquals(ACCEPT, gate.accept(100, 1, 0, wms))
        assertEquals(DROP, gate.accept(100, 2, 5, wms))
        assertEquals(DROP, gate.accept(100, 1, 5, ims))
        assertEquals(ACCEPT, gate.accept(100, 1, 5, wms))
        assertEquals(DROP, gate.accept(100, 1, 0, wms))
        assertEquals(ACCEPT, gate.accept(100, 1, 1, wms))
    }
}
