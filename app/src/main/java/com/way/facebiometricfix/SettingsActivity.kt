package com.way.facebiometricfix

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 应用开关界面。
 *
 * 顶部固定区域是「强认证」总开关（默认关）：开启后人脸可满足 Class 3 强认证，
 * 生物识别绑定的 Keystore 密钥因此可被人脸解锁；关闭时人脸只用于普通认证。
 *
 * 下方列表勾选 = 允许该应用把面容当作 Class 3 使用，默认全开，
 * 只保存未勾选的包名。改动经 [PrefsProvider] 传递给 system_server。
 *
 * 注意两点（都踩过）：
 * - targetSdk 35+ 强制 edge-to-edge，内容会画到状态栏下面，需自行处理窗口内边距；
 * - 标题栏同样会盖住内容，直接不要它。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var listView: ListView
    private lateinit var strongCheck: CheckBox
    private lateinit var strongHint: TextView

    private var packages: List<String> = emptyList()

    /** 列表勾选状态自己维护；ArrayAdapter 重建会清掉 ListView 的 checked 状态 */
    private val uncheckedPackages = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // 标题栏会盖住列表顶部，这里不要它，标题自己画
        actionBar?.hide()

        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        strongCheck = CheckBox(this).apply {
            setText(R.string.allow_strong)
            setPadding(dp(16), dp(8), dp(16), dp(4))
            isChecked = prefs.getBoolean(Config.KEY_ALLOW_FACE_STRONG, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Config.KEY_ALLOW_FACE_STRONG, checked).apply()
                updateStrongHint()
            }
        }

        strongHint = TextView(this).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
            textSize = 12f
        }

        listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(strongCheck)
            addView(strongHint)
            addView(listView)
        }

        // edge-to-edge：给状态栏/导航栏让出位置
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        setContentView(root)
        updateStrongHint()
    }

    override fun onResume() {
        super.onResume()

        // 只列有启动入口的应用：范围可控，且覆盖了所有会用 BiometricPrompt 的客户端
        val pm = packageManager
        val entries = linkedMapOf<String, String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            entries.putIfAbsent(pkg, info.loadLabel(pm).toString())
        }
        val sorted = entries.entries.sortedBy { it.value.lowercase() }
        packages = sorted.map { it.key }

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            sorted.map { "${it.value}\n${it.key}" }
        )

        uncheckedPackages.clear()
        uncheckedPackages.addAll(
            prefs.getStringSet(Config.KEY_DISABLED, emptySet()) ?: emptySet()
        )
        for (i in packages.indices) {
            listView.setItemChecked(i, packages[i] !in uncheckedPackages)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = packages.getOrNull(position) ?: return@setOnItemClickListener
            if (listView.isItemChecked(position)) uncheckedPackages.remove(pkg)
            else uncheckedPackages.add(pkg)
            save()
        }
    }

    private fun updateStrongHint() {
        strongHint.text = getString(
            if (strongCheck.isChecked) R.string.allow_strong_on else R.string.allow_strong_off
        )
    }

    private fun save() {
        prefs.edit()
            .putStringSet(Config.KEY_DISABLED, HashSet(uncheckedPackages))
            .apply()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ENABLE_ALL, Menu.NONE, R.string.enable_all)
        menu.add(Menu.NONE, MENU_DISABLE_ALL, Menu.NONE, R.string.disable_all)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ENABLE_ALL -> setAll(true)
            MENU_DISABLE_ALL -> setAll(false)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setAll(checked: Boolean) {
        uncheckedPackages.clear()
        for (i in packages.indices) {
            listView.setItemChecked(i, checked)
            if (!checked) uncheckedPackages.add(packages[i])
        }
        save()
    }

    private companion object {
        const val MENU_ENABLE_ALL = 1
        const val MENU_DISABLE_ALL = 2
    }
}
