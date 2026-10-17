package com.way.facebiometricfix

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 跨进程配置读取。
 *
 * 不用 libxposed 的 getRemotePreferences：部分机型（/data 被路径隐藏，例如 SUSFS）
 * 下 system_server 读回来恒为空集合且不抛异常，界面形同虚设。这里由模块进程自己读
 * 自己的 SharedPreferences，再经 Binder 交给 system_server。
 *
 * 以键值行形式返回：[Config.KEY_ALLOW_FACE_STRONG] 一行（"1"/"0"），
 * 以及每个被关闭的包名各一行（[Config.KEY_DISABLED]）。
 */
class PrefsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(COLUMN_KEY, COLUMN_VALUE))
        val ctx = context ?: return cursor
        val prefs = ctx.getSharedPreferences(Config.PREFS_NAME, 0)

        cursor.addRow(
            arrayOf<Any>(
                Config.KEY_ALLOW_FACE_STRONG,
                if (prefs.getBoolean(Config.KEY_ALLOW_FACE_STRONG, false)) "1" else "0"
            )
        )

        val disabled = prefs.getStringSet(Config.KEY_DISABLED, emptySet()) ?: emptySet()
        for (pkg in disabled) {
            cursor.addRow(arrayOf<Any>(Config.KEY_DISABLED, pkg))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
    }
}
