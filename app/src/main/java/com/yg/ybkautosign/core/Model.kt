package com.yg.ybkautosign.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 登录会话。新版云班课走 coreapi + X-token，不再有 access_id/access_secret 那套签名。
 * token 过期就整条链路 401，用账号密码重新换一个。
 */
data class Cred(
    val userId: String,
    val token: String,
    val account: String = "",
    val password: String = "",
    val savedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("user_id", userId)
        put("token", token)
        put("account", account)
        put("password", password)
        put("saved_at", savedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Cred(
            userId = o.optString("user_id"),
            token = o.optString("token"),
            account = o.optString("account"),
            password = o.optString("password"),
            savedAt = o.optLong("saved_at")
        )
    }
}

/** 课程（云班课里的一个班）。id 是大写 UUID，签到接口要用它。 */
data class Course(
    val id: String,
    val name: String,
    val termCurrent: Boolean
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("current", termCurrent)
    }

    companion object {
        fun fromJson(o: JSONObject) = Course(
            id = o.optString("id"),
            name = o.optString("name"),
            // 写出去用 "current"，读的时候还要兼容老格式 "term_current"="Y"
            termCurrent = o.optBoolean("current", false) || o.optString("term_current") == "Y"
        )

        fun listFromJson(arr: JSONArray?): List<Course> {
            if (arr == null) return emptyList()
            val out = ArrayList<Course>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = fromJson(o)
                if (c.id.isNotEmpty()) out.add(c)
            }
            return out
        }
    }
}

/** 课表里的一条：周几 + 起始分钟 + 结束分钟 + 课程关键词（用于匹配 Course） */
data class Lesson(
    val dayOfWeek: Int,   // 1=周一 ... 7=周日
    val startMin: Int,    // 从 00:00 起算的分钟数
    val endMin: Int,
    val keyword: String
)

/** 当前开着的签到。id 用于去重，type 是 SIMPLE / CLOCKIN 等。 */
data class OpenCheckin(
    val id: String,
    val type: String,
    /** checkinFlag == "Y" 表示「我已经签到了」，N 表示「开签了但我还没签」。 */
    val signed: Boolean,
    val openTime: String,
    val raw: String
)
