package io.github.benbaobaoshigemi.rotationcontrol

import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class GestureInputDispatcherTest {
    @Test fun singleFingerMovesBypassClockConfigurationAndDisplayReads() {
        val input = Input()
        input.start(1)
        repeat(120) { input.send(ACTION_MOVE, 1, dx = it.toFloat()) }
        input.finish(1)

        assertEquals(2, input.clockReads)
        assertEquals(2, input.configReads)
        assertEquals(0, input.displayReads)
        assertTrue(input.rotations.isEmpty())
        assertEquals(0, input.toggles)
        println("Single-finger DOWN + 120 MOVE + UP: clock=2, config=2, display=0 (each event delivered twice)")
    }

    @Test fun doubleTapUsesSnapshotsAndRotatesOnceWithDuplicateEvents() {
        val input = Input()
        repeat(2) {
            input.start(2)
            repeat(20) { input.send(ACTION_MOVE, 2, dx = 1f) }
            input.finish(2, dx = 1f)
        }

        assertEquals(listOf(Surface.ROTATION_0), input.rotations)
        assertEquals(0, input.toggles)
        assertEquals(4, input.displayReads)
        println("Two-finger double tap with 40 MOVE samples: 4 display reads (two per tap)")
    }

    @Test fun tripleTapTogglesOnceAndSharesDisplayReadWithSwipeDetector() {
        val input = Input(primary = "WMS-PointerListener")
        repeat(3) {
            input.start(3)
            input.send(ACTION_MOVE, 3, dx = 2f)
            input.finish(3, dx = 2f)
        }

        assertEquals(1, input.toggles)
        assertTrue(input.rotations.isEmpty())
        // Two fingers down, three fingers down (shared), and final up.
        assertEquals(9, input.displayReads)
    }

    @Test fun ordinaryTapBetweenThreeFingerTapsBreaksTheSequence() {
        val input = Input()
        repeat(2) { input.tap(3) }
        input.tap(1)
        repeat(2) { input.tap(3) }
        assertEquals(0, input.toggles)
        input.tap(3)
        assertEquals(1, input.toggles)
    }

    @Test fun bottomSwipeRotatesOnceAndReadsDisplayOnlyAtStartAndTrigger() {
        val input = Input()
        input.start(3, y = 850f)
        repeat(25) { input.send(ACTION_MOVE, 3, y = 850f, dx = -it.toFloat()) }
        input.send(ACTION_MOVE, 3, y = 850f, dx = -130f)
        input.finish(3, y = 850f, dx = -130f)

        assertEquals(listOf(Surface.ROTATION_90), input.rotations)
        assertEquals(0, input.toggles)
        assertEquals(3, input.displayReads)
    }

    @Test fun verticalSwipeCannotBecomeHorizontalRotationLaterInTheSameStream() {
        val input = Input()
        input.start(3, y = 850f)
        input.send(ACTION_MOVE, 3, y = 850f, dy = -70f)
        input.send(ACTION_MOVE, 3, y = 850f, dx = -130f)
        input.finish(3, y = 850f, dx = -130f)

        assertTrue(input.rotations.isEmpty())
        assertEquals(0, input.toggles)
    }

    @Test fun foldBetweenTapsDoesNotCombineCoordinatesFromDifferentDisplays() {
        val input = Input()
        input.tap(2)
        input.geometry = input.geometry.copy(width = 1000f, height = 600f, rotation = Surface.ROTATION_90)
        input.tap(2)
        assertTrue(input.rotations.isEmpty())
        input.tap(2)
        assertEquals(1, input.rotations.size)
    }

    @Test fun rotationDuringSwipeCancelsItAndNextSwipeUsesNewDimensions() {
        val input = Input()
        input.start(3, y = 850f)
        input.geometry = GestureDisplaySnapshot(1000f, 600f, Surface.ROTATION_90, 1f)
        input.send(ACTION_MOVE, 3, y = 850f, dx = -140f)
        input.finish(3, y = 850f, dx = -140f)
        assertTrue(input.rotations.isEmpty())

        input.start(3, y = 520f)
        input.send(ACTION_MOVE, 3, y = 520f, dx = -140f)
        input.finish(3, y = 520f, dx = -140f)
        assertEquals(listOf(Surface.ROTATION_180), input.rotations)
    }

    @Test fun geometryChangeAtTapEndDoesNotCountAsPartOfTripleTap() {
        val input = Input()
        repeat(2) { input.tap(3) }
        input.start(3)
        input.geometry = input.geometry.copy(density = 1.5f)
        input.finish(3)
        assertEquals(0, input.toggles)

        repeat(3) { input.tap(3) }
        assertEquals(1, input.toggles)
    }

    @Test fun interruptedOrCancelledTouchClearsPendingTapState() {
        val input = Input()
        repeat(2) { input.tap(3) }
        input.start(3)
        // No UP or CANCEL; the next DOWN must reset all detectors.
        repeat(2) { input.tap(3) }
        assertEquals(0, input.toggles)
        input.start(3)
        input.send(ACTION_CANCEL, 3)
        repeat(2) { input.tap(3) }
        assertEquals(0, input.toggles)
        input.tap(3)
        assertEquals(1, input.toggles)
    }

    private class Input(private val primary: String = "IMS-Filter") {
        var geometry = GestureDisplaySnapshot(600f, 1000f, Surface.ROTATION_0, 1f)
        var displayReads = 0
        var clockReads = 0
        var configReads = 0
        var toggles = 0
        val rotations = mutableListOf<Int>()

        private var now = 1000L
        private var down = 0L
        private val secondary = if (primary == "IMS-Filter") "WMS-PointerListener" else "IMS-Filter"
        private val dispatcher = GestureInputDispatcher(
            loadDisplay = { displayReads++; geometry },
            nowMillis = { clockReads++; now },
            enabledGestures = {
                configReads++
                GestureInputDispatcher.TWO_FINGER or GestureInputDispatcher.BOTTOM_SWIPE or
                    GestureInputDispatcher.TRIPLE_TAP
            },
            onRotate = { rotation, _ -> rotations.add(rotation) },
            onToggleLock = { toggles++ }
        )

        fun tap(pointers: Int) {
            start(pointers)
            finish(pointers)
        }

        fun start(pointers: Int, y: Float = 200f) {
            now += 60
            down = now
            send(ACTION_DOWN, 1, y)
            for (count in 2..pointers) send(ACTION_POINTER_DOWN, count, y)
        }

        fun finish(pointers: Int, y: Float = 200f, dx: Float = 0f) {
            for (count in pointers downTo 2) send(ACTION_POINTER_UP, count, y, dx)
            send(ACTION_UP, 1, y, dx)
        }

        fun send(action: Int, count: Int, y: Float = 200f, dx: Float = 0f, dy: Float = 0f) {
            now++
            val event = Mockito.mock(MotionEvent::class.java)
            Mockito.`when`(event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)).thenReturn(true)
            Mockito.`when`(event.actionMasked).thenReturn(action)
            Mockito.`when`(event.actionIndex).thenReturn(count - 1)
            Mockito.`when`(event.pointerCount).thenReturn(count)
            Mockito.`when`(event.downTime).thenReturn(down)
            Mockito.`when`(event.deviceId).thenReturn(1)
            for (index in 0 until count) {
                Mockito.`when`(event.getPointerId(index)).thenReturn(index)
                Mockito.`when`(event.getX(index)).thenReturn(200f + 30f * index + dx)
                Mockito.`when`(event.getY(index)).thenReturn(y + dy)
            }
            dispatcher.onPointerEvent(event, primary)
            dispatcher.onPointerEvent(event, secondary)
        }
    }
}
