package com.way.facebiometricfix

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * 跨进程配置。
 *
 * - [KEY_DISABLED]：被关闭面容的应用包名，其余应用一律放行（默认全开）
 * - [KEY_ALLOW_FACE_STRONG]：是否允许人脸满足强认证（默认关）
 *
 * 数据由模块进程经 [PrefsProvider] 提供，system_server 侧用 system context 的
 * ContentResolver 读取，经 Binder 传递、不经过文件系统。
 */
internal object Config {

    const val PREFS_NAME = "config"
    const val KEY_DISABLED = "disabled_packages"
    const val KEY_ALLOW_FACE_STRONG = "allow_face_strong"
    const val AUTHORITY = "com.way.facebiometricfix.prefs"

    private const val COLUMN_KEY = "key"
    private const val COLUMN_VALUE = "value"
    private const val TAG = "FaceBiometricFix"

    /** 配置缓存有效期：避免每次判定都跨进程查询 */
    private const val TTL_MS = 3000L

    @Volatile
    private var disabled: Set<String> = emptySet()

    @Volatile
    private var allowFaceStrong = false

    @Volatile
    private var lastLoad = 0L

    /** 在 system_server 启动与热重载时预读一次。日志走 logcat，不依赖模块实例。 */
    fun attach() {
        lastLoad = 0L
        load()
    }

    fun isEnabled(packageName: String): Boolean {
        refresh()
        return packageName !in disabled
    }

    fun isFaceStrongAllowed(): Boolean {
        refresh()
        return allowFaceStrong
    }

    private fun refresh() {
        if (SystemClock.uptimeMillis() - lastLoad > TTL_MS) {
            load()
        }
    }

    private fun load() {
        // 先记时间戳，避免读取过程中被重复触发
        lastLoad = SystemClock.uptimeMillis()

        val context = systemContext()
        if (context == null) {
            Log.w(TAG, "system context unavailable, keep config as-is")
            return
        }

        val result = runCatching {
            val packages = HashSet<String>()
            var allow = false
            context.contentResolver
                .query(Uri.parse("content://$AUTHORITY"), null, null, null, null)
                ?.use { cursor ->
                    val keyIndex = cursor.getColumnIndex(COLUMN_KEY)
                    val valueIndex = cursor.getColumnIndex(COLUMN_VALUE)
                    if (keyIndex >= 0 && valueIndex >= 0) {
                        while (cursor.moveToNext()) {
                            when (cursor.getString(keyIndex)) {
                                KEY_DISABLED -> cursor.getString(valueIndex)?.let { packages.add(it) }
                                KEY_ALLOW_FACE_STRONG -> allow = cursor.getString(valueIndex) == "1"
                            }
                        }
                    }
                }
            packages to allow
        }

        result.onSuccess { (packages, allow) ->
            disabled = packages
            allowFaceStrong = allow
            Log.i(TAG, "config loaded, allowFaceStrong=$allow, disabled=$packages")
        }
        result.onFailure { Log.w(TAG, "read config failed: $it") }
    }

    /** system_server 中取得 system context，用于访问 ContentProvider。 */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        activityThread
            .getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(current) as? Context
    }.getOrNull()
}
