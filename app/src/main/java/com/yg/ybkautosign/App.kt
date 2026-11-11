package com.yg.ybkautosign

import android.app.Application
import com.yg.ybkautosign.core.Store

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ctx = this
        Store.init(this)
    }

    companion object {
        /** 全局 Context，给通知之类没地方拿 Context 的工具用。 */
        @Volatile
        var ctx: Application? = null
            private set
    }
}
