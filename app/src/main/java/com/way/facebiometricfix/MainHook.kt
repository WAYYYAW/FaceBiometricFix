package com.way.facebiometricfix

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private fun log(msg: String) {
        XposedBridge.log("[${TAG}] $msg")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ANDROID_PACKAGE) return

        log("hooking biometrics in system_server ...")

        hookAppPreAuthContext(lpparam)
        hookAppFaceStrengthCheck(lpparam)
    }

    /**
     * 在 PreAuthInfo 计算普通应用资格期间记录 opPackageName。
     *
     * isAtLeastStrength() 在 system_server 内部执行时无法可靠取得原始调用者 UID，
     * 所以必须从 PreAuthInfo 的参数传递调用上下文。
     */
    private fun hookAppPreAuthContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.biometrics.PreAuthInfo",
                lpparam.classLoader,
                "getStatusForBiometricAuthenticator",
                XposedHelpers.findClass("android.app.admin.DevicePolicyManager", lpparam.classLoader),
                XposedHelpers.findClass("com.android.server.biometrics.BiometricService\$SettingObserver", lpparam.classLoader),
                XposedHelpers.findClass("com.android.server.biometrics.BiometricSensor", lpparam.classLoader),
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                XposedHelpers.findClass("com.android.server.biometrics.BiometricCameraManager", lpparam.classLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[4] as? String
                        if (packageName != null && packageName != SYSTEM_UI_PACKAGE) {
                            APP_PRE_AUTH_PACKAGE.set(packageName)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        APP_PRE_AUTH_PACKAGE.remove()
                    }
                }
            )
            log("OK: PreAuthInfo app context hook")
        }.onFailure { log("FAIL PreAuthInfo context hook: ${it.stackTraceToString()}") }
    }

    /** 仅在普通 App 的预检查调用链中放行 4095 人脸强度。 */
    private fun hookAppFaceStrengthCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.biometrics.Utils",
                lpparam.classLoader,
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val packageName = APP_PRE_AUTH_PACKAGE.get()
                        val sensorStrength = param.args[0] as? Int ?: return false
                        val requestedStrength = param.args[1] as? Int ?: return false
                        if (packageName != null &&
                            packageName != CODEBOOK_PACKAGE &&
                            sensorStrength == OEM_STRENGTH_MASK &&
                            (requestedStrength == STRONG_AUTHENTICATORS ||
                                requestedStrength == WEAK_AUTHENTICATORS)
                        ) {
                            return true
                        }

                        // Keep the exact ColorOS implementation for all other paths,
                        // especially SystemUI and provider/lockout operations.
                        val maskedStrength = sensorStrength and 32767
                        if (((requestedStrength.inv()) and maskedStrength) != 0) return false
                        var candidate = 1
                        while (candidate <= requestedStrength) {
                            if (candidate == maskedStrength) return true
                            candidate = (candidate shl 1) or 1
                        }
                        return false
                    }
                }
            )
            log("OK: app-only 4095 strength check")
        }.onFailure { log("FAIL app strength hook: ${it.stackTraceToString()}") }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"
        private const val ANDROID_PACKAGE = "android"

        /** 厂商私有强度掩码（人脸传感器上报值） */
        private const val OEM_STRENGTH_MASK = 4095
        private const val STRONG_AUTHENTICATORS = 15
        private const val WEAK_AUTHENTICATORS = 255
        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

        private const val STATUS_OK = 1
        private const val STATUS_INSUFFICIENT_STRENGTH = 4
        private const val FACE_MODALITY = 8
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"
    }
}
