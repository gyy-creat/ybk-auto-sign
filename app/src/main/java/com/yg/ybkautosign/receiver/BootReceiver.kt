package com.yg.ybkautosign.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yg.ybkautosign.core.LogBus
import com.yg.ybkautosign.core.Store
import com.yg.ybkautosign.service.AutoSignService

/** 开机 / App 升级后自动把监控拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Store.init(context)
        if (!Store.enabled) return
        LogBus.log("开机/升级广播：恢复监控服务")
        runCatching { AutoSignService.start(context) }
    }
}
