package com.yg.ybkautosign.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局运行日志，UI 与服务共用。只保留最近 600 行。 */
object LogBus {

    private const val MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(msg: String) {
        // 同步打到 logcat，连着电脑时 adb logcat -s YbkSignZ 就能看，不用盯手机
        android.util.Log.i("YbkSignZ", msg)
        val line = "${fmt.format(Date())}  $msg"
        val cur = _lines.value
        val next = if (cur.size >= MAX) cur.subList(cur.size - MAX + 1, cur.size) + line else cur + line
        _lines.value = next
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun snapshot(): String = _lines.value.joinToString("\n")
}
