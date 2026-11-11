package com.yg.ybkautosign.core

import com.yg.ybkautosign.net.ApiException
import com.yg.ybkautosign.net.CoreApi
import com.yg.ybkautosign.service.SignAccessibilityService

/**
 * 调度中枢。
 *
 * 新链路（已实测）：
 *   账号密码 --CoreApi.login--> token
 *   token  --/ccs/joined-->     课程列表
 *   token  --/ccs/{id}/checkins/current--> 现在有没有开签到
 *   开签了 → 通知无障碍服务去 App 里点按钮
 *
 * 纯 API 的「提交签到」走不通：网页端没有学生签到接口，App 端有加固。
 * 所以 API 只负责「发现」，执行交给无障碍。
 */
object SignEngine {

    /** 登录并落盘。失败抛 ApiException。 */
    suspend fun login(account: String, password: String): List<Course> = runCatching {
        val cred = CoreApi.login(account, password)
        Store.cred = cred
        LogBus.log("[登录] 成功，用户 ${cred.userId.take(8)}…")
        val courses = CoreApi.joinedCourses(cred)
        Store.courses = courses
        val cur = courses.count { it.termCurrent }
        LogBus.log("[课程] 共 ${courses.size} 门，其中当前学期 $cur 门")
        courses
    }.getOrElse { e ->
        val msg = if (e is ApiException) e.message ?: "登录失败" else "登录异常：${e.message}"
        LogBus.log("[登录] 失败：$msg")
        throw ApiException(msg)
    }

    /** 有 token 就复用，没有（或 force=true）就重新登录。 */
    suspend fun ensureCred(force: Boolean = false): Cred {
        val saved = Store.cred
        if (!force && saved != null && saved.token.isNotBlank()) return saved

        val account = saved?.account.orEmpty().ifBlank { Store.account }
        val password = saved?.password.orEmpty().ifBlank { Store.password }
        if (account.isBlank() || password.isBlank()) {
            throw ApiException("还没有登录过，请先输入账号密码登录")
        }
        val cred = CoreApi.login(account, password)
        Store.cred = cred
        LogBus.log("[登录] token 已刷新")
        return cred
    }

    /** 刷新课程列表。 */
    suspend fun refreshCourses(): List<Course> {
        val cred = ensureCred()
        val courses = CoreApi.joinedCourses(cred)
        Store.courses = courses
        return courses
    }

    /** 要监控的课程：严格按用户勾选。老版本升级上来（没勾过）才退化成全选。 */
    fun monitoredCourses(): List<Course> {
        val all = Store.courses
        if (!Store.selectedInited) return all
        val sel = Store.selected
        return all.filter { sel.contains(it.id) }
    }

    /**
     * 跑一轮探测。返回「本轮发现了几个开着的签到」。
     * 发现了就交给无障碍去点。
     */
    suspend fun round(): Int {
        val cred = ensureCred()
        val courses = monitoredCourses()
        if (courses.isEmpty()) {
            LogBus.log("[轮询] 没有可监控的课程，先去勾选课程")
            return 0
        }

        var found = 0
        for (c in courses) {
            val open = try {
                CoreApi.currentCheckin(cred, c.id)
            } catch (e: ApiException) {
                if (isAuthError(e.message)) {
                    LogBus.log("[轮询] token 失效，重新登录")
                    ensureCred(force = true)
                } else {
                    LogBus.log("[轮询] ${c.name} 查询失败：${e.message}")
                }
                null
            } catch (e: Exception) {
                null
            }

            if (open == null) continue

            val key = "${c.id}|${open.id}|${Store.today()}"

            // checkinFlag=Y 表示我已经签过了，不用再去点
            if (open.signed) {
                if (!Store.isSigned(key)) {
                    Store.markSigned(key)
                    LogBus.log("[已签] ${c.name} 已签到（checkinFlag=Y），跳过")
                }
                continue
            }

            if (Store.isSigned(key)) continue
            Store.markSigned(key)

            found++
            LogBus.log("[发现] ${c.name} 开签了！类型=${open.type} id=${open.id} 开始于 ${open.openTime}")
            dispatch(c, open)
        }
        return found
    }

    /** 交给无障碍去执行；无障碍没连上就只发通知提醒手动点。 */
    private fun dispatch(course: Course, open: OpenCheckin) {
        val reason = "${course.name} 开签（${open.type.ifBlank { "未知类型" }}）"
        val ok = SignAccessibilityService.requestSign(reason)
        if (ok) {
            LogBus.log("[执行] 已交给无障碍服务自动点击")
        } else {
            LogBus.log("[执行] 无障碍服务没连上，只能提醒手动签")
        }
        Notifier.alertCheckin(course.name, autoStarted = ok)
    }

    private fun isAuthError(msg: String?): Boolean {
        if (msg == null) return false
        val m = msg.lowercase()
        return m.contains("401") || m.contains("403") ||
            m.contains("unauthorized") || m.contains("token") ||
            m.contains("登录") || m.contains("权限")
    }
}
