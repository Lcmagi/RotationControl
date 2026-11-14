package io.github.benbaobaoshigemi.rotationcontrol

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Display
import android.view.Surface
import de.robv.android.xposed.XposedBridge

/**
 * 屏幕旋转控制器：
 * 1. 负责反射调用 WindowManagerService 的 freezeRotation / freezeDisplayRotation；
 * 2. 负责同步更新 Settings.System 的 USER_ROTATION 与 ACCELEROMETER_ROTATION；
 * 3. 负责触发系统轻微震动反馈（Haptic feedback）；
 * 4. 支持绝对朝向模式（默认）与相对步进模式。
 */
object RotationController {
    private const val TAG = "LSPosed-Rotation"

    private var appContext: Context? = null
    private var wmsInstance: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val panelOverride = PanelRotationOverride()
    private var displayManager: DisplayManager? = null
    private var listeningForPanelChanges = false
    private val panelListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            restoreRotationAfterPanelChange()
        }
    }

    /**
     * 旋转模式配置：
     * false: 绝对定向模式（默认推荐）。点击下方固定转 180° 朝下，点击右侧固定转 90°。
     * true: 相对步进模式。点击右侧在当前屏幕角度基础上再顺时针累加 +90°。
     */
    var useRelativeRotation: Boolean = false

    fun init(context: Context, wms: Any) {
        appContext = context
        wmsInstance = wms
        if (!listeningForPanelChanges) {
            try {
                displayManager = context.getSystemService(DisplayManager::class.java)
                displayManager?.let {
                    it.registerDisplayListener(panelListener, mainHandler)
                    listeningForPanelChanges = true
                }
            } catch (e: RuntimeException) {
                XposedBridge.log("[$TAG] Failed to observe display changes: ${e.message}")
            }
        }
        XposedBridge.log("[$TAG] RotationController initialized with Context and WindowManagerService")
    }

    /**
     * 执行屏幕旋转
     * @param targetRotation Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
     * @param reason 触发原因与算法决策描述
     */
    fun rotateScreen(targetRotation: Int, reason: String) {
        mainHandler.post {
            try {
                val context = appContext ?: return@post
                if (!canRunGesture(context)) return@post
                // Reconcile first if the display callback is still queued.
                restoreRotationAfterPanelChange()
                val panel = currentPanel()
                val before = readRotationSettings(context)
                val wms = wmsInstance

                val currentRotation = getCurrentRotation()
                val finalRotation = if (useRelativeRotation) {
                    when (targetRotation) {
                        Surface.ROTATION_90 -> (currentRotation + 1) % 4
                        Surface.ROTATION_180 -> (currentRotation + 2) % 4
                        Surface.ROTATION_270 -> (currentRotation + 3) % 4
                        else -> Surface.ROTATION_0
                    }
                } else {
                    targetRotation
                }

                XposedBridge.log(
                    "[$TAG] Intent: Pointing to $finalRotation (current=$currentRotation, reason=$reason)"
                )

                val applied = PanelRotationOverride.Settings(0, finalRotation)
                val wmsSuccess = currentRotation != finalRotation && wms != null &&
                    invokeWmsFreeze(wms, finalRotation)
                writeRotationSettings(context, applied)
                if (panel != null) panelOverride.record(panel, before, applied)
                triggerHapticFeedback(context)

                XposedBridge.log("[$TAG] Rotation execution finished: finalRotation=$finalRotation, wmsSuccess=$wmsSuccess")
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Error during rotateScreen execution: ${t.message}")
            }
        }
    }

    /**
     * 切换系统原生屏幕旋转锁定状态
     */
    fun toggleRotationLock() {
        mainHandler.post {
            try {
                val context = appContext ?: return@post
                if (!canRunGesture(context)) return@post
                restoreRotationAfterPanelChange()
                // The three-tap toggle is an explicit change to system policy.
                panelOverride.clear()

                // 优先通过系统原生内部类 RotationPolicy 切换（与下拉通知栏快捷磁贴 100% 同源）
                var nativeSuccess = false
                try {
                    val policyClass = Class.forName("com.android.internal.view.RotationPolicy")
                    val isLockedMethod = policyClass.getMethod("isRotationLocked", Context::class.java)
                    val setLockedMethod = policyClass.getMethod("setRotationLock", Context::class.java, Boolean::class.javaPrimitiveType)

                    val isLocked = isLockedMethod.invoke(null, context) as Boolean
                    val targetLock = !isLocked
                    setLockedMethod.invoke(null, context, targetLock)
                    triggerHapticFeedback(context, isLock = targetLock)
                    XposedBridge.log("[$TAG] Native RotationPolicy toggled: wasLocked=$isLocked -> targetLock=$targetLock")
                    nativeSuccess = true
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] Native RotationPolicy reflection failed: ${t.message}, using fallback")
                }

                if (!nativeSuccess) {
                    val cr = context.contentResolver
                    val autoRotate = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

                    if (autoRotate) {
                        // 当前为自动旋转 -> 锁定为当前朝向
                        val curRot = getCurrentRotation()
                        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                        Settings.System.putInt(cr, Settings.System.USER_ROTATION, curRot)
                        wmsInstance?.let { invokeWmsFreeze(it, curRot) }
                        triggerHapticFeedback(context, isLock = true)
                        XposedBridge.log("[$TAG] Toggle: Auto-rotate DISABLED -> Rotation LOCKED at $curRot")
                    } else {
                        // 当前为锁定 -> 解锁开启自动旋转
                        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                        wmsInstance?.let { invokeWmsThaw(it) }
                        triggerHapticFeedback(context, isLock = false)
                        XposedBridge.log("[$TAG] Toggle: Rotation UNLOCKED -> Auto-rotate ENABLED")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] toggleRotationLock error: ${t.message}")
            }
        }
    }

    private fun canRunGesture(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == false && power?.isInteractive == true
    }

    private fun currentPanel(): PanelRotationOverride.Panel? {
        val mode = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return null
        // Physical mode dimensions do not rotate with the UI. Refresh rate is irrelevant.
        return PanelRotationOverride.Panel(mode.physicalWidth, mode.physicalHeight)
    }

    private fun readRotationSettings(context: Context): PanelRotationOverride.Settings {
        val resolver = context.contentResolver
        return PanelRotationOverride.Settings(
            Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0),
            Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
        )
    }

    private fun writeRotationSettings(context: Context, settings: PanelRotationOverride.Settings) {
        val resolver = context.contentResolver
        check(Settings.System.putInt(resolver, Settings.System.USER_ROTATION, settings.angle))
        check(Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, settings.autoRotate))
    }

    private fun restoreRotationAfterPanelChange() {
        if (!panelOverride.isActive) return
        try {
            val context = appContext ?: return
            val panel = currentPanel() ?: return
            val previous = panelOverride.onPanelChanged(panel) { readRotationSettings(context) } ?: return
            val wms = wmsInstance
            if (wms != null) {
                if (previous.autoRotate == 1) invokeWmsThaw(wms) else invokeWmsFreeze(wms, previous.angle)
            }
            writeRotationSettings(context, previous)
            XposedBridge.log("[$TAG] Restored rotation settings after display size change")
        } catch (e: RuntimeException) {
            XposedBridge.log("[$TAG] Could not restore rotation settings: ${e.message}")
        }
    }

    private fun getCurrentRotation(): Int {
        val context = appContext
        if (context != null) {
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return display.rotation
                }
            } catch (_: Throwable) {}

            try {
                return Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_0
                )
            } catch (_: Throwable) {}
        }
        return Surface.ROTATION_0
    }

    private fun invokeWmsFreeze(wms: Any, rotation: Int): Boolean {
        var success = false

        // 尝试 freezeRotation(int rotation, String caller)
        try {
            val method2 = wms.javaClass.methods.firstOrNull {
                it.name == "freezeRotation" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == String::class.java
            }
            if (method2 != null) {
                method2.invoke(wms, rotation, "TwoFingerRotationHook")
                success = true
                XposedBridge.log("[$TAG] Invoked WMS.freezeRotation(rotation, caller)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] WMS.freezeRotation(int, String) failed: ${t.message}")
        }

        // 尝试 freezeRotation(int rotation)
        if (!success) {
            try {
                val method1 = wms.javaClass.methods.firstOrNull {
                    it.name == "freezeRotation" && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method1 != null) {
                    method1.invoke(wms, rotation)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeRotation(rotation)")
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] WMS.freezeRotation(int) failed: ${t.message}")
            }
        }

        // 尝试 freezeDisplayRotation(int displayId, int rotation, String caller)
        try {
            val methodDisplay = wms.javaClass.methods.firstOrNull {
                it.name == "freezeDisplayRotation"
            }
            if (methodDisplay != null) {
                if (methodDisplay.parameterTypes.size == 3) {
                    methodDisplay.invoke(wms, 0, rotation, "TwoFingerRotationHook")
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeDisplayRotation(0, rotation, caller)")
                } else if (methodDisplay.parameterTypes.size == 2) {
                    methodDisplay.invoke(wms, 0, rotation)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeDisplayRotation(0, rotation)")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] WMS.freezeDisplayRotation failed: ${t.message}")
        }

        return success
    }

    private fun invokeWmsThaw(wms: Any): Boolean {
        var success = false
        try {
            val method = wms.javaClass.methods.firstOrNull {
                it.name == "thawRotation" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            if (method != null) {
                method.invoke(wms, "TwoFingerRotationHook")
                success = true
                XposedBridge.log("[$TAG] Invoked WMS.thawRotation(caller)")
            }
        } catch (_: Throwable) {}

        if (!success) {
            try {
                val method = wms.javaClass.methods.firstOrNull { it.name == "thawRotation" && it.parameterTypes.isEmpty() }
                if (method != null) {
                    method.invoke(wms)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawRotation()")
                }
            } catch (_: Throwable) {}
        }

        try {
            val methodDisplay = wms.javaClass.methods.firstOrNull { it.name == "thawDisplayRotation" }
            if (methodDisplay != null) {
                if (methodDisplay.parameterTypes.size == 2) {
                    methodDisplay.invoke(wms, 0, "TwoFingerRotationHook")
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawDisplayRotation(0, caller)")
                } else if (methodDisplay.parameterTypes.size == 1) {
                    methodDisplay.invoke(wms, 0)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawDisplayRotation(0)")
                }
            }
        } catch (_: Throwable) {}

        return success
    }

    private fun triggerHapticFeedback(context: Context, isLock: Boolean = true) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isLock) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isLock) 40L else 70L)
                }
            }
        } catch (_: Throwable) {}
    }
}
