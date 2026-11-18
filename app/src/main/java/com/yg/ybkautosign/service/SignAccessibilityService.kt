package com.yg.ybkautosign.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yg.ybkautosign.core.LogBus
import com.yg.ybkautosign.core.Notifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动签到执行器。
 *
 * 为什么需要它：云班课网页端没有「学生提交签到」的接口，签到提交只存在于 App 内部，
 * 而 App 有加固（DexHelper）。所以用 API 发现「开签了」之后，派这个无障碍服务去点按钮。
 *
 * 流程：亮屏 → 上滑解锁 → 拉起云班课 App → 反复扫描界面找「签到」→ 点 → 判定结果
 *
 * 前提：手机锁屏用滑动解锁（无密码），并且给本 App 开了「悬浮窗」权限
 * （Android 10+ 后台启动 Activity 必须有 SYSTEM_ALERT_WINDOW）。
 */
class SignAccessibilityService : AccessibilityService() {

    companion object {
        const val TARGET_PKG = "com.mosoink.teach"

        @Volatile
        var instance: SignAccessibilityService? = null
            private set

        @Volatile
        var connected = false
            private set

        /** 外部（SignEngine / 前台服务）调用：要求立刻去签一次。courseName 用于结果通知。 */
        fun requestSign(reason: String, courseName: String = ""): Boolean {
            val s = instance ?: return false
            return s.startAutoSign(reason, courseName)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var attempts = 0
    private var tag = ""
    private var courseName = ""

    private val scanRunnable = Runnable { doScan() }

    // ------------------------------------------------------------ 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        LogBus.log("[无障碍] 服务已连接，可以自动点签到了")
    }

    override fun onDestroy() {
        instance = null
        connected = false
        main.removeCallbacksAndMessages(null)
        busy.set(false)
        LogBus.log("[无障碍] 服务已断开")
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopAutoSign("被系统中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件来了就顺手扫一次，比纯定时器反应快
        if (busy.get() && event != null) {
            val pkg = event.packageName?.toString()
            if (pkg == TARGET_PKG) {
                main.removeCallbacks(scanRunnable)
                main.postDelayed(scanRunnable, 250)
            }
        }
    }

    // ------------------------------------------------------------ 自动签到主流程

    fun startAutoSign(reason: String, course: String = ""): Boolean {
        if (!busy.compareAndSet(false, true)) {
            LogBus.log("[无障碍] 上一轮还没结束，忽略本次请求")
            return false
        }
        tag = reason
        courseName = course
        attempts = 0
        LogBus.log("[无障碍] 开始自动签到：$reason")

        wakeScreen()
        main.postDelayed({ unlockThenLaunch() }, 450)
        return true
    }

    /**
     * 结束一轮。
     * @param result 1=签到成功（短震+通知） -1=失败（急促震动+通知） 0=中性（中断/被忽略，不打扰用户）
     */
    private fun stopAutoSign(why: String, result: Int = 0) {
        val wasBusy = busy.getAndSet(false)
        if (wasBusy) {
            LogBus.log("[无障碍] 结束：$why")
        }
        main.removeCallbacks(scanRunnable)
        if (!wasBusy) return

        val name = courseName.ifBlank { "云班课签到" }
        when (result) {
            1 -> Notifier.notifySigned(name)
            -1 -> Notifier.notifyFailed(name, why)
        }
    }

    /** 点亮屏幕。息屏状态下无障碍读不到界面，不点亮什么都点不了。 */
    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "ybk:sign"
                )
                wl.acquire(20_000L)
                LogBus.log("[无障碍] 屏幕是灭的，已唤醒")
            }
        } catch (e: Exception) {
            LogBus.log("[无障碍] 唤醒失败：${e.message}")
        }
    }

    private fun unlockThenLaunch() {
        swipeUp()
        main.postDelayed({ launchApp() }, 900)
    }

    /** 无密码锁屏下，从底部上滑即解锁。有密码的话这一步没效果，需要用户自己解。 */
    private fun swipeUp() {
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val path = Path().apply {
                moveTo(w / 2f, h * 0.88f)
                lineTo(w / 2f, h * 0.30f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            LogBus.log("[无障碍] 上滑解锁失败：${e.message}")
        }
    }

    private fun launchApp() {
        val intent = packageManager.getLaunchIntentForPackage(TARGET_PKG)
        if (intent == null) {
            stopAutoSign("手机里没装云班课 App（$TARGET_PKG）", -1)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            LogBus.log("[无障碍] 已拉起云班课，等待签到入口出现…")
        } catch (e: Exception) {
            LogBus.log("[无障碍] 拉起 App 失败：${e.message}（检查是否给了悬浮窗权限）")
            stopAutoSign("拉起 App 失败", -1)
            return
        }
        main.postDelayed(scanRunnable, 1500)
    }

    // ------------------------------------------------------------ 扫描与点击

    private fun doScan() {
        if (!busy.get()) return
        attempts++
        if (attempts > 40) {   // 约 30 秒
            stopAutoSign("扫描超时，没找到可点的签到按钮", -1)
            return
        }

        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root == null || root.packageName?.toString() != TARGET_PKG) {
            // App 还没到前台，再等等
            main.postDelayed(scanRunnable, 800)
            return
        }

        val screenText = collectText(root)
        if (screenText.contains("签到成功") || screenText.contains("已签到") || screenText.contains("签到已提交")) {
            stopAutoSign("界面显示已签到成功", 1)
            return
        }

        val target = findSignTarget(root)
        if (target == null) {
            main.postDelayed(scanRunnable, 800)
            return
        }

        val label = (target.text?.toString() ?: target.contentDescription?.toString() ?: "").trim()
        val ok = clickNode(target)
        LogBus.log("[无障碍] 第 $attempts 次尝试点击「${label.ifBlank { "签到" }}」→ ${if (ok) "已点击" else "点击失败"}")

        // 点完给它 2 秒看结果，再决定要不要继续
        main.postDelayed({
            val r2 = try { rootInActiveWindow } catch (e: Exception) { null }
            val t2 = collectText(r2)
            if (t2.contains("签到成功") || t2.contains("已签到") || t2.contains("签到已提交")) {
                stopAutoSign("签到成功", 1)
            } else {
                main.postDelayed(scanRunnable, 1200)
            }
        }, 2000)
    }

    /** 把整棵树的文本拼起来，用来做「成功了没有」的判断。 */
    private fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 3000) {
            val n = queue.removeFirst()
            n.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
            n.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /** 找「签到」字样的节点，优先返回能真正点下去的那个。 */
    private fun findSignTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 3000) {
            val n = queue.removeFirst()
            val txt = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ")
            if (isSignWord(txt)) {
                val clickable = climbToClickable(n)
                if (clickable != null) return clickable
                if (fallback == null) fallback = n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallback
    }

    private fun isSignWord(raw: String): Boolean {
        val t = raw.replace(" ", "").replace("\u00A0", "")
        if (t.isEmpty()) return false
        // 排除明显不是入口的文案
        if (t.contains("签到率") || t.contains("签到详情") || t.contains("签到统计")) return false
        if (t.contains("签到") && t.length <= 12) return true
        return false
    }

    /** 自己不可点就往父级找，找到能点的为止。 */
    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth++ < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val clickable = climbToClickable(node)
        if (clickable != null) {
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // 退化方案：按屏幕坐标点一下
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun tap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
