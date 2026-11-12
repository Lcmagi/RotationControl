package io.github.benbaobaoshigemi.rotationcontrol

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * 屏幕任意位置三指三击（Triple Tap）检测器：
 * 
 * 核心逻辑：
 * 1. 屏幕任意位置检测 3 指快速连续轻击 3 次；
 * 2. 严格的敲击时限（单次 <= 320ms）与位移门限（<= 45dp），与任何滑动截屏动作物理互斥；
 * 3. 触发后切换屏幕旋转锁定状态：
 *    - 若当前已锁定 (ACCELEROMETER_ROTATION=0)：解锁并开启自动旋转 (ACCELEROMETER_ROTATION=1)，双击震动反馈；
 *    - 若当前为自动旋转 (ACCELEROMETER_ROTATION=1)：锁定为当前屏幕物理朝向 (ACCELEROMETER_ROTATION=0)，单次利落震动反馈。
 */
internal class ThreeFingerTripleTapDetector(
    private val display: GestureDisplay,
    private val onToggleLockAction: () -> Unit
) {
    companion object {
        private const val MAX_TAP_DURATION_MS = 320L    // 单次敲击最大允许时长 (ms)
        private const val MAX_TAP_GAP_MS = 380L         // 敲击之间的最大间隔时长 (ms)
        private const val MAX_SLOP_DP = 45f             // 单次点击中允许的最大移动 (dp)，严格滤除滑动手势
        private const val MAX_CLUSTER_DRIFT_DP = 90f    // 三次点击重心之间的最大允许漂移 (dp)
    }

    private enum class State {
        IDLE,
        TAP_1_DOWN,
        WAITING_TAP_2,
        TAP_2_DOWN,
        WAITING_TAP_3,
        TAP_3_DOWN
    }

    private class Point(var x: Float, var y: Float)

    private var currentState = State.IDLE
    private var tapDownTime = 0L
    private var isTapDisqualified = false
    private var maxPointersInTap = 0

    private var tap1Centroid: Point? = null
    private var tap1UpTime = 0L

    private var tap2Centroid: Point? = null
    private var tap2UpTime = 0L

    private var tapDisplay: GestureDisplaySnapshot? = null
    private var firstTapDisplay: GestureDisplaySnapshot? = null
    private var tapStartCentroid: Point? = null
    private var lastCurrentCentroid: Point? = null

    fun onPointerEvent(event: MotionEvent, actionMasked: Int, pointerCount: Int, now: Long) {
        if (pointerCount > maxPointersInTap) {
            maxPointersInTap = pointerCount
        }

        // 超过 3 指，立刻取消三击检测
        if (pointerCount > 3) {
            resetToIdle()
            return
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 检查前序点击等待超时
                if (currentState == State.WAITING_TAP_2 && now - tap1UpTime > MAX_TAP_GAP_MS) {
                    resetToIdle()
                } else if (currentState == State.WAITING_TAP_3 && now - tap2UpTime > MAX_TAP_GAP_MS) {
                    resetToIdle()
                }

                currentState = when (currentState) {
                    State.WAITING_TAP_2 -> State.TAP_2_DOWN
                    State.WAITING_TAP_3 -> State.TAP_3_DOWN
                    else -> State.TAP_1_DOWN
                }

                tapDownTime = now
                isTapDisqualified = false
                maxPointersInTap = 1
                tapDisplay = null
                tapStartCentroid = null
                lastCurrentCentroid = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 3) {
                    if (currentState == State.IDLE) return
                    val geometry = display.read()
                    val previousGeometry = tapDisplay
                    if (previousGeometry != null && previousGeometry != geometry) {
                        resetToIdle()
                        return
                    }
                    if (currentState != State.TAP_1_DOWN && firstTapDisplay != geometry) {
                        tap1Centroid = null
                        tap2Centroid = null
                        firstTapDisplay = null
                        currentState = State.TAP_1_DOWN
                    }
                    tapDisplay = geometry
                    val centroid = updateCentroid(event)
                    if (tapStartCentroid == null) {
                        tapStartCentroid = Point(centroid.x, centroid.y)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTapDisqualified) return
                if (pointerCount == 3) {
                    val geometry = tapDisplay ?: return
                    val c = updateCentroid(event)
                    tapStartCentroid?.let { start ->
                        val moveDist = hypot(c.x - start.x, c.y - start.y)
                        if (moveDist > MAX_SLOP_DP * geometry.density) {
                            // 移动过大，视为滑动或截屏动作，立即废弃本点击
                            isTapDisqualified = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 3 && tapDisplay != null && lastCurrentCentroid == null) {
                    updateCentroid(event)
                }
            }

            MotionEvent.ACTION_UP -> {
                val geometry = tapDisplay
                val currentTapCentroid = lastCurrentCentroid
                if (isTapDisqualified || maxPointersInTap != 3 || currentTapCentroid == null ||
                    geometry == null || now - tapDownTime > MAX_TAP_DURATION_MS ||
                    display.read() != geometry
                ) {
                    resetToIdle()
                    return
                }
                val maxDriftPx = MAX_CLUSTER_DRIFT_DP * geometry.density

                when (currentState) {
                    State.TAP_1_DOWN -> {
                        tap1Centroid = currentTapCentroid
                        firstTapDisplay = geometry
                        tap1UpTime = now
                        currentState = State.WAITING_TAP_2
                    }

                    State.TAP_2_DOWN -> {
                        val t1 = tap1Centroid
                        if (t1 == null || hypot(currentTapCentroid.x - t1.x, currentTapCentroid.y - t1.y) > maxDriftPx) {
                            resetToIdle()
                            return
                        }
                        tap2Centroid = currentTapCentroid
                        tap2UpTime = now
                        currentState = State.WAITING_TAP_3
                    }

                    State.TAP_3_DOWN -> {
                        val t1 = tap1Centroid
                        val t2 = tap2Centroid
                        if (t1 == null || t2 == null ||
                            hypot(currentTapCentroid.x - t1.x, currentTapCentroid.y - t1.y) > maxDriftPx ||
                            hypot(currentTapCentroid.x - t2.x, currentTapCentroid.y - t2.y) > maxDriftPx
                        ) {
                            resetToIdle()
                            return
                        }

                        // 三指三击判定成功！
                        onToggleLockAction()
                        resetToIdle()
                    }

                    else -> {
                        resetToIdle()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetToIdle()
            }
        }
    }

    private fun updateCentroid(event: MotionEvent): Point {
        val x = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
        val y = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
        val point = lastCurrentCentroid
        if (point != null) {
            point.x = x
            point.y = y
            return point
        }
        return Point(x, y).also { lastCurrentCentroid = it }
    }

    fun resetToIdle() {
        currentState = State.IDLE
        tap1Centroid = null
        tap2Centroid = null
        firstTapDisplay = null
        tapDisplay = null
        tapStartCentroid = null
        lastCurrentCentroid = null
        isTapDisqualified = false
        maxPointersInTap = 0
    }
}
