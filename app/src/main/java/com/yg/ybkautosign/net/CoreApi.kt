package com.yg.ybkautosign.net

import com.yg.ybkautosign.core.Course
import com.yg.ybkautosign.core.Cred
import com.yg.ybkautosign.core.OpenCheckin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/**
 * 云班课新版 Web API（coreapi）。
 *
 * 已实测通过的链路：
 *   1. POST /passports/account-login  {"account","password"}  -> {user, token, status}
 *      —— 明文密码直接可用，不需要网页那套 wasm 加密。
 *   2. GET  /ccs/joined                                       -> {clazzCourses:[...]}
 *   3. GET  /ccs/{ccId}/checkins/current                       -> 开签时返回签到对象；
 *      未开签时返回 {"errorCode":"err.cc.noOpenCheckin", ...}
 *
 * 注意：网页端没有「学生提交签到」的接口（签到提交只在 App 里），
 * 所以这里只负责「发现开签」，真正点签到由无障碍服务完成。
 */
object CoreApi {

    private const val BASE = "https://coreapi.mosoteach.cn"
    private val MEDIA_JSON = "application/json;charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- 基础

    private fun builder(path: String, token: String?): Request.Builder {
        val b = Request.Builder().url(BASE + path)
        b.header("Content-Type", "application/json;charset=utf-8")
        b.header("X-client-app-id", "MTWEB")
        b.header("X-client-version", "6.0.0")
        b.header("X-security-type", "SECURITY_TYPE_TOKEN")
        b.header("Accept", "application/json, text/plain, */*")
        if (!token.isNullOrBlank()) b.header("X-token", token)
        return b
    }

    private fun exec(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) throw ApiException("HTTP ${resp.code} 空响应")
            val o = runCatching { JSONObject(text) }.getOrElse {
                throw ApiException("返回不是 JSON: " + text.take(120))
            }
            return o
        }
    }

    private fun errText(o: JSONObject, fallback: String): String {
        val msg = o.optString("errorMessage")
        if (msg.isNotEmpty()) return msg
        val code = o.optString("errorCode")
        return if (code.isNotEmpty()) "$fallback（$code）" else fallback
    }

    private fun ts(): Long = System.currentTimeMillis()

    // ---------------------------------------------------------------- 登录

    /**
     * 账号密码登录。服务端接受明文 password，返回顶层 token。
     * 失败抛 ApiException，message 是可以直接给用户看的中文。
     */
    fun login(account: String, password: String): Cred {
        val body = JSONObject().apply {
            put("account", account)
            put("password", password)
        }.toString().toRequestBody(MEDIA_JSON)

        val o = exec(builder("/passports/account-login", null).post(body).build())

        if (!o.optBoolean("status", false)) {
            throw ApiException(errText(o, "登录失败"))
        }
        val token = o.optString("token")
        if (token.isBlank()) throw ApiException("登录成功但没拿到 token")

        val user = o.optJSONObject("user") ?: JSONObject()
        return Cred(
            userId = user.optString("userId"),
            token = token,
            account = account,
            password = password
        )
    }

    // ---------------------------------------------------------------- 课程

    /** 拉取加入的班课。只保留能拿到 id 的条目。 */
    fun joinedCourses(cred: Cred): List<Course> {
        val req = builder("/ccs/joined?_ts=${ts()}", cred.token).get().build()
        val o = exec(req)
        if (!o.optBoolean("status", false) && o.optJSONArray("clazzCourses") == null) {
            throw ApiException(errText(o, "拉课程列表失败"))
        }
        val arr: JSONArray = o.optJSONArray("clazzCourses") ?: return emptyList()
        val out = ArrayList<Course>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val courseObj = item.optJSONObject("course")
            val name = courseObj?.optString("name").orEmpty().ifBlank {
                item.optString("name")
            }
            val term = item.optJSONObject("term")
            val current = term?.optString("isCurrent") == "Y"
            out.add(Course(id = id, name = name, termCurrent = current))
        }
        return out
    }

    // ---------------------------------------------------------------- 打开中的签到

    /**
     * 查这门课此刻有没有开着的签到。
     *
     * @return 有则返回 OpenCheckin，没有（老师没开 / 已结束）返回 null。
     */
    /**
     * 当前开着的签到。实测真实返回（2026-11-11 实抓）：
     *   开签：{"checkin":{"deadlineTime":null,"checkinTime":"...","id":27187420,
     *          "checkinFlag":"Y","type":"SIMPLE","openTime":"...","points":""},"status":true}
     *   未开：{"errorCode":"err.cc.noOpenCheckin","errorMessage":"老师还没有开始签到或签到已结束","status":false}
     *   已结课：{"errorCode":"err.cc.closed","errorMessage":"操作无效，班课已结束","status":false}
     * 注意 checkinFlag 是「我签没签」，不是「有没有开签」——开没开看 status 和 errorCode。
     */
    fun currentCheckin(cred: Cred, ccId: String): OpenCheckin? {
        val req = builder("/ccs/$ccId/checkins/current?_ts=${ts()}", cred.token).get().build()
        val o = try {
            exec(req)
        } catch (e: ApiException) {
            return null
        }

        if (o.optString("errorCode").isNotEmpty()) return null
        if (!o.optBoolean("status", true)) return null

        // 真实结构是顶层 checkin，其它版本可能叫 data
        val node = o.optJSONObject("checkin") ?: o.optJSONObject("data") ?: o
        val id = pickString(node, "id", "checkinId", "checkin_id") ?: "unknown"
        val type = pickString(node, "type", "checkinType") ?: "SIMPLE"
        val signed = node.optString("checkinFlag") == "Y"
        val openTime = node.optString("openTime")

        return OpenCheckin(id = id, type = type, signed = signed, openTime = openTime, raw = o.toString())
    }

    /** 在若干候选键里取第一个非空字符串，取不到返回 null。 */
    private fun pickString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    /** 校验 token 是否还有效。有效返回 true。 */
    fun ping(cred: Cred): Boolean = try {
        val o = exec(builder("/users/my-profile?_ts=${ts()}", cred.token).get().build())
        o.optBoolean("status", false) || o.has("user")
    } catch (e: Exception) {
        false
    }
}
