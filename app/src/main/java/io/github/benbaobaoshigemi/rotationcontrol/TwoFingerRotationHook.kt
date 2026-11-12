package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.hardware.display.DisplayManager
import android.content.res.Resources
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 挂钩 system_server：
 * 通道 1：WindowManagerService.systemReady 注册全局 PointerEventListener
 * 通道 2：InputManagerService.filterInputEvent 原生触摸事件拦截（双保险）
 */
object TwoFingerRotationHook {
    private const val TAG = "LSPosed-Rotation"
    private var isWmsListenerRegistered = false
    @Volatile private var systemContext: Context? = null
    private var displayReadFailureLogged = false

    private val gestureInput = GestureInputDispatcher(
        loadDisplay = ::readGestureDisplay,
        nowMillis = SystemClock::uptimeMillis,
        enabledGestures = {
            var enabled = 0
            if (ConfigManager.isTwoFingerEnabled) enabled = enabled or GestureInputDispatcher.TWO_FINGER
            if (ConfigManager.isBottomSwipeEnabled) enabled = enabled or GestureInputDispatcher.BOTTOM_SWIPE
            if (ConfigManager.isTripleTapEnabled) enabled = enabled or GestureInputDispatcher.TRIPLE_TAP
            enabled
        },
        onRotate = { rotation, reason -> RotationController.rotateScreen(rotation, reason) },
        onToggleLock = { RotationController.toggleRotationLock() }
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        XposedBridge.log("[$TAG] Attaching TwoFingerRotationHook to system_server")

        // ==========================================
        // 通道 1：WindowManagerService (WMS)
        // ==========================================
        try {
            val wmsClass = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", lpparam.classLoader)

            // 严格在 systemReady 之后注册（确保 DisplayContent(0) 及 InputMonitor 完全创建就绪）
            XposedHelpers.findAndHookMethod(
                wmsClass,
                "systemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("[$TAG] WindowManagerService.systemReady hooked -> Registering PointerEventListener")
                        initWms(param.thisObject, lpparam.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook WindowManagerService: ${t.message}")
        }

        // ==========================================
        // 通道 2：InputManagerService (IMS) 双保险
        // ==========================================
        try {
            val imsClass = XposedHelpers.findClass("com.android.server.input.InputManagerService", lpparam.classLoader)

            // 1. 获取 Context 并确保 RotationController 初始化
            XposedHelpers.findAndHookMethod(
                imsClass,
                "systemRunning",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        if (context != null) {
                            systemContext = context
                            XposedBridge.log("[$TAG] Obtained system Context from IMS.systemRunning")
                            if (!isWmsListenerRegistered) {
                                tryInitWithServiceManager(context, lpparam.classLoader)
                            }
                        }
                    }
                }
            )

            // 2. 挂钩 filterInputEvent 直接接收原始触摸流
            XposedHelpers.findAndHookMethod(
                imsClass,
                "filterInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? MotionEvent ?: return
                        gestureInput.onPointerEvent(event, "IMS-Filter")
                    }
                }
            )
            XposedBridge.log("[$TAG] Successfully hooked filterInputEvent in InputManagerService as dual-channel listener")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook InputManagerService: ${t.message}")
        }
    }

    @Synchronized
    fun initWms(wmsInstance: Any, classLoader: ClassLoader, fallbackContext: Context? = null) {
        if (isWmsListenerRegistered) return
        try {
            val context = (XposedHelpers.getObjectField(wmsInstance, "mContext") as? Context) ?: fallbackContext
            if (context != null) {
                systemContext = context
                RotationController.init(context, wmsInstance)
                ConfigManager.initInSystemServer(context)
            }

            val success = registerPointerEventListener(wmsInstance, classLoader)
            if (success) {
                isWmsListenerRegistered = true
                XposedBridge.log("[$TAG] TwoFingerRotationHook WMS PointerEventListener registered successfully!")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to initialize WMS: ${t.message}")
        }
    }

    private fun tryInitWithServiceManager(context: Context, classLoader: ClassLoader) {
        try {
            val smClass = XposedHelpers.findClass("android.os.ServiceManager", classLoader)
            val wmsBinder = XposedHelpers.callStaticMethod(smClass, "getService", Context.WINDOW_SERVICE)
            if (wmsBinder != null) {
                initWms(wmsBinder, classLoader, context)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] tryInitWithServiceManager failed: ${t.message}")
        }
    }

    private fun registerPointerEventListener(wmsInstance: Any, classLoader: ClassLoader): Boolean {
        try {
            val listenerClass = try {
                XposedHelpers.findClass("android.view.WindowManagerPolicyConstants\$PointerEventListener", classLoader)
            } catch (e: Throwable) {
                try {
                    Class.forName("android.view.WindowManagerPolicyConstants\$PointerEventListener")
                } catch (e2: Throwable) {
                    XposedHelpers.findClass("com.android.server.wm.WindowManagerPolicyConstants\$PointerEventListener", classLoader)
                }
            }

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader ?: classLoader,
                arrayOf(listenerClass),
                object : InvocationHandler {
                    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                        when (method.name) {
                            "onPointerEvent" -> {
                                val event = args?.getOrNull(0) as? MotionEvent
                                if (event != null) {
                                    gestureInput.onPointerEvent(event, "WMS-PointerListener")
                                }
                                return null
                            }
                            "equals" -> return proxy === args?.getOrNull(0)
                            "hashCode" -> return System.identityHashCode(proxy)
                            "toString" -> return "TwoFingerPointerEventListener@${Integer.toHexString(System.identityHashCode(proxy))}"
                        }
                        if (method.returnType == Boolean::class.javaPrimitiveType) return false
                        if (method.returnType == Int::class.javaPrimitiveType) return 0
                        return null
                    }
                }
            )

            val regMethod = wmsInstance.javaClass.methods.firstOrNull {
                it.name == "registerPointerEventListener" && it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0].isAssignableFrom(listenerClass)
            } ?: wmsInstance.javaClass.methods.firstOrNull { it.name == "registerPointerEventListener" }

            if (regMethod != null) {
                if (regMethod.parameterTypes.size == 2) {
                    regMethod.invoke(wmsInstance, proxy, 0)
                } else {
                    regMethod.invoke(wmsInstance, proxy)
                }
                XposedBridge.log("[$TAG] registerPointerEventListener invoked successfully via reflection: $regMethod")
                return true
            } else {
                XposedBridge.log("[$TAG] registerPointerEventListener method not found on WMS")
                return false
            }
        } catch (t: Throwable) {
            val realCause = if (t is java.lang.reflect.InvocationTargetException) t.targetException ?: t else t
            XposedBridge.log("[$TAG] Failed to registerPointerEventListener: ${realCause.javaClass.name}: ${realCause.message}\n${Log.getStackTraceString(realCause)}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun readGestureDisplay(): GestureDisplaySnapshot {
        val context = systemContext
        val resources = context?.resources ?: Resources.getSystem()
        val density = resources.displayMetrics.density
        if (context != null) {
            try {
                val manager = context.getSystemService(DisplayManager::class.java)
                val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val metrics = DisplayMetrics()
                    display.getRealMetrics(metrics)
                    return GestureDisplaySnapshot(
                        metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(),
                        display.rotation, density
                    )
                }
            } catch (e: RuntimeException) {
                if (!displayReadFailureLogged) {
                    displayReadFailureLogged = true
                    XposedBridge.log("[$TAG] Failed to read gesture display: ${e.message}")
                }
            }
        }
        val metrics = Resources.getSystem().displayMetrics
        return GestureDisplaySnapshot(
            metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(),
            Surface.ROTATION_0, density
        )
    }
}
