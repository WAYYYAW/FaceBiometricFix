package com.way.facebiometricfix

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class MainHook : XposedModule() {

    private fun logInfo(msg: String) {
        log(Log.INFO, TAG, msg)
    }

    private fun logError(msg: String, tr: Throwable) {
        log(Log.ERROR, TAG, msg, tr)
    }

    private val preAuthContextHooker = XposedInterface.Hooker { chain ->
        val packageName = runCatching { chain.getArg(4) as? String }.getOrNull()
        if (packageName != null && packageName != SYSTEM_UI_PACKAGE) {
            APP_PRE_AUTH_PACKAGE.set(packageName)
        }
        try {
            chain.proceed()
        } finally {
            APP_PRE_AUTH_PACKAGE.remove()
        }
    }

    private val faceStrengthCheckHooker = XposedInterface.Hooker { chain ->
        val packageName = APP_PRE_AUTH_PACKAGE.get()
        val sensorStrength = runCatching { chain.getArg(0) as? Int }.getOrNull() ?: return@Hooker false
        val requestedStrength = runCatching { chain.getArg(1) as? Int }.getOrNull() ?: return@Hooker false
        if (packageName != null && !Config.isEnabled(packageName) &&
            (sensorStrength == OEM_STRENGTH_MASK || sensorStrength == WEAK_FACE_STRENGTH)
        ) {
            logInfo("skip by config: $packageName")
        }
        if (packageName != null &&
            Config.isEnabled(packageName) &&
            packageName != CODEBOOK_PACKAGE &&
            (sensorStrength == OEM_STRENGTH_MASK ||
                sensorStrength == WEAK_FACE_STRENGTH) &&
            (requestedStrength == STRONG_AUTHENTICATORS ||
                requestedStrength == WEAK_AUTHENTICATORS)
        ) {
            return@Hooker true
        }

        // Keep the exact ColorOS implementation for all other paths,
        // especially SystemUI and provider/lockout operations.
        val maskedStrength = sensorStrength and 32767
        if (((requestedStrength.inv()) and maskedStrength) != 0) return@Hooker false
        var candidate = 1
        while (candidate <= requestedStrength) {
            if (candidate == maskedStrength) return@Hooker true
            candidate = (candidate shl 1) or 1
        }
        false
    }

    /**
     * 是否允许人脸满足强认证。
     *
     * 认证成功后，系统用 isStrongBiometric() 决定是否保留人脸 HAL 生成的
     * HardwareAuthToken；判 false 时 token 被丢弃（日志 Dropping authToken for
     * non-strong biometric），Keystore 里的生物识别绑定密钥也就无法解锁。
     *
     * 这里只影响该判定，且仅针对 FACE 模态；指纹等其它模态不受影响。
     */
    private val strongBiometricHooker = XposedInterface.Hooker { chain ->
        val original = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
        if (original) return@Hooker true

        if (!Config.isFaceStrongAllowed()) return@Hooker false

        val sensorId = runCatching { chain.getArg(0) as? Int }.getOrNull()
            ?: return@Hooker false
        if (FACE_SENSOR_ID != sensorId) return@Hooker false

        val current = currentAuthSession(chain.getThisObject() ?: return@Hooker false)
            ?: return@Hooker false

        // 先看按应用开关：被关掉的应用完全走原生，不做任何接管。
        // 放在 CryptoObject 判定之前，保证"不勾选即无人脸"这条约束始终成立。
        val packageName = runCatching { current.second }.getOrNull()
        if (packageName != null && !Config.isEnabled(packageName)) {
            logInfo("skip by config: $packageName")
            return@Hooker false
        }

        // 再限定只对携带 CryptoObject 的请求放行：这类请求的 per-op 令牌由系统在
        // KeyMint 会话内签发，放行后才拿得到。只是拿"认证成功"结果、自己直连
        // Keystore 的应用（银行类多为此）不吃时间级令牌，放行只会让它们以为可用、
        // 实际解锁失败，因此一并保持原生。
        val operationId = runCatching { current.first }.getOrNull() ?: 0L
        if (operationId == 0L) return@Hooker false

        logInfo("treat face as strong: sensorId=$sensorId")
        true
    }

    /**
     * 从 BiometricService 上取当前 AuthSession 的 (mOperationId, mOpPackageName)。
     *
     * isStrongBiometric() 由 handleAuthenticationSucceeded() 调用，二者同属
     * BiometricService，后者持有字段 mAuthSession。取不到时返回 null，
     * 调用方一律不放行。
     */
    private fun currentAuthSession(service: Any): Pair<Long, String?>? = runCatching {
        val session = service.javaClass
            .getDeclaredField("mAuthSession")
            .apply { isAccessible = true }
            .get(service) ?: return@runCatching null

        val operationId = session.javaClass
            .getDeclaredField("mOperationId")
            .apply { isAccessible = true }
            .getLong(session)

        val packageName = session.javaClass
            .getDeclaredField("mOpPackageName")
            .apply { isAccessible = true }
            .get(session) as? String

        operationId to packageName
    }.getOrElse {
        logError("currentAuthSession failed", it)
        null
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        logInfo("hooking biometrics in system_server ...")
        Config.attach()
        installHooks(param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        APP_PRE_AUTH_PACKAGE.remove()
        Config.attach()
        logInfo("Hot reloading biometrics hooks in system_server ...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        var replacedPreAuth = false
        var replacedStrength = false
        var replacedStrong = false
        var targetClassLoader: ClassLoader? = null

        for (oldHandle in param.oldHookHandles) {
            if (targetClassLoader == null) {
                targetClassLoader = oldHandle.executable.declaringClass.classLoader
            }
            when (oldHandle.id) {
                HOOK_ID_PRE_AUTH_CONTEXT -> {
                    runCatching {
                        oldHandle.replaceHook(preAuthContextHooker)
                        replacedPreAuth = true
                        logInfo("OK: hot-replaced PreAuthInfo app context hook")
                    }.onFailure {
                        logError("FAIL hot-replace PreAuthInfo hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }
                HOOK_ID_FACE_STRENGTH_CHECK -> {
                    runCatching {
                        oldHandle.replaceHook(faceStrengthCheckHooker)
                        replacedStrength = true
                        logInfo("OK: hot-replaced app-only 4095 strength check")
                    }.onFailure {
                        logError("FAIL hot-replace strength hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }
                HOOK_ID_STRONG_BIOMETRIC -> {
                    runCatching {
                        oldHandle.replaceHook(strongBiometricHooker)
                        replacedStrong = true
                        logInfo("OK: hot-replaced strong biometric hook")
                    }.onFailure {
                        logError("FAIL hot-replace strong biometric hook", it)
                        runCatching { oldHandle.unhook() }
                    }
                }
                else -> {
                    runCatching { oldHandle.unhook() }
                        .onFailure { logError("FAIL unhook old handle ${oldHandle.id}", it) }
                }
            }
        }

        if (!replacedPreAuth || !replacedStrength || !replacedStrong) {
            val classLoader = targetClassLoader ?: Thread.currentThread().contextClassLoader
            if (classLoader != null) {
                if (!replacedPreAuth) hookAppPreAuthContext(classLoader)
                if (!replacedStrength) hookAppFaceStrengthCheck(classLoader)
                if (!replacedStrong) hookStrongBiometric(classLoader)
            } else {
                log(Log.WARN, TAG, "Hot reload fallback skipped: system_server ClassLoader unavailable")
            }
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        hookAppPreAuthContext(classLoader)
        hookAppFaceStrengthCheck(classLoader)
        hookStrongBiometric(classLoader)
    }

    /**
     * 在 PreAuthInfo 计算普通应用资格期间记录 opPackageName。
     *
     * isAtLeastStrength() 在 system_server 内部执行时无法可靠取得原始调用者 UID，
     * 所以必须从 PreAuthInfo 的参数传递调用上下文。
     */
    private fun hookAppPreAuthContext(classLoader: ClassLoader) {
        runCatching {
            val preAuthInfoClass = Class.forName(
                "com.android.server.biometrics.PreAuthInfo",
                false,
                classLoader
            )
            val method = preAuthInfoClass.getDeclaredMethod(
                "getStatusForBiometricAuthenticator",
                Class.forName("android.app.admin.DevicePolicyManager", false, classLoader),
                Class.forName("com.android.server.biometrics.BiometricService\$SettingObserver", false, classLoader),
                Class.forName("com.android.server.biometrics.BiometricSensor", false, classLoader),
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.util.List::class.java,
                Boolean::class.javaPrimitiveType,
                Class.forName("com.android.server.biometrics.BiometricCameraManager", false, classLoader)
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_PRE_AUTH_CONTEXT)
                .intercept(preAuthContextHooker)
            logInfo("OK: PreAuthInfo app context hook")
        }.onFailure { logError("FAIL PreAuthInfo context hook", it) }
    }

    /** 仅在普通 App 的预检查调用链中放行 4095 人脸强度。 */
    private fun hookAppFaceStrengthCheck(classLoader: ClassLoader) {
        runCatching {
            val utilsClass = Class.forName(
                "com.android.server.biometrics.Utils",
                false,
                classLoader
            )
            val method = utilsClass.getDeclaredMethod(
                "isAtLeastStrength",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_FACE_STRENGTH_CHECK)
                .intercept(faceStrengthCheckHooker)
            logInfo("OK: app-only 4095 strength check")
        }.onFailure { logError("FAIL app strength hook", it) }
    }

    /** 允许人脸满足强认证：仅接管认证成功后的强度判定，且仅针对 FACE。 */
    private fun hookStrongBiometric(classLoader: ClassLoader) {
        runCatching {
            val serviceClass = Class.forName(
                "com.android.server.biometrics.BiometricService",
                false,
                classLoader
            )
            val method = serviceClass.getDeclaredMethod(
                "isStrongBiometric",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            hook(method)
                .setId(HOOK_ID_STRONG_BIOMETRIC)
                .intercept(strongBiometricHooker)
            logInfo("OK: strong biometric hook")
        }.onFailure { logError("FAIL strong biometric hook", it) }
    }

    companion object {
        private const val TAG = "FaceBiometricFix"

        private const val HOOK_ID_PRE_AUTH_CONTEXT = "hook_pre_auth_context"
        private const val HOOK_ID_FACE_STRENGTH_CHECK = "hook_face_strength_check"
        private const val HOOK_ID_STRONG_BIOMETRIC = "hook_strong_biometric"

        /** 厂商私有强度掩码（人脸传感器上报值） */
        private const val OEM_STRENGTH_MASK = 4095

        /**
         * 类原生系统（AVIUM / Lineage 系）会把厂商人脸 HAL 的 4095 归一化成 Class 2 的 255。
         * 必须与 4095 同等放行，否则模块在 system_server 挂载成功但完全不生效。
         */
        private const val WEAK_FACE_STRENGTH = 255
        private const val STRONG_AUTHENTICATORS = 15
        private const val WEAK_AUTHENTICATORS = 255
        private val APP_PRE_AUTH_PACKAGE = ThreadLocal<String>()

        /**
         * 本机人脸传感器的 id（OPLUS SenseProvider）。
         *
         * isStrongBiometric() 的 this 可能是匿名内部类（实测为
         * BiometricService$BiometricServiceWrapper$1），拿不到 mSensors，
         * 因此这里按 id 判定模态。换机型需同步此值，可用
         * `logcat | grep "Registering ID"` 查人脸 Modality: 8 那一行的 id。
         */
        private const val FACE_SENSOR_ID = 1008

        private const val STATUS_OK = 1
        private const val STATUS_INSUFFICIENT_STRENGTH = 4
        private const val FACE_MODALITY = 8
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CODEBOOK_PACKAGE = "com.coloros.codebook"
    }
}
