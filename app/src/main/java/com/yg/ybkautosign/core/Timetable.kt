package com.yg.ybkautosign.core

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 课表解析。
 *
 * 支持的行格式（一行一节课，空行和 # 开头忽略）：
 *   周一 08:00-09:40 高等数学
 *   周三 14:00~15:40 大学物理
 *   周5 10:00-11:40 英语
 *   1 08:00-09:40 高数          （数字表示周一）
 *
 * 第三段的"关键词"用于匹配云班课课程名（包含即匹配，大小写无关）。
 */
object Timetable {

    data class ParseResult(val lessons: List<Lesson>, val errors: List<String>)

    private val DAY_ALIAS = mapOf(
        "周一" to 1, "周1" to 1, "星期一" to 1, "一" to 1, "mon" to 1, "1" to 1,
        "周二" to 2, "周2" to 2, "星期二" to 2, "二" to 2, "tue" to 2, "2" to 2,
        "周三" to 3, "周3" to 3, "星期三" to 3, "三" to 3, "wed" to 3, "3" to 3,
        "周四" to 4, "周4" to 4, "星期四" to 4, "四" to 4, "thu" to 4, "4" to 4,
        "周五" to 5, "周5" to 5, "星期五" to 5, "五" to 5, "fri" to 5, "5" to 5,
        "周六" to 6, "周6" to 6, "星期六" to 6, "六" to 6, "sat" to 6, "6" to 6,
        "周日" to 7, "周天" to 7, "周7" to 7, "星期日" to 7, "星期天" to 7,
        "日" to 7, "天" to 7, "sun" to 7, "7" to 7
    )

    fun parse(raw: String): ParseResult {
        val lessons = ArrayList<Lesson>()
        val errors = ArrayList<String>()
        raw.lineSequence().forEachIndexed { idx, line0 ->
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) {
                errors.add("第${idx + 1}行：至少要『星期 时间段』两段 → $line")
                return@forEachIndexed
            }

            val dow = DAY_ALIAS[parts[0].lowercase()] ?: DAY_ALIAS[parts[0]]
            if (dow == null) {
                errors.add("第${idx + 1}行：认不出星期『${parts[0]}』→ $line")
                return@forEachIndexed
            }

            val timeStr = parts[1]
            val m = Regex("(\\d{1,2}):(\\d{2})\\s*[-~至到]\\s*(\\d{1,2}):(\\d{2})").find(timeStr)
            if (m == null) {
                errors.add("第${idx + 1}行：时间段写成 08:00-09:40 → $line")
                return@forEachIndexed
            }
            val sh = m.groupValues[1].toInt()
            val sm = m.groupValues[2].toInt()
            val eh = m.groupValues[3].toInt()
            val em = m.groupValues[4].toInt()
            if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59) {
                errors.add("第${idx + 1}行：时间超出范围 → $line")
                return@forEachIndexed
            }

            val keyword = parts.drop(2).joinToString(" ").trim()
            lessons.add(
                Lesson(
                    dayOfWeek = dow,
                    startMin = sh * 60 + sm,
                    endMin = eh * 60 + em,
                    keyword = keyword
                )
            )
        }
        return ParseResult(lessons, errors)
    }

    /** 当前是否处在某节课的监控窗口内：课前 10 分钟 → 下课 */
    fun isNowInWindow(lessons: List<Lesson>, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (lessons.isEmpty()) return true // 没填课表就全时段监控
        val dow = now.dayOfWeek.value // 1..7
        val min = now.hour * 60 + now.minute
        return lessons.any { it.dayOfWeek == dow && min >= it.startMin - 10 && min <= it.endMin }
    }

    /** 当前时刻属于哪个课程的窗口（用于日志显示） */
    fun currentLesson(lessons: List<Lesson>, now: LocalDateTime = LocalDateTime.now()): Lesson? {
        val dow = now.dayOfWeek.value
        val min = now.hour * 60 + now.minute
        return lessons.firstOrNull { it.dayOfWeek == dow && min >= it.startMin - 10 && min <= it.endMin }
    }

    fun nowTime(): LocalTime = LocalTime.now()
}
