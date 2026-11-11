package com.yg.ybkautosign.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yg.ybkautosign.App
import com.yg.ybkautosign.MainActivity
import com.yg.ybkautosign.R

/**
 * 通知。两条渠道：
 *  - 常驻的「监控中」前台通知（由 AutoSignService 发）
 *  - 一次性的「开签了」提醒，点一下直接进 App
 */
object Notifier {

    const val CH_MONITOR = "ybk_monitor"
    const val CH_ALERT = "ybk_alert"
    const val ID_MONITOR = 1001
    private const val ID_ALERT_BASE = 2000

    private var seq = 0

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
        if (nm.getNotificationChannel(CH_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "签到提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "发现老师开签到时的提醒"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    /** 发现开签了。autoStarted=true 表示无障碍已经在自动点了。 */
    fun alertCheckin(courseName: String, autoStarted: Boolean) {
        val ctx = App.ctx ?: return
        ensureChannels(ctx)

        val title = if (autoStarted) "正在自动签到：$courseName" else "快签到！$courseName"
        val text = if (autoStarted) {
            "已自动打开云班课并点击签到，请留意结果"
        } else {
            "无障碍服务没开，请立刻手动打开云班课签到"
        }

        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()

        runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT_BASE + (seq++ % 100), n)
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
