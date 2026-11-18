package com.yg.ybkautosign.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    // ------------------------------------------------------------ 铃声（闹钟提示音）

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null
    private var stopTask: Runnable? = null
    private var savedAlarmVol = -1

    /**
     * 播放闹钟铃声若干秒，结束后自动停并把音量还原。
     *
     * 为什么不用通知渠道的 setSound：
     * 渠道声音只在「发通知那一刻」响一次，而且用户在系统里一关就哑。
     * 开签只开几十秒，错过就没了，所以这里直接放闹钟铃，循环响到指定秒数。
     *
     * 音量保护：闹钟音量被人调到 0/很低时，临时抬到最大音量的 60%，响完还原。
     */
    fun playAlarm(seconds: Int = Store.soundSec) {
        if (!Store.soundOn) {
            LogBus.log("[铃声] 已经关掉了提示音，跳过")
            return
        }
        val ctx = App.ctx ?: return
        stopAlarm()

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var curVol = -1
        var maxVol = -1
        if (am != null) {
            curVol = runCatching { am.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
            maxVol = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
            val floor = (maxVol * 0.6f).toInt()
            if (maxVol > 0 && curVol < floor) {
                savedAlarmVol = curVol
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0) }
            }
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // 依次降级找铃声，闹钟铃 → 来电铃 → 通知音
        val candidates = intArrayOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION
        )
        var uri: Uri? = null
        var used = ""
        for (t in candidates) {
            val u = runCatching { RingtoneManager.getDefaultUri(t) }.getOrNull() ?: continue
            val exists = runCatching { RingtoneManager.getRingtone(ctx, u) }.getOrNull() != null
            if (!exists) continue
            uri = u
            used = when (t) {
                RingtoneManager.TYPE_ALARM -> "闹钟铃声"
                RingtoneManager.TYPE_RINGTONE -> "来电铃声"
                else -> "通知音"
            }
            break
        }

        if (uri == null) {
            LogBus.log("[铃声] 系统里找不到可用铃声，只震动不响铃")
            restoreVolume(am)
            return
        }

        // 首选 MediaPlayer：isLooping 比 Ringtone 可靠，长短铃声都能响满
        val mp = MediaPlayer()
        val startedByPlayer = runCatching {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            true
        }.getOrDefault(false)

        if (startedByPlayer) {
            player = mp
            LogBus.log("[铃声] 开始播放${used}，${seconds}s（音量 ${curVol}/${maxVol}，MediaPlayer 循环）")
        } else {
            runCatching { mp.release() }
            // 兜底：Ringtone
            val r = runCatching { RingtoneManager.getRingtone(ctx, uri) }.getOrNull()
            if (r == null) {
                LogBus.log("[铃声] MediaPlayer 和 Ringtone 都启动失败，只震动")
                restoreVolume(am)
                return
            }
            runCatching { r.audioAttributes = attrs }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { r.isLooping = true }
            }
            val ok = runCatching { r.play(); true }.getOrDefault(false)
            if (!ok) {
                LogBus.log("[铃声] 播放失败（可能被系统静音策略拦了）")
                restoreVolume(am)
                return
            }
            ringtone = r
            LogBus.log("[铃声] 开始播放${used}，${seconds}s（音量 ${curVol}/${maxVol}，Ringtone 兜底）")
        }

        val task = Runnable {
            LogBus.log("[铃声] 响够 ${seconds}s，已停止")
            stopAlarm()
        }
        stopTask = task
        main.postDelayed(task, seconds * 1000L)
    }

    fun stopAlarm() {
        stopTask?.let { main.removeCallbacks(it) }
        stopTask = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { ringtone?.stop() }
        ringtone = null
        val am = App.ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        restoreVolume(am)
    }

    private fun restoreVolume(am: AudioManager?) {
        if (savedAlarmVol < 0 || am == null) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0) }
        savedAlarmVol = -1
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
        // 自动签：响 5 秒就够（它自己在动）；要人手动签：响满，当闹钟用
        playAlarm(if (autoStarted) 5 else Store.soundSec)
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
        playAlarm(6)
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
