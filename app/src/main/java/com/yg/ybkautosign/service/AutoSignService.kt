package com.yg.ybkautosign.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yg.ybkautosign.MainActivity
import com.yg.ybkautosign.R
import com.yg.ybkautosign.core.LogBus
import com.yg.ybkautosign.core.SignEngine
import com.yg.ybkautosign.core.Store
import com.yg.ybkautosign.core.Timetable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * 常驻前台服务：按课表窗口轮询云班课开签状态，开签即签。
 *
 * 轮询节奏（间隔由用户在界面上设，默认 30 秒）：
 *   有课表 + 现在在上课时段（课前 10 分钟 → 下课）：按设定间隔（30s）
 *   有课表 + 不在上课时段：放宽到 max(设定间隔, 120s)，省电又不漏签
 *   课表留空：全时段按设定间隔
 */
class AutoSignService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CH_ID = "ybk_monitor"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.yg.ybkautosign.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, AutoSignService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AutoSignService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("监控中 · 等待开签"))
        acquireWakeLock()
        if (loopJob == null || loopJob?.isActive != true) {
            loopJob = scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        try {
            LogBus.log("─── 监控已启动 ───")
            val lessons = Timetable.parse(Store.timetableRaw).lessons
            if (lessons.isEmpty()) {
                LogBus.log("课表为空：全时段按 ${Store.pollSec}s 轮询")
            } else {
                LogBus.log("课表已载入 ${lessons.size} 节：上课时段每 ${Store.pollSec}s，其余时段每 ${maxOf(Store.pollSec, 120)}s")
            }

            while (true) {
                try {
                    if (!Store.enabled) {
                        LogBus.log("监控开关已关闭，服务退出")
                        break
                    }
                    if (Store.cred?.token.isNullOrBlank()) {
                        LogBus.log("还没登录，等待登录…")
                    } else {
                        val found = SignEngine.round()
                        updateNotification(
                            if (found > 0) "本轮发现 $found 个开签，已派无障碍执行"
                            else "监控中 · ${Timetable.nowTime()}"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogBus.log("轮询异常：${e.message}")
                }

                val inWindow = lessons.isEmpty() || Timetable.isNowInWindow(lessons)
                delay(if (inWindow) Store.pollSec * 1000L else maxOf(Store.pollSec, 120) * 1000L)
            }
            LogBus.log("─── 监控已停止 ───")
        } catch (e: CancellationException) {
            LogBus.log("监控协程被取消")
        } finally {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------ 保活相关

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YbkAutoSign::engine").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 最长 12 小时，够一个上课日
        }
        LogBus.log("已获取 CPU 唤醒锁（熄屏也能轮询）")
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CH_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CH_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AutoSignService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
