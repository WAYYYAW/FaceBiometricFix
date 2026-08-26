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

        hookIsAtLeastStrength(lpparam)
        hookGetCurrentStrength(lpparam)
        hookStrengthToProperty(lpparam)
    }

    /**
     * 所有强度校验直接放行。
     * 覆盖调用点：PreAuthInfo / AuthSession / BiometricService.isStrongBiometric /
     * resetLockoutTimeBound / getSupportedModalities / Utils.isStrongBiometric 等
     */
    private fun hookIsAtLeastStrength(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.biometrics.Utils",
                lpparam.classLoader,
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                XC_MethodReplacement.returnConstant(true)
            )
            log("OK: Utils.isAtLeastStrength -> true")
        }.onFailure { log("FAIL isAtLeastStrength: ${it.stackTraceToString()}") }
    }

    /**
     * 对外报告的当前强度：4095(厂商掩码) 修正为 15(Class3)。
     * 指纹本来就是 15，保持不变；其他值原样返回，避免误伤。
     *
     * 注意：必须在 afterHookedMethod 中改写结果（此时原方法已执行完，
     * param.result 才有值）。绝不能用 XC_MethodReplacement 读 result——
     * Replacement 模式下原方法不执行，result 恒为 null（上一版的崩溃根源）。
     */
    private fun hookGetCurrentStrength(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.biometrics.BiometricSensor",
                lpparam.classLoader,
                "getCurrentStrength",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val orig = param.result
                            if (orig is Int && orig >= OEM_STRENGTH_MASK) {
                                param.result = STRONG_AUTHENTICATORS
                            }
                        } catch (t: Throwable) {
                            // 系统关键路径上绝不让 hook 本身抛异常
                            log("getCurrentStrength hook error: $t")
                        }
                    }
                }
            )
            log("OK: BiometricSensor.getCurrentStrength hooked (>=4095 -> $STRONG_AUTHENTICATORS)")
        }.onFailure { log("FAIL getCurrentStrength: ${it.stackTraceToString()}") }
    }

    /**
     * 属性强度映射强制为 STRONG(2)：ColorOS 把 4095 映射为 CONVENIENCE(0)
     */
    private fun hookStrengthToProperty(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.biometrics.Utils",
                lpparam.classLoader,
                "authenticatorStrengthToPropertyStrength",
                Int::class.javaPrimitiveType,
                XC_MethodReplacement.returnConstant(PROPERTY_STRONG)
            )
            log("OK: authenticatorStrengthToPropertyStrength -> $PROPERTY_STRONG")
        }.onFailure { log("FAIL authenticatorStrengthToPropertyStrength: ${it.stackTraceToString()}") }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"
        private const val ANDROID_PACKAGE = "android"

        /** 厂商私有强度掩码（人脸传感器上报值） */
        private const val OEM_STRENGTH_MASK = 4095

        /** Authenticators.BIOMETRIC_STRONG */
        private const val STRONG_AUTHENTICATORS = 15

        /** SensorProperties.STRENGTH_STRONG */
        private const val PROPERTY_STRONG = 2
    }
}
