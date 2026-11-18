package com.yg.ybkautosign.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yg.ybkautosign.App
import com.yg.ybkautosign.MainActivity
import com.yg.ybkautosign.R

/**
 * 通知 + 震动提醒。
 *
 * 两条通知渠道：
 *  - CH_MONITOR：常驻的「监控中」前台通知（由 AutoSignService 发），静音
 *  - CH_ALERT  ：开签提醒，最高优先级，带震动
 *
 * 震动为什么不用通知渠道自带的：
 * 用户很容易在系统里把这个渠道的震动关掉，或者手机处于静音/勿扰，
 * 那时提醒就哑了。所以这里额外用 Vibrator 直接震一次，
 * AudioAttributes 走 USAGE_ALARM，尽量穿透静音模式。
 */
object Notifier {

    const val CH_MONITOR = "ybk_monitor"
    const val CH_ALERT = "ybk_alert"
    const val ID_MONITOR = 1001
    private const val ID_ALERT_BASE = 2000

    private var seq = 0

    /** 要你手动签：长震，两轮半，很难忽略 */
    private val PAT_CALL = longArrayOf(0, 700, 250, 700, 250, 1100)

    /** 正在自动签：中震一轮，告诉你「我来处理了」 */
    private val PAT_AUTO = longArrayOf(0, 320, 160, 320, 160, 550)

    /** 签到成功：短震，事后确认感 */
    private val PAT_OK = longArrayOf(0, 110, 80, 110)

    /** 自动签失败：急促，比 PAT_CALL 更急 */
    private val PAT_FAIL = longArrayOf(0, 250, 120, 250, 120, 250, 120, 250)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CH_MONITOR) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MONITOR, "签到监控", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "常驻通知，说明后台还在盯着"
                    setShowBadge(false)
                }
            )
        }

        val alert = nm.getNotificationChannel(CH_ALERT)
        if (alert == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "签到提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "发现老师开签到时的震动 + 弹窗提醒"
                    enableVibration(true)
                    vibrationPattern = PAT_CALL
                    enableLights(true)
                    lightColor = 0xFFFF3B30.toInt()
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
            )
        }
    }

    // ------------------------------------------------------------ 震动

    /** 按当前设置震一下。pattern 是 [等待, 震, 停, 震, …] 毫秒。 */
    fun vibrate(pattern: LongArray) {
        if (!Store.vibrateOn) return
        val ctx = App.ctx ?: return

        val v: Vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }.getOrNull() ?: return

        if (!v.hasVibrator()) return

        // 三档强度：每个「震」段的振幅不同，停顿段振幅必须是 0
        val amp = when (Store.vibrateLevel) {
            0 -> 110
            1 -> 180
            else -> 255
        }
        val timings = pattern
        val amplitudes = IntArray(timings.size) { i ->
            // 偶数下标（0 起）是等待/停顿段，奇数下标是震动段
            if (i % 2 == 1) amp else 0
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        }
    }

    // ------------------------------------------------------------ 通知

    /** 发现开签了。autoStarted=true 表示无障碍已经在自动点了。 */
    fun alertCheckin(courseName: String, autoStarted: Boolean) {
        val ctx = App.ctx ?: return
        ensureChannels(ctx)

        val title = if (autoStarted) "正在自动签到：$courseName" else "快签到！$courseName"
        val text = if (autoStarted) {
            "已自动打开云班课并点击签到，请留意结果"
        } else {
            "无障碍服务没开，请立刻打开云班课手动签到"
        }

        vibrate(if (autoStarted) PAT_AUTO else PAT_CALL)
        post(ctx, title, text, if (autoStarted) PAT_AUTO else PAT_CALL)
    }

    /** 自动签到成功，告诉你一声（短震）。 */
    fun notifySigned(courseName: String) {
        val ctx = App.ctx ?: return
        ensureChannels(ctx)
        vibrate(PAT_OK)
        post(ctx, "✅ 已签到：$courseName", "无障碍已点掉签到按钮，本次自动完成", null)
    }

    /** 自动签到失败，需要你自己补一刀（急促震动）。 */
    fun notifyFailed(courseName: String, why: String) {
        val ctx = App.ctx ?: return
        ensureChannels(ctx)
        vibrate(PAT_FAIL)
        post(ctx, "⚠️ 自动签到没成功：$courseName", "$why —— 请立刻手动签到", PAT_FAIL)
    }

    private fun post(ctx: Context, title: String, text: String, vibration: LongArray?) {
        val pi = openApp(ctx)

        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pi)

        if (vibration != null) {
            b.setVibrate(vibration)   // API 26 以下靠它；26+ 靠渠道和 Vibrator
        }

        // 锁屏直接亮屏弹出。Android 14 起系统可能把非闹钟类 App 降级成普通通知，
        // 这里 try 兜住，降级了也不影响震动和通知本身。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.canUseFullScreenIntent()) b.setFullScreenIntent(pi, true)
            } else {
                b.setFullScreenIntent(pi, true)
            }
        } catch (e: Exception) {
            // 权限/系统限制，忽略
        }

        runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT_BASE + (seq++ % 100), b.build())
        }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
