# 云班课自动签到（YbkAutoSign）

纯本地 Android App。**不用 root、不用电脑、不联网到第三方服务器**——所有数据只存在手机本地。

> ⚠️ 一句话说清能力边界：它是「接口当眼睛 + 无障碍当手」。
> 接口负责 15 秒问一次「老师开签到了吗」；一旦开签，无障碍服务会把云班课 App 拉到前台并点掉签到按钮。
> **纯接口提交签到走不通**——云班课网页端没有学生签到接口，App 端做了加固，所以只能这么绕。

---

## 一、它怎么工作

```
后台服务（前台通知常驻）
   ↓ 每 15 秒（上课时段）或 5 分钟（其他时间）
调 coreapi.mosoteach.cn → 问「这门课现在开签了吗」
   ↓ 开签了
亮屏 → 上滑解锁 → 把云班课 App 拉到前台
   ↓
无障碍服务扫描界面 → 找到「签到」按钮 → 点掉
   ↓
看到「签到成功」→ 结束，弹通知告诉你
```

去重：同一条签到记录（`课程ID|签到ID|日期`）只会触发一次，不会反复点。

---

## 二、装到手机上的完整步骤

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`（约 6.3 MB）

```powershell
# 手机开 USB 调试并连电脑后
E:\android-sdk\platform-tools\adb.exe install -r E:\ai项目\YbkAutoSign\app\build\outputs\apk\debug\app-debug.apk
```

或者直接把 APK 拷进手机点安装（需要允许「安装未知来源应用」）。

装好后按 App 里 ①~⑤ 走：

### ① 登录
填云班课手机号 + 密码，点「登录并拉取课程」。成功了会显示 `已登录：手机号 · token xxxxxxxx… · 本地缓存 N 门课`。

### ② 勾选课程
一个都不勾 = 监控全部课程。建议只勾本学期那几门，轮询更省电。

### ③ 喂课程表（可选）
一行一节，格式：

```
周一 08:00-09:40 高等数学
周三 14:00-15:40 市场营销学
```

填了之后：**上课时段每 15 秒问一次，其他时间 5 分钟一次**。
不填也行：全天按 5 分钟轮询，一样能用，就是耗电多一点、发现得慢一点。

解析规则：
- 星期支持 `周一 / 周1 / 星期一 / mon / 1` 这几种写法
- 时间框自动往前扩 10 分钟（课前 10 分钟就开始盯）
- 课程关键词只是备注，不参与匹配（匹配走课程 ID）

### ④ 授权（**这一步不做，自动签到就是断的**）

| 授权 | 为什么必须 | 怎么开 |
|---|---|---|
| **无障碍服务** | 帮你点签到按钮，没它就只能提醒、点不了 | 点按钮 → 在列表里找「云班课自动签到助手」→ 打开 |
| **悬浮窗权限** | Android 10+ 不让后台 App 启动别的 App，有这个权限才行 | 点按钮 → 允许「显示在其他应用上层」 |
| 忽略电池优化 | 防止系统把你后台杀掉 | 点按钮 → 允许 |
| 通知权限 | 开签了要能弹出来提醒你 | 点按钮 → 允许 |

App 里这两个开关的状态会实时显示：`✅ 无障碍已开启　✅ 悬浮窗已开`。两个都对才能启动监控。

#### ⚠️ 小米 / 红米 / 澎湃 OS（HyperOS）专属坑：无障碍开关点不动

表现：无障碍列表里能看到「云班课自动签到助手」，但开关是灰的，下面写着 **「由受限设置控制」**。

原因不在代码——是系统对**侧载安装**（用 adb 装、没走应用商店）的 App 做的额外限制。服务本身已经正确注册了，
`adb shell dumpsys package com.yg.ybkautosign` 能看到 `BIND_ACCESSIBILITY_SERVICE` 权限和 intent-filter。

三条路，按顺序试：

1. **直接试**：App 里点「① 开启无障碍服务」，如果能打开就完事。
2. **走应用信息页**（最稳）：
   点 App 里的 **「⚠ 小米/红米先点这里：解除权限限制」** → 到应用信息页 → 右上角 **⋮** → **允许应用获取权限**
   → 回 App 再开无障碍。
3. **走无障碍页底部**：设置 → 辅助功能 → 无障碍 → 往下拉到底 → **「权限管控」/「已下载的应用」** → 勾上本 App。

电脑端也可以用 adb 直接解除（需要手机连着电脑）：
```bash
adb shell cmd appops set com.yg.ybkautosign ACCESS_RESTRICTED_SETTINGS allow
```
设完 `adb shell cmd appops get com.yg.ybkautosign ACCESS_RESTRICTED_SETTINGS` 应显示 `allow`。
但这只是解除系统层的限制标记，无障碍开关**仍然要你自己点一次**——adb 没有 WRITE_SECURE_SETTINGS 权限，改不了那个开关。

### ⑤ 开始监控
点「开始监控」，通知栏会出现一条常驻通知。**别去划掉它**，划掉服务就没了（部分系统允许划掉但仍运行，不确定就留着）。

启动前想先确认「账号能不能查到签到位」，点 **「立即检查一次（不开监控）」**：它会立刻跑一轮完整探测
（读 token → 拉课程 → 逐课查 `/checkins/current`），结果直接写进下面的运行日志，不启动后台服务。
日志里出现 `[自检] 这一轮没有开着的签到（正常，说明接口是通的）` 就代表整条链路是好的。

**轮询间隔**在「开始监控」上面那个输入框里改，单位秒，默认 **30**（允许 5–3600）。
改完要停掉监控再重新开始，才会按新间隔跑。日志启动时会自报当前间隔：

```
课表为空：全时段按 30s 轮询
```

⚠️ **每次覆盖安装新 APK 之后，无障碍服务会被系统自动取消勾选**——这是 Android 对「包被替换」的安全策略，
不是 bug。装完新版本必须回 ④ 重新把无障碍打开一次。

---

## 三、手机厂商保活（很重要）

国产 ROM 会杀后台，必须手动放行：

| 品牌 | 设置路径 |
|---|---|
| 小米 / Redmi | 设置 → 应用设置 → 应用管理 → 云班课自动签到 → **省电策略改成「无限制」**、允许自启动、允许后台弹出界面 |
| 华为 / 荣耀 | 手机管家 → 应用启动管理 → 找到本 App → 改成**手动管理**，三个开关全开 |
| OPPO / 一加 / realme | 设置 → 电池 → 应用耗电管理 → 本 App → **允许后台运行**、允许自启动、允许关联启动 |
| vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许；i管家 → 自启动管理 → 允许 |
| 三星 | 设置 → 电池 → 后台使用限制 → 从「休眠应用」里移出 |

**还有一个前提**：自动流程里包含「亮屏 + 上滑解锁」。如果你锁屏设了密码/指纹/图案，上滑解不开，流程就卡在锁屏。
要全自动，锁屏得设成「无密码，直接滑动解锁」。

---

## 四、你会看到什么样的日志

```
─── 监控已启动 ───
课表已载入 4 节，窗口内每 15s 轮询
[登录] 成功，用户 172313F1…
[课程] 共 18 门，其中当前学期 4 门
[发现] 市场营销学 开签了！类型=SIMPLE id=26793237
[执行] 已交给无障碍服务自动点击
[无障碍] 开始自动签到：市场营销学 开签（SIMPLE）
[无障碍] 已拉起云班课，等待签到入口出现…
[无障碍] 第 1 次尝试点击「签到」→ 已点击
[无障碍] 结束：签到成功
```

出问题时把这段日志发我，基本能定位。

---

## 五、接口（逆向出来的，记录备查）

域名 `https://coreapi.mosoteach.cn`，公共请求头：

```
Content-Type: application/json;charset=utf-8
X-client-app-id: MTWEB
X-client-version: 6.0.0
X-security-type: SECURITY_TYPE_TOKEN
X-token: <登录返回的 token>          # 登录接口本身不带
```

| 用途 | 接口 |
|---|---|
| 登录 | `POST /passports/account-login`，body `{"account","password"}`（明文可直传，服务端不强制 wasm 加密） |
| 课程列表 | `GET /ccs/joined` → `clazzCourses[].{id, course.name, term.isCurrent}` |
| 签到列表 | `GET /ccs/{ccId}/checkins` → `data.rows[]`，`checkinFlag` 是「**我签没签**」不是「开没开」 |
| **当前是否开签** | `GET /ccs/{ccId}/checkins/current`，未开签时返回 `errorCode: err.cc.noOpenCheckin` |

注意 `checkinFlag` 的坑：它是「本人是否已签」，不是「签到是否开启」。判断开签必须用 `/checkins/current`。

---

## 六、自己编译

本机环境（已配好，实测可用）：

- JDK 17：`C:\Program Files\Java\jdk-17`
- Gradle 8.7：`E:\ai项目\.toolchain\gradle-8.7`
- Android SDK：`E:\android-sdk`（platform-tools / platforms;android-34 / build-tools;34.0.0）

```bash
cmd //c "set \"JAVA_HOME=C:\Program Files\Java\jdk-17\" && E:\ai项目\.toolchain\gradle-8.7\bin\gradle.bat -p E:\ai项目\YbkAutoSign assembleDebug --console=plain"
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

踩过的坑：

| 报错 | 原因 | 处理 |
|---|---|---|
| `project path contains non-ASCII characters` | 工程在 `E:\ai项目`（中文目录） | `gradle.properties` 里加 `android.overridePathCheck=true`，aapt2 实测正常 |
| `resource mipmap/ic_launcher not found` | 缺图标 | 用 `mipmap-anydpi-v26` 的 adaptive-icon（minSdk 26 不需要 png） |
| MSYS 下调 gradle 报缺 `cygpath` | MSYS 环境的 gradle 脚本跑不了 | 统一走 `cmd //c` + `gradle.bat` |
| MSYS 下 `cmd /c` 直接进交互模式 | MSYS 把 `/c` 当路径转换了 | 用 `cmd //c`（双斜杠） |
| `Unresolved reference: App` | 跨包引用 | Notifier.kt 补 `import com.yg.ybkautosign.App` |

---

## 七、代码结构

```
app/src/main/java/com/yg/ybkautosign/
├─ App.kt                          Application，持有全局 ctx
├─ MainActivity.kt                 单页界面：登录/课程/课表/授权/启停/日志
├─ core/
│  ├─ Model.kt                     Cred / Course / Lesson / OpenCheckin
│  ├─ Store.kt                     SharedPreferences 持久化（凭据、课程、课表、去重键）
│  ├─ LogBus.kt                    日志流，界面实时刷
│  ├─ Timetable.kt                 课表解析 + 上课窗口判断
│  ├─ Notifier.kt                  通知渠道 + 开签提醒
│  └─ SignEngine.kt                调度中枢：发现开签 → 派无障碍
├─ net/
│  └─ CoreApi.kt                   coreapi.mosoteach.cn 全部接口
├─ service/
│  ├─ AutoSignService.kt           前台服务，按窗口轮询 SignEngine.round()
│  └─ SignAccessibilityService.kt  无障碍：亮屏/解锁/拉 App/找签到/点
└─ receiver/
   └─ BootReceiver.kt              开机自启恢复监控
```

---

## 八、已知限制（先说清楚，省得踩坑）

| 情况 | 能不能自动 |
|---|---|
| 普通一键签到（SIMPLE） | ✅ 能 |
| 位置签到 | ✅ 能（点按钮进 App 后由 App 自己上报位置，前提是你授权了定位；**位置对不对不管**） |
| 手势签到（画图案） | ❌ 不能（你一开始就说不要这个） |
| 数字/口令签到 | ❌ 不能 |
| 扫码签到 | ❌ 不能 |
| 锁屏有密码 | ❌ 自动流程卡在锁屏，只能收到提醒后自己解 |
| 云班课 App 改版换了按钮文案 | ⚠️ 可能失效，需要跟着调匹配规则 |

另外：这套东西依赖「无障碍能看到云班课界面」。如果云班课 App 之后也给无障碍加防护（`FLAG_SECURE` 或禁止读取节点），这条路会断。
