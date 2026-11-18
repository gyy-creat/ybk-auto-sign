package com.yg.ybkautosign.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 本地持久化。全部落在 SharedPreferences 里，不联网、不上传。 */
object Store {

    private const val PREF = "ybk_autosign"
    private lateinit var sp: SharedPreferences

    private const val K_CRED = "cred"
    private const val K_COURSES = "courses"
    private const val K_SELECTED = "selected"
    private const val K_TABLE = "timetable"
    private const val K_ENABLED = "enabled"
    private const val K_DEBUG = "debug_raw"
    private const val K_SIGNED = "signed_keys"
    private const val K_POLL_SEC = "poll_sec"
    private const val K_SEL_INITED = "selected_inited_v2"
    private const val K_VIBRATE = "vibrate_on"
    private const val K_VIBRATE_LEVEL = "vibrate_level"
    private const val K_SOUND = "sound_on"
    private const val K_SOUND_SEC = "sound_sec"

    /** 轮询间隔（秒）。用户在界面上改，默认 30 秒。 */
    var pollSec: Int
        get() = sp.getInt(K_POLL_SEC, 30).coerceIn(5, 3600)
        set(value) {
            sp.edit().putInt(K_POLL_SEC, value.coerceIn(5, 3600)).apply()
        }

    /** 开签时是否震动提醒，默认开。 */
    var vibrateOn: Boolean
        get() = sp.getBoolean(K_VIBRATE, true)
        set(value) = sp.edit().putBoolean(K_VIBRATE, value).apply()

    /** 震动强度：0=轻 1=中 2=重，默认重。 */
    var vibrateLevel: Int
        get() = sp.getInt(K_VIBRATE_LEVEL, 2).coerceIn(0, 2)
        set(value) = sp.edit().putInt(K_VIBRATE_LEVEL, value.coerceIn(0, 2)).apply()

    /** 开签时播放闹钟铃声 */
    var soundOn: Boolean
        get() = sp.getBoolean(K_SOUND, true)
        set(value) = sp.edit().putBoolean(K_SOUND, value).apply()

    /** 铃声持续秒数（响这么久自动停，防止一直吵） */
    var soundSec: Int
        get() = sp.getInt(K_SOUND_SEC, 12).coerceIn(3, 60)
        set(value) = sp.edit().putInt(K_SOUND_SEC, value.coerceIn(3, 60)).apply()

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    // -------- 凭据 --------
    var cred: Cred?
        get() = sp.getString(K_CRED, null)?.let {
            runCatching { Cred.fromJson(JSONObject(it)) }.getOrNull()
        }
        set(value) {
            sp.edit().apply {
                if (value == null) remove(K_CRED) else putString(K_CRED, value.toJson().toString())
            }.apply()
        }

    /** 账号密码单独存一份，token 失效时用来无感重登。 */
    var account: String
        get() = cred?.account.orEmpty()
        set(value) {
            val c = cred ?: Cred("", "", value, password, System.currentTimeMillis())
            cred = c.copy(account = value)
        }

    var password: String
        get() = cred?.password.orEmpty()
        set(value) {
            val c = cred ?: Cred("", "", account, value, System.currentTimeMillis())
            cred = c.copy(password = value)
        }

    /** token 失效时清掉，保留账号密码好重登。 */
    fun invalidateToken() {
        cred = cred?.copy(token = "")
    }

    // -------- 课程缓存 --------
    var courses: List<Course>
        get() = runCatching {
            Course.listFromJson(JSONArray(sp.getString(K_COURSES, "[]")))
        }.getOrDefault(emptyList())
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it.toJson()) }
            sp.edit().putString(K_COURSES, arr.toString()).apply()
        }

    // -------- 勾选监控的课程 --------
    var selected: Set<String>
        get() = sp.getStringSet(K_SELECTED, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(K_SELECTED, value).apply()

    /**
     * 是否已经做过「首次勾选」。
     * 用来区分「从没选过」和「用户主动把课全取消了」——以前靠 selected 是否为空来猜，
     * 结果每次重开都变回全选，用户的勾选等于白点。
     */
    var selectedInited: Boolean
        get() = sp.getBoolean(K_SEL_INITED, false)
        set(value) = sp.edit().putBoolean(K_SEL_INITED, value).apply()

    // -------- 课表 --------
    var timetableRaw: String
        get() = sp.getString(K_TABLE, "") ?: ""
        set(value) = sp.edit().putString(K_TABLE, value).apply()

    // -------- 开关 --------
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(value) = sp.edit().putBoolean(K_ENABLED, value).apply()

    var debugRaw: Boolean
        get() = sp.getBoolean(K_DEBUG, false)
        set(value) = sp.edit().putBoolean(K_DEBUG, value).apply()

    // -------- 已签记录（去重） --------
    fun isSigned(key: String): Boolean = signedKeys().contains(key)

    fun markSigned(key: String) {
        val today = today()
        val kept = signedKeys().filter { dayStamp(it) == today }
        sp.edit().putStringSet(K_SIGNED, (kept + key).toSet()).apply()
    }

    private fun signedKeys(): Set<String> = sp.getStringSet(K_SIGNED, emptySet())?.toSet() ?: emptySet()

    private fun dayStamp(key: String): String {
        val parts = key.split('|')
        return if (parts.size >= 3) parts[2] else ""
    }

    fun today(): String = java.time.LocalDate.now().toString()
}
