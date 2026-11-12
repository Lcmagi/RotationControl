package io.github.benbaobaoshigemi.rotationcontrol

import android.view.MotionEvent
import android.view.Surface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕旋转手势检测器：
 * 第一性物理原则：“手指连线应该平行于目标的显示顶部”。
 * 
 * 核心算法：
 * 1. 无论屏幕当前显示角度（0°、90°、180°、270°）为何，通过显示旋转矩阵将触控采样点
 *    逆变换还原至【平板机身绝对物理硬件坐标系】；
 * 2. 两次点击共 4 个触控点，计算物理均值 (X_phys_avg, Y_phys_avg)；
 * 3. 计算物理双指跨度 DeltaX_phys 与 DeltaY_phys：
 *    - DeltaX_phys > DeltaY_phys（手指连线平行于机身短边：顶/底边）：
 *      - 靠近物理底边（充电口端） -> 物理底边成为目标显示顶部 (ROTATION_180)
 *      - 靠近物理顶边（摄像头端） -> 物理顶边成为目标显示顶部 (ROTATION_0)
 *    - DeltaY_phys > DeltaX_phys（手指连线平行于机身长边：左/右边）：
 *      - 靠近物理右边（音量键端） -> 物理右边成为目标显示顶部 (ROTATION_90)
 *      - 靠近物理左边（磁吸端）   -> 物理左边成为目标显示顶部 (ROTATION_270)
 */
internal class ScreenRotationGestureDetector(
    private val display: GestureDisplay,
    private val onRotateAction: (targetRotation: Int, reason: String) -> Unit
) {
    companion object {
        // 阈值定义（严谨过滤长按与普通滑动，基于物理像素）
        private const val MAX_TAP_DURATION_MS = 400L    // 单次敲击时长上限，超过视为长按/按压，丢弃
        private const val MAX_DOUBLE_TAP_GAP_MS = 450L  // 两次点击之间的最大间隔时间，超过重置
        private const val MAX_SLOP_DP = 80f             // 单次点击中允许的最大位移（dp），防滑动/捏合
        private const val MAX_DRIFT_DP = 180f           // 两次点击中心点之间的最大漂移（dp），保证在同一物理区域
    }

    private enum class State {
        IDLE,
        IN_TAP_1,
        WAITING_FOR_TAP_2,
        IN_TAP_2
    }

    private class Point(var x: Float, var y: Float)

    private data class TapRecord(
        val pA_phys: Point,
        val pB_phys: Point,
        val centroid_phys: Point,
        val deltaX_phys: Float,
        val deltaY_phys: Float,
        val timeUp: Long
    )

    private var currentState = State.IDLE

    // 当前敲击过程中的状态跟踪
    private var tapDownTime: Long = 0L
    private var maxPointersInTap: Int = 0
    private var firstDownX = 0f
    private var firstDownY = 0f
    private var tapDisplay: GestureDisplaySnapshot? = null
    private var firstTapDisplay: GestureDisplaySnapshot? = null

    // 双指在机身绝对物理坐标系下的记录
    private var p0StartPhys: Point? = null
    private var p1StartPhys: Point? = null
    private var p0CurrentPhys: Point? = null
    private var p1CurrentPhys: Point? = null

    // 第一次敲击物理记录
    private var tap1Record: TapRecord? = null

    fun onPointerEvent(event: MotionEvent, actionMasked: Int, pointerCount: Int, now: Long) {
        // 更新单次敲击中出现过的最大指针数
        if (pointerCount > maxPointersInTap) {
            maxPointersInTap = pointerCount
        }

        // 若检测到 3 指及以上，立即废弃本手势（避免干扰系统三指截屏/分屏）
        if (pointerCount >= 3) {
            resetToIdle()
            return
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 如果处于等待第二次点击状态，检查间隔是否超时
                if (currentState == State.WAITING_FOR_TAP_2) {
                    val gap = now - (tap1Record?.timeUp ?: 0L)
                    if (gap > MAX_DOUBLE_TAP_GAP_MS) {
                        currentState = State.IDLE
                        tap1Record = null
                    }
                }

                if (currentState == State.IDLE) {
                    currentState = State.IN_TAP_1
                } else if (currentState == State.WAITING_FOR_TAP_2) {
                    currentState = State.IN_TAP_2
                }

                tapDownTime = now
                maxPointersInTap = 1

                firstDownX = event.getX(0)
                firstDownY = event.getY(0)
                tapDisplay = null
                p0StartPhys = null
                p1StartPhys = null
                p0CurrentPhys = null
                p1CurrentPhys = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount != 2 || currentState == State.IDLE) return

                val geometry = display.read()
                val previousGeometry = tapDisplay
                if (previousGeometry != null && previousGeometry != geometry) {
                    resetToIdle()
                    return
                }
                if (currentState == State.IN_TAP_2 && firstTapDisplay != geometry) {
                    // A fold/rotation between taps starts a new pair.
                    tap1Record = null
                    firstTapDisplay = null
                    currentState = State.IN_TAP_1
                }
                tapDisplay = geometry
                if (p0StartPhys == null) {
                    p0StartPhys = physicalPoint(firstDownX, firstDownY, geometry)
                }
                val p1Start = physicalPoint(event.getX(1), event.getY(1), geometry)
                p1StartPhys = p1Start
                p0CurrentPhys = physicalPoint(event.getX(0), event.getY(0), geometry)
                p1CurrentPhys = Point(p1Start.x, p1Start.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount != 2) return
                val geometry = tapDisplay ?: return
                val p0 = p0CurrentPhys ?: return
                val p1 = p1CurrentPhys ?: return
                setPhysicalPoint(p0, event.getX(0), event.getY(0), geometry)
                setPhysicalPoint(p1, event.getX(1), event.getY(1), geometry)

                val maxSlopPx = MAX_SLOP_DP * geometry.density
                val start0 = p0StartPhys
                val start1 = p1StartPhys
                if ((start0 != null && hypot(p0.x - start0.x, p0.y - start0.y) > maxSlopPx) ||
                    (start1 != null && hypot(p1.x - start1.x, p1.y - start1.y) > maxSlopPx)
                ) {
                    resetToIdle()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount != 2) return
                val geometry = tapDisplay ?: return
                val p0 = p0CurrentPhys ?: return
                val p1 = p1CurrentPhys ?: return
                setPhysicalPoint(p0, event.getX(0), event.getY(0), geometry)
                setPhysicalPoint(p1, event.getX(1), event.getY(1), geometry)
            }

            MotionEvent.ACTION_UP -> {
                // 全部手指离开屏幕，验证本单次点击是否合规
                val geometry = tapDisplay
                val pA = p0CurrentPhys
                val pB = p1CurrentPhys
                if (geometry == null || pA == null || pB == null || maxPointersInTap != 2 ||
                    now - tapDownTime > MAX_TAP_DURATION_MS || display.read() != geometry
                ) {
                    resetToIdle()
                    return
                }

                val centroid = Point((pA.x + pB.x) / 2f, (pA.y + pB.y) / 2f)
                val deltaX = abs(pA.x - pB.x)
                val deltaY = abs(pA.y - pB.y)

                val tapRecord = TapRecord(
                    pA_phys = pA,
                    pB_phys = pB,
                    centroid_phys = centroid,
                    deltaX_phys = deltaX,
                    deltaY_phys = deltaY,
                    timeUp = now
                )

                if (currentState == State.IN_TAP_1) {
                    // 第一次双指敲击顺利通过，进入等待第二次敲击阶段
                    tap1Record = tapRecord
                    firstTapDisplay = geometry
                    currentState = State.WAITING_FOR_TAP_2
                } else if (currentState == State.IN_TAP_2) {
                    // 第二次双指敲击完成！检查两次点击之间的中心位置物理漂移
                    val t1 = tap1Record
                    if (t1 == null) {
                        resetToIdle()
                        return
                    }

                    val drift = hypot(centroid.x - t1.centroid_phys.x, centroid.y - t1.centroid_phys.y)
                    if (drift > MAX_DRIFT_DP * geometry.density) {
                        resetToIdle()
                        return
                    }

                    // 校验全部通过：执行第一性物理指向决策！
                    processGestureResult(t1, tapRecord, geometry.width, geometry.height)
                    resetToIdle()
                } else {
                    resetToIdle()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetToIdle()
            }
        }
    }

    /**
     * 第一性物理决策：
     * “手指连线应该平行于目标的显示顶部”
     */
    private fun processGestureResult(t1: TapRecord, t2: TapRecord, wDisp: Float, hDisp: Float) {
        // 1. 机身绝对物理尺寸（平板物理短边 W_phys，长边 H_phys）
        val wPhys = min(wDisp, hDisp)
        val hPhys = max(wDisp, hDisp)
        val centerPhysX = wPhys / 2.0f
        val centerPhysY = hPhys / 2.0f

        // 2. 计算两点，共四次点击在机身绝对物理坐标系下的平均坐标
        val avgPhysX = (t1.pA_phys.x + t1.pB_phys.x + t2.pA_phys.x + t2.pB_phys.x) / 4.0f
        val avgPhysY = (t1.pA_phys.y + t1.pB_phys.y + t2.pA_phys.y + t2.pB_phys.y) / 4.0f

        // 3. 计算双指在机身绝对物理坐标系下的平均跨度（连线方向向量）
        val meanDeltaXPhys = (t1.deltaX_phys + t2.deltaX_phys) / 2.0f
        val meanDeltaYPhys = (t1.deltaY_phys + t2.deltaY_phys) / 2.0f

        // 4. 指向判定：
        if (meanDeltaXPhys > meanDeltaYPhys) {
            // 手指连线平行于机身短边（水平连线） -> 目标显示顶部在机身顶端或底端
            val isBottom = avgPhysY > centerPhysY
            val targetRotation = if (isBottom) Surface.ROTATION_180 else Surface.ROTATION_0
            val reason = if (isBottom) {
                "Fingers parallel to Bottom Edge (avgPhysY=${avgPhysY.toInt()} > ${centerPhysY.toInt()}) -> Physical Bottom becomes display TOP (ROTATION_180)"
            } else {
                "Fingers parallel to Top Edge (avgPhysY=${avgPhysY.toInt()} <= ${centerPhysY.toInt()}) -> Physical Top becomes display TOP (ROTATION_0)"
            }
            onRotateAction(targetRotation, reason)
        } else {
            // 手指连线平行于机身长边（垂直连线） -> 目标显示顶部在机身右侧边或左侧边
            val isRight = avgPhysX > centerPhysX
            val targetRotation = if (isRight) Surface.ROTATION_90 else Surface.ROTATION_270
            val reason = if (isRight) {
                "Fingers parallel to Right Edge (avgPhysX=${avgPhysX.toInt()} > ${centerPhysX.toInt()}) -> Physical Right becomes display TOP (ROTATION_90)"
            } else {
                "Fingers parallel to Left Edge (avgPhysX=${avgPhysX.toInt()} <= ${centerPhysX.toInt()}) -> Physical Left becomes display TOP (ROTATION_270)"
            }
            onRotateAction(targetRotation, reason)
        }
    }

    /**
     * 将当前显示旋转下的触控点 (x_disp, y_disp) 逆变换还原回【平板机身绝对物理硬件坐标系】 (x_phys, y_phys)
     */
    private fun physicalPoint(x: Float, y: Float, geometry: GestureDisplaySnapshot): Point =
        Point(0f, 0f).also { setPhysicalPoint(it, x, y, geometry) }

    private fun setPhysicalPoint(point: Point, x: Float, y: Float, geometry: GestureDisplaySnapshot) {
        when (geometry.rotation) {
            Surface.ROTATION_90 -> {
                point.x = geometry.height - y
                point.y = x
            }
            Surface.ROTATION_180 -> {
                point.x = geometry.width - x
                point.y = geometry.height - y
            }
            Surface.ROTATION_270 -> {
                point.x = y
                point.y = geometry.width - x
            }
            else -> {
                point.x = x
                point.y = y
            }
        }
    }

    fun resetToIdle() {
        currentState = State.IDLE
        tap1Record = null
        firstTapDisplay = null
        tapDisplay = null
        p0StartPhys = null
        p1StartPhys = null
        p0CurrentPhys = null
        p1CurrentPhys = null
        maxPointersInTap = 0
    }
}
