package com.yg.ybkautosign

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import com.yg.ybkautosign.core.Course
import com.yg.ybkautosign.core.LogBus
import com.yg.ybkautosign.core.Notifier
import com.yg.ybkautosign.core.SignEngine
import com.yg.ybkautosign.core.Store
import com.yg.ybkautosign.core.Timetable
import com.yg.ybkautosign.databinding.ActivityMainBinding
import com.yg.ybkautosign.service.AutoSignService
import com.yg.ybkautosign.service.SignAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        Store.init(this)
        Notifier.ensureChannels(this)

        // 回填
        Store.cred?.let {
            b.etAccount.setText(it.account)
            b.etPwd.setText(it.password)
        }
        b.etTable.setText(Store.timetableRaw)
        b.etPoll.setText(Store.pollSec.toString())
        b.swDebug.isChecked = Store.debugRaw
        b.swVibrate.isChecked = Store.vibrateOn
        renderVibLevel()
        renderCourses(Store.courses)
        renderTableHint(Store.timetableRaw)
        renderStatus()

        b.btnLogin.setOnClickListener { doLogin() }
        b.btnSelTerm.setOnClickListener { selectCourses { it.termCurrent } }
        b.btnSelAll.setOnClickListener { selectCourses { true } }
        b.btnSelNone.setOnClickListener { selectCourses { false } }
        b.btnSaveTable.setOnClickListener { saveTable() }
        b.btnA11y.setOnClickListener { openAccessibilitySettings() }
        b.btnOverlay.setOnClickListener { requestOverlay() }
        b.btnUnlock.setOnClickListener { openAppDetail() }
        b.btnStart.setOnClickListener { startMonitor() }
        b.btnTest.setOnClickListener { testOnce() }
        b.btnStop.setOnClickListener { stopMonitor() }
        b.btnClear.setOnClickListener { LogBus.clear() }
        b.btnBattery.setOnClickListener { requestIgnoreBattery() }
        b.btnNotif.setOnClickListener { requestNotifPermission() }
        b.swDebug.setOnCheckedChangeListener { _, v -> Store.debugRaw = v }

        // 震动提醒
        b.swVibrate.setOnCheckedChangeListener { _, v ->
            Store.vibrateOn = v
            if (v) Notifier.vibrate(longArrayOf(0, 260, 140, 260))
        }
        b.btnVibLight.setOnClickListener { Store.vibrateLevel = 0; renderVibLevel() }
        b.btnVibMid.setOnClickListener { Store.vibrateLevel = 1; renderVibLevel() }
        b.btnVibHeavy.setOnClickListener { Store.vibrateLevel = 2; renderVibLevel() }
        b.btnVibTest.setOnClickListener { testVibrate() }

        // 轮询间隔随手改随手存，改完要在「开始监控」前生效需重启服务
        b.etPoll.doAfterTextChanged { s ->
            s?.toString()?.toIntOrNull()?.let { Store.pollSec = it }
        }

        collectLog()
        maybeAskNotif()

        handleCliAction()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCliAction()
    }

    /**
     * 命令行触发，方便连着电脑时验证：
     *   adb shell am start -n com.yg.ybkautosign/.MainActivity -e action test      ← 立即查一次开签
     *   adb shell am start -n com.yg.ybkautosign/.MainActivity -e action vibrate   ← 立即震一次
     */
    private fun handleCliAction() {
        when (intent?.getStringExtra("action")) {
            "test" -> b.root.postDelayed({ testOnce() }, 1200)
            "vibrate" -> b.root.postDelayed({ testVibrate() }, 1200)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderPermStatus()
    }

    // ------------------------------------------------------------ 状态显示

    private fun renderStatus() {
        val c = Store.cred
        b.tvStatus.text = when {
            c == null || c.token.isBlank() -> "未登录"
            else -> "已登录：${c.account} · token ${c.token.take(8)}… · 本地缓存 ${Store.courses.size} 门课"
        }
    }

    private fun renderPermStatus() {
        val a11y = isAccessibilityOn()
        val overlay = canDrawOverlay()
        b.tvPermStatus.text = buildString {
            append(if (a11y) "✅ 无障碍已开启" else "❌ 无障碍未开启")
            append("　")
            append(if (overlay) "✅ 悬浮窗已开" else "❌ 悬浮窗未开")
            if (a11y && overlay) append("\n两项都齐了，可以自动点签到。")
            else if (!a11y) append("\n打不开无障碍？先点上面「解除权限限制」，在应用信息页点右上角 ⋮ → 允许应用获取权限。")
        }
        b.tvPermStatus.setTextColor(0xFF000000.toInt())
    }

    private fun isAccessibilityOn(): Boolean {
        if (SignAccessibilityService.connected) return true
        val expected = ComponentName(this, SignAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    // ------------------------------------------------------------ 登录 / 课程

    private fun doLogin() {
        val acc = b.etAccount.text?.toString()?.trim().orEmpty()
        val pwd = b.etPwd.text?.toString().orEmpty()
        if (acc.isEmpty() || pwd.isEmpty()) {
            Toast.makeText(this, "先填手机号和密码", Toast.LENGTH_SHORT).show()
            return
        }
        b.btnLogin.isEnabled = false
        b.tvStatus.text = "登录中…"
        LogBus.log("开始登录 $acc")

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { SignEngine.login(acc, pwd) }
            }
            b.btnLogin.isEnabled = true
            r.onSuccess { courses ->
                renderStatus()
                renderCourses(courses)
                Toast.makeText(this@MainActivity, "登录成功，共 ${courses.size} 门课", Toast.LENGTH_SHORT).show()
            }.onFailure {
                b.tvStatus.text = "登录失败：${it.message}"
                Toast.makeText(this@MainActivity, "登录失败，看日志", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderCourses(courses: List<Course>) {
        b.boxCourses.removeAllViews()
        if (courses.isEmpty()) {
            b.boxCourses.addView(android.widget.TextView(this).apply {
                text = "还没有课程。先登录拉取。"
                textSize = 12f
                setTextColor(0xFF000000.toInt())
                setPadding(0, 8, 0, 8)
            })
            return
        }

        // 第一次进来（全新安装 / 老版本升级）：自动勾上本学期在上的课
        if (!Store.selectedInited) {
            var picked = courses.filter { it.termCurrent }.map { it.id }.toSet()
            if (picked.isEmpty()) picked = courses.map { it.id }.toSet()   // 兜底：一个本学期都没有就全选
            Store.selected = picked
            Store.selectedInited = true
            LogBus.log("[课程] 自动勾选本学期 ${picked.size} / ${courses.size} 门")
        }

        val selected = Store.selected.toMutableSet()
        courses.forEach { c ->
            val cb = MaterialCheckBox(this).apply {
                text = if (c.termCurrent) "${c.name}（本学期）" else c.name
                textSize = 14f
                // 严格按存下来的勾选状态显示，不再"空集就全勾"
                isChecked = selected.contains(c.id)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(c.id) else selected.remove(c.id)
                    Store.selected = selected.toSet()
                    Store.selectedInited = true
                    renderCourseCount()
                }
            }
            b.boxCourses.addView(cb)
        }
        renderCourseCount()
    }

    // ------------------------------------------------------------ 震动提醒

    private fun renderVibLevel() {
        val lv = Store.vibrateLevel
        val on = 0xFF000000.toInt()
        val off = 0xFF9AA3AE.toInt()
        b.btnVibLight.setTextColor(if (lv == 0) on else off)
        b.btnVibMid.setTextColor(if (lv == 1) on else off)
        b.btnVibHeavy.setTextColor(if (lv == 2) on else off)
    }

    /** 立刻震一次，用户在真机上确认「手机到底震不震」。 */
    private fun testVibrate() {
        if (!Store.vibrateOn) {
            Toast.makeText(this, "震动提醒是关的，先打开上面的开关", Toast.LENGTH_SHORT).show()
            return
        }
        Notifier.vibrate(longArrayOf(0, 500, 250, 500, 250, 900))
        val name = when (Store.vibrateLevel) {
            0 -> "轻"
            1 -> "中"
            else -> "重"
        }
        LogBus.log("[震动] 已发出测试震动（强度：$name）。如果手机没震，看下面那行提示。")
        Toast.makeText(this, "震了没？没震就看下面的提示", Toast.LENGTH_SHORT).show()
    }

    private fun renderCourseCount() {
        val total = Store.courses.size
        val n = Store.selected.size
        b.tvCourseCount.text = "已勾选 $n / 共 $total 门"
    }

    /** 批量勾选。predicate 决定哪些课被选中。 */
    private fun selectCourses(predicate: (Course) -> Boolean) {
        val picked = Store.courses.filter(predicate).map { it.id }.toSet()
        Store.selected = picked
        Store.selectedInited = true
        renderCourses(Store.courses)
        LogBus.log("[课程] 勾选已更新：${picked.size} / ${Store.courses.size} 门")
    }

    // ------------------------------------------------------------ 课表

    private fun saveTable() {
        val raw = b.etTable.text?.toString().orEmpty()
        val res = Timetable.parse(raw)
        Store.timetableRaw = raw
        renderTableHint(raw)
        if (res.errors.isEmpty()) {
            LogBus.log("课表已保存：${res.lessons.size} 节")
            Toast.makeText(this, "课表已保存（${res.lessons.size} 节）", Toast.LENGTH_SHORT).show()
            if (Store.enabled) restartMonitor()
        } else {
            LogBus.log("课表有 ${res.errors.size} 行解析失败：\n" + res.errors.joinToString("\n"))
            Toast.makeText(this, "有 ${res.errors.size} 行格式不对", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderTableHint(raw: String) {
        val res = Timetable.parse(raw)
        b.tvTableHint.text = when {
            res.errors.isEmpty() && res.lessons.isNotEmpty() -> "✅ 已解析 ${res.lessons.size} 节"
            res.errors.isEmpty() -> "留空＝全天不间断，按上面设的间隔轮询"
            else -> res.errors.joinToString("\n")
        }
    }

    // ------------------------------------------------------------ 监控开关

    private fun startMonitor() {
        val c = Store.cred
        if (c == null || c.token.isBlank()) {
            Toast.makeText(this, "先登录一次", Toast.LENGTH_LONG).show()
            return
        }
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "无障碍服务没开，开完再启动", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (!canDrawOverlay()) {
            Toast.makeText(this, "悬浮窗没开，后台拉不起云班课", Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        Store.enabled = true
        AutoSignService.start(this)
        maybeAskNotif()
        Toast.makeText(this, "监控已启动", Toast.LENGTH_SHORT).show()
    }

    /** 立刻查一轮，不启动前台服务。用来验证 token、课程、接口是否都通。 */
    private fun testOnce() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在检查…", Toast.LENGTH_SHORT).show()
            try {
                val n = SignEngine.round()
                if (n == 0) {
                    LogBus.log("[自检] 这一轮没有开着的签到（正常，说明接口是通的）")
                } else {
                    LogBus.log("[自检] 发现 $n 个开着的签到，已派给无障碍去点")
                }
            } catch (e: Exception) {
                LogBus.log("[自检] 失败：${e.message}")
            }
            renderStatus()
        }
    }

    private fun stopMonitor() {
        Store.enabled = false
        AutoSignService.stop(this)
        LogBus.log("用户手动停止监控")
        Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
    }

    private fun restartMonitor() {
        AutoSignService.stop(this)
        b.root.postDelayed({ if (Store.enabled) AutoSignService.start(this) }, 800)
    }

    // ------------------------------------------------------------ 授权

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "在列表里找到「云班课自动签到助手」并打开", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
    }

    /** 打开本应用的信息页。小米/红米/澎湃OS 上「允许应用获取权限」的入口就在这里。 */
    private fun openAppDetail() {
        Toast.makeText(this, "点右上角 ⋮ → 允许应用获取权限", Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "找到「云班课自动签到」并允许显示在其他应用上层", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
        } else {
            Toast.makeText(this, "当前系统不需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAskNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        } else {
            Toast.makeText(this, "当前系统不需要单独申请通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已经在白名单里了", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ------------------------------------------------------------ 日志

    private fun collectLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LogBus.lines.collect { lines ->
                    b.tvLog.text = lines.joinToString("\n")
                }
            }
        }
    }
}
