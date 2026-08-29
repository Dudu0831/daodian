# 到点 · 技术设计文档

> v1.2 · 单人单设备 · 目标机 荣耀 MagicOS / Android 15

一句话：**AI 只负责把一句话翻译成一条记录，绝不负责在正确的时间叫醒你。**

---

## 01 目标与非目标

非目标砍掉的复杂度，比目标带来的还多。

**做**

- 一句自然语言 → 一条精确定时的提醒
- 相对时间（「二十分钟后」）、绝对时间（「9 月 2 号下午三点」）、简单重复（「每周二早上」）
- 到点强提醒：响铃 + 锁屏弹出 + 可稍后
- 断网、API key 失效、供应商跑路时，**已排期的提醒不受任何影响**
- 任何 AI 解析结果都能手动改；完全不用 AI 也能建提醒

**不做**

- 多用户、账号体系、云同步、跨设备 —— 因此没有后端，一行服务端代码都不写
- 上架任何应用商店 —— 因此不受 Google Play 权限政策约束（见 §05）
- 日历双向同步、分享、协作
- 项目管理（子任务、标签、看板、优先级矩阵）

**前置约束**：目标机是国行荣耀，**没有 Google Play 服务**。FCM 推送和任何依赖 GMS 的后台调度方案全部不可用。这条约束不是限制，是验证 —— 它从一开始就排除了「服务端定时推送」，把本地闹钟确立为唯一正确的实现。

---

## 02 总体架构

```
                    ┌─────────── 本地可靠区 · 飞行模式下完整工作 ───────────┐
                    │                                                      │
你说的话 ──► LLM 解析 ──┼─► Room ──► AlarmManager ──► 到点广播 ──► 通知·响铃 │
    │      (允许失败)   │  (唯一真相)  setAlarmClock()                      │
    │                  │     ▲                                             │
    └─► 手动添加/编辑 ───┼─────┘                                             │
        (逃生舱)        └──────────────────────────────────────────────────┘
```

网络只参与「把一句话变成一条记录」这一步。记录一旦落进 Room，触发链路就完全由 `AlarmManager` 和 `BroadcastReceiver` 承担 —— 拔掉网络、删掉 API key、供应商倒闭，已排期的提醒照响。

虚线那条是逃生舱：**不经过任何 AI 也能建一条完整提醒**，这条路径必须始终可用。

两条路径的可靠性要求差一个数量级，工程投入也应该差一个数量级：

| 路径 | 失败会怎样 | 应对 |
|---|---|---|
| **解析路径**（网络） | 你当场就看得见 —— 界面报错或时间不对，立刻能改 | 重试、降级、手动兜底。**允许失败** |
| **触发路径**（本地） | 你三天后才发现房租忘了交 | 四重重排 + 兜底巡检 + 投递日志。**不允许失败** |

这个不对称是整份文档的主线，也是 §10 里程碑排序的唯一理由。

---

## 03 技术栈

| 层 | 选择 | 说明 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose | Material 3，单 Activity |
| SDK | minSdk 34 · targetSdk 35 · compileSdk 35 | 见 决策 3.2 |
| 持久化 | Room（KSP） | 两张表，见 §04 |
| 配置 / 密钥 | DataStore (Preferences) | `allowBackup="false"` |
| 定时 | `AlarmManager.setAlarmClock()` | 核心，见 §05 |
| 兜底 | WorkManager 周期任务 | 6 小时一次巡检 |
| 网络 / LLM | `openai-java`（OkHttp backend） | 官方 SDK，`baseUrl` 指向任意兼容 endpoint |
| 日期时间 | `java.time` | 原生可用，不需要 desugaring |

### 决策 3.1 · 已修订：用 openai-java 官方 SDK

- **选**：`openai-java`，OkHttp backend，`baseUrl` 可改
- **弃**：~~OkHttp + kotlinx.serialization 手写客户端~~

好处是实打实的：请求/响应类型由官方维护，新参数跟进不用自己追文档，`response_format` 这类结构有现成类型可用。

**但有两个前提要在 M2 第一步就验掉**：

1. **Android 可用性** —— 这类 JVM SDK 有的走 `java.net.http.HttpClient`（Android 上根本没有）。必须确认走的是 OkHttp backend，并实际在真机上跑通，同时看 R8 之后的包体增量。
2. **非 OpenAI 供应商的兼容性** —— SDK 会按 OpenAI 的完整形状发字段，某些供应商见到不认识的字段会直接 400。「用官方 SDK」和「兼容性更高」不完全等价，实测为准。

好消息是这个决定几乎不花钱：SDK 藏在 `ReminderParser` 接口后面（§6.1）。真撞上任何一条，换回手写客户端只动一个类。

### 决策 3.2 · 已修订：minSdk 34

原定 33，理由是「33 是 `USE_EXACT_ALARM` 和 `POST_NOTIFICATIONS` 落地的版本，定在这里就不用写任何 `Build.VERSION` 分支」。

写代码时发现这个理由本身要求的是 **34**：

- `NotificationManager.canUseFullScreenIntent()` —— API 34
- `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` —— API 34

停在 33 就得为全屏 intent 写版本分支，正好违背了当初定 33 的目的。目标机是 Android 15，改成 34 零损失。

---

## 04 数据模型

### 4.1 提醒

```kotlin
@Entity(tableName = "reminders", indices = [Index("nextTriggerAt"), Index("status")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,              // 「交房租」—— 去掉「提醒我」这类壳
    val note: String? = null,
    val rawInput: String,           // 你的原话，永久保留
    val nextTriggerAt: Long,        // epoch millis，唯一的排期依据
    val rrule: String? = null,      // RFC 5545 子集；null = 一次性
    val zoneId: String,             // 创建时所在时区
    val localTime: String? = null,  // "08:00"，墙钟锚定时用来重算
    val wallClockAnchored: Boolean, // 见 §7.1
    val status: ReminderStatus,     // SCHEDULED / FIRED / DONE / CANCELLED
    val parsedBy: String? = null,   // "deepseek-chat"，回溯是哪家解析错的
    val createdAt: Long,
    val updatedAt: Long
)
```

`rawInput` 和 `parsedBy` 是刻意留的：解析出错时能对照原话看模型怎么想歪的，换供应商后也能拿老句子重跑做对比。

### 4.2 投递日志

```kotlin
@Entity(tableName = "fire_log", indices = [Index("firedAt")])
data class FireLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val title: String,
    val scheduledAt: Long,   // 本该响的时刻
    val firedAt: Long,       // 实际响的时刻
    val source: FireSource   // ALARM / SWEEP / BOOT_CATCHUP / MANUAL_TEST
)
```

`firedAt - scheduledAt` 就是漂移量。这张表看起来可有可无，实际上它是整个项目里**唯一能把「感觉挺准的」变成数据的东西** —— §09 的验收、§11 的最大风险，全靠它。

`source` 同样关键：日志里大量出现 `SWEEP`，说明主闹钟路径正在被 ROM 掐掉、兜底网在替它干活，这是个必须立刻处理的信号。

---

## 05 调度层

项目的核心，也是唯一不允许出错的部分。

### 5.1 决策：`setAlarmClock()`，不是 `setExactAndAllowWhileIdle()`

`setAlarmClock` 是系统里优先级最高的一档 —— 面向「用户明确设定的闹钟」，明确豁免于 Doze 和应用待机分桶。`setExactAndAllowWhileIdle` 虽然也能在 Doze 下触发，但受频率限制、在低电量和深度 Doze 下仍可能被延后。

**代价**：系统状态栏会常驻闹钟图标，并在下拉栏显示最近一次时间。对提醒 app 来说算功能不算副作用。（已确认接受。）

在荣耀这种激进 ROM 上，这个优先级差别很可能就是「准时响」和「第二天早上才收到一堆积压通知」的区别。

### 5.2 权限

```xml
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.INTERNET" />
```

`USE_EXACT_ALARM` 是**普通权限，安装即授予**，不需要写任何运行时引导。相比之下 `SCHEDULE_EXACT_ALARM` 在 Android 14 起对新装应用默认拒绝，得跳设置页求用户手动开。Google Play 只把 `USE_EXACT_ALARM` 批给闹钟/日历类应用 —— 我们不上架，直接用。这是 §01「不上架」换来的第一笔实惠。

只有 `POST_NOTIFICATIONS` 需要运行时申请。

### 5.3 排期是易失的

整个调度层唯一需要真正理解的概念：**`AlarmManager` 里的排期不是持久化状态，它是 Room 的一份缓存，而且随时会掉。**

| 事件 | 对已排闹钟的影响 |
|---|---|
| 设备重启 | 全部清空 |
| 应用被更新 / 重装 | 全部清空 |
| 被 ROM 强杀 | 通常保留，**但不保证** |
| 时区变更 | 时间戳没变，但「早上 8 点」的含义变了 |

```
        ┌──────────────┐   setAlarmClock() 逐条排期   ┌────────────────────┐
        │     Room     │ ───────────────────────────► │ AlarmManager 排期  │
        │  唯一真相·持久 │                              │  易失·重启即清空    │
        └──────────────┘                              └────────────────────┘
               ▲            ┌──────────────────┐                │
               │            │  重排触发源       │                │ 到点·系统广播
  写回          │            │ ·新增/编辑/完成   │                ▼
  nextTriggerAt│            │ ·BOOT_COMPLETED  │       ┌────────────────────┐
               │            │ ·TIMEZONE_CHANGED│       │   AlarmReceiver    │
        ┌──────────────┐    │ ·WorkManager 6h  │       │  goAsync()·限 10s  │
        │  通知 · 响铃  │◄───└──────────────────┘◄──────└────────────────────┘
        │  写 FireLog  │      发通知 + 按 RRULE 算下次
        └──────────────┘
```

真相永远在 Room，四个触发源负责把排期重新推回一致。任何时候都要能从 Room 全量重建 —— 这条不变式一旦破了，漏提醒就成了随机事件。

| 触发源 | 实现 | 覆盖的失效场景 |
|---|---|---|
| 新增 / 编辑 / 完成 | 直接调用 Rescheduler | 正常流程 |
| `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` | BroadcastReceiver | 重启、应用更新 |
| `TIMEZONE_CHANGED` / `TIME_SET` | BroadcastReceiver | 出国、手动改系统时间 |
| WorkManager 周期任务 | 6 小时一次 | 被强杀后的兜底 |

### 5.4 兜底巡检

- **补发**：查 `status = SCHEDULED` 且 `nextTriggerAt < now` 的漏网记录 → 立刻发通知（标注补发）+ 写 `FireLog(source = SWEEP)` + 算下次
- **补排**：查未来 24 小时内的记录 → 用 `PendingIntent.FLAG_NO_CREATE` 探测闹钟是否还在，不在就补排

**别高估它**：WorkManager 自己也会被 MagicOS 掐掉。它是**第二道网，不是保险**。主路径必须自己站得住，巡检只负责把偶发的漏网变成「迟到几小时」而不是「彻底没有」。

### 5.5 触发链路

`AlarmReceiver.onReceive()` 里按顺序做四件事，全部在 `goAsync()` 内完成：

1. 写 `FireLog`（先写，保证即使后面崩了也留下证据）
2. 发通知
3. `rrule != null` → 算下次 → 更新 Room → 重排
4. 否则 `status = FIRED`

`onReceive` 有约 10 秒上限，`goAsync()` 也只延长到几十秒。**这里绝对不能发网络请求。**

### 5.6 通知

- 渠道 `reminders_v1`，`IMPORTANCE_HIGH`。**渠道创建后铃声改不了**，要换声音必须换 channel id，所以 id 里带版本号
- `setCategory(CATEGORY_ALARM)` + `setFullScreenIntent(...)` → 锁屏直接弹出
- Android 14+ 收紧了全屏 intent：先用 `canUseFullScreenIntent()` 检查，拿不到就降级成普通 heads-up，不要崩
- 两个 action：「完成」「稍后 10 分钟」

---

## 06 AI 解析层

整层可以被一个手动编辑页完全替代 —— 这是设计它的前提，不是妥协。

### 6.1 接口

```kotlin
interface ReminderParser {
    suspend fun parse(input: String, now: ZonedDateTime): ParseResult
}

sealed interface ParseResult {
    data class Ok(val plan: ReminderPlan) : ParseResult
    data class NeedsClarification(val question: String, val options: List<String>) : ParseResult
    data class Failed(val reason: String) : ParseResult
}

data class ProviderProfile(
    val name: String,
    val baseUrl: String,   // "https://api.deepseek.com/v1"
    val model: String,
    val apiKey: String,
    val jsonMode: JsonMode // STRICT_SCHEMA / JSON_OBJECT / PROMPT_ONLY
)
```

唯一的实现是 `OpenAiCompatParser(profile)`。换供应商 = 在设置页改这四个字段。

### 6.2 结构化输出的三档降级

各家对 `response_format` 的支持完全不一致，**别赌**：

| 档位 | 请求参数 | 适用 |
|---|---|---|
| `STRICT_SCHEMA` | `response_format: {type:"json_schema", …, strict:true}` | 明确声明支持的供应商 |
| `JSON_OBJECT` | `response_format: {type:"json_object"}` | 多数国内供应商 |
| `PROMPT_ONLY` | 不传，纯靠提示词约束 | 本地小模型、老接口 |

**三档都必须把 JSON Schema 原文写进 system prompt。** 高档位只是多一层服务端保证，不是省掉提示词约束的理由。

解析端一律容错：剥掉 ` ```json ` 围栏 → 取第一个 `{` 到最后一个 `}` → 宽松反序列化。

### 6.3 输出结构

```kotlin
data class ReminderPlan(
    val title: String,
    val note: String? = null,
    val firstTriggerAt: String,   // "2026-09-02T15:00:00+08:00"
    val basis: String,            // 推算依据："now + 5d, 15:00"
    val rrule: String? = null,
    val wallClockAnchored: Boolean = true,
    val confidence: Double,
    val clarifyingQuestion: String? = null
)
```

`basis` 是刻意加的：逼模型把推算过程显式写出来，既提高准确率，也让你在出错时一眼看出它怎么想歪的。不入库主表，但要显示在确认卡上。

### 6.4 提示词的两条硬规则

1. **必须给模型当前时刻、时区和星期几。** 只给日期不给星期，「下周三」必错。格式固定：`当前时刻：2026-08-28T21:30:00+08:00（周五），时区 Asia/Shanghai`
2. **时间戳放 user message，不要放 system prompt。** 主流供应商都做前缀缓存，system prompt 必须逐字节稳定才能命中。

### 6.5 校验闸门

模型返回之后、写库之前，无条件过一遍：

| 条件 | 处理 |
|---|---|
| `firstTriggerAt` 解析失败 | `Failed`，转手动 |
| `firstTriggerAt ≤ now` | 拒绝，转确认 —— **最常见的错误形态** |
| `firstTriggerAt > now + 5 年` | 拒绝，转确认 |
| `rrule` 超出支持子集 | 降级成一次性并明确提示 |
| `confidence < 0.6` 或有 `clarifyingQuestion` | 走确认 UI |

这道闸门比换一个更强的模型值钱得多：它把「静默出错」变成「当场问你一句」。

### 6.6 成本

每次解析约 700–900 tokens，system prompt 占大头且大部分能命中前缀缓存。按每天 10 条估，国内供应商是每月几毛到几块的量级。

**这个用量下成本不该成为选型依据**，该看的是接口稳定性、延迟、以及对 `response_format` 的支持程度。

---

## 07 时区与重复规则

### 7.1 墙钟锚定 vs 绝对时刻

| 说法 | 语义 | 飞一趟之后 |
|---|---|---|
| 「每天早上 8 点吃药」 | **墙钟锚定** | 到哪儿都是当地早上 8 点 |
| 「9 月 2 号 15:00 的会」 | **绝对时刻** | 还是那一瞬间，显示成当地时间 |

`wallClockAnchored = true` 时，`nextTriggerAt` 只是「`localTime` + 当前时区」的一个投影。所以 `TIMEZONE_CHANGED` 触发的**不是简单重排，而是重算** —— 拿 `localTime` 和 `rrule` 在新时区下重新求值再写回。这是两种重排路径里唯一有区别的地方，务必分开写。

默认规则：**有 `rrule` 的默认墙钟锚定，一次性的默认绝对时刻。**

### 7.2 RRULE 支持子集

```
FREQ = DAILY | WEEKLY | MONTHLY | YEARLY
INTERVAL = n
BYDAY = MO,TU,WE,TH,FR,SA,SU      (仅 WEEKLY)
BYMONTHDAY = n                     (仅 MONTHLY)
COUNT = n | UNTIL = <ISO-8601>
```

超出子集的一律降级成一次性并提示。用 `java.time` 手写求值器，不引第三方库。

**边界情况必须写死并同步进提示词**：`BYMONTHDAY=31` 落在只有 30 天的月份 → **顺延到该月最后一天**（不跳过该月）。不写清楚的话模型和求值器各按各的理解走，会得到「有些月份不提醒」这种极难复现的 bug。

---

## 08 界面

| 页面 | 内容 |
|---|---|
| **主页** | 顶部一个大输入框（右侧麦克风按钮直接调系统输入法语音，不自己接语音识别）。下面按「今天 / 明天 / 本周 / 以后」分组的列表 |
| **确认卡** | 标题、人话时间（「9月2日 周三 15:00 · 5 天后」）、重复规则、`basis`、原话。两个按钮：「就这样」「改一下」 |
| **编辑页** | 手动改标题、时间、重复规则、墙钟/绝对开关。**逃生舱，必须能完全脱离 AI 建成一条完整提醒** |
| **设置页** | 供应商配置 + 测试连接、通知铃声、权限体检、投递日志入口 |

**投递日志页**：每条显示「应响 → 实响 → 漂移 `+3s`」和来源标签。它是判断 §09 保活配置有没有生效的唯一客观依据，别做成调试开关藏起来。

---

## 09 保活与验收

本项目最大的技术风险。代码解决不了，只能靠一次性手动配置 + 长时间实测。

### 9.1 app 内的权限体检

| 检查项 | 检测方式 |
|---|---|
| 精确闹钟 | `AlarmManager.canScheduleExactAlarms()` |
| 通知权限 | `NotificationManagerCompat.areNotificationsEnabled()` |
| 渠道未被静音 | `channel.importance >= IMPORTANCE_DEFAULT` |
| 电池优化白名单 | `PowerManager.isIgnoringBatteryOptimizations()` |
| 全屏 intent | `NotificationManager.canUseFullScreenIntent()` |

### 9.2 系统层面要手动设的

MagicOS 各版本菜单名有出入，按关键词找：

- **应用启动管理** → 找到本 app → 关掉「自动管理」→ 三个开关全开（允许自启动 / 允许关联启动 / 允许后台活动）。**这一项最关键，且没有公开 API 可以检测。**
- **电池** → 取消对本 app 的省电策略 / 后台耗电限制
- **最近任务**界面 → 下拉本 app 的卡片 → 加锁
- **通知** → 允许「横幅」「锁屏显示」「铃声」

### 9.3 验收标准

验收**不是**「点一下能响」，是**放置测试**：

排 20 条覆盖未来 48 小时的提醒（**必须包含凌晨时段**，那是 Doze 最深的时候），手机正常揣兜里、正常用、别刻意去打开这个 app。48 小时后看投递日志的漂移分布。

**全部 20 条漂移 < 30 秒，且 `source` 全是 `ALARM`（没有 `SWEEP` 补发），才算通过。**

出现任何一条 `SWEEP`，说明主路径正在被掐，回到 9.2 重新检查配置。

---

## 10 里程碑

| | 内容 | 出口条件 |
|---|---|---|
| **M1** | 把最难的跑通 —— 完全不接 AI。手动添加/编辑页 + 列表、Room + AlarmManager + BootReceiver + 通知、FireLog + 投递日志页、权限体检页 | **48 小时放置测试通过（§9.3）** |
| **M2** | 接 AI。先验 SDK 可用性 → ProviderProfile + 设置页 → 三档 `response_format` + 容错解析 → 校验闸门 + 确认卡 | 30 条真实句子，解析正确率 > 90% |
| **M3** | 重复规则与时区。RRULE 子集求值器、墙钟锚定 + `TIMEZONE_CHANGED` 重算 | 手动改系统时区，重复提醒时间正确 |
| **M4** | 打磨。稍后提醒、通知 action、语音输入、铃声选择、分组列表、搜索 | 自己愿意每天用 |

**为什么 M1 不接 AI**：AI 解析出错当场看得见、能立刻改；闹钟没响可能三天后才发现。M1 不通过，后面全是白做 —— 一个解析得再漂亮但会漏提醒的 app，价值是负的，因为你会开始依赖它。

---

## 11 风险登记

| 风险 | 影响 | 应对 |
|---|---|---|
| MagicOS 杀后台导致漏提醒 | **致命** | M1 放置测试 + 巡检兜底 + 投递日志可观测 |
| `openai-java` 在 Android 上跑不起来，或对非 OpenAI 供应商发多余字段 | 中 | M2 第一步实测；撞上就换回手写客户端，只动 `ReminderParser` 的一个实现类 |
| 供应商不支持 `json_schema` | 中 | 三档降级，最低档纯提示词也能工作 |
| 模型日期算错 | 中 | `basis` 字段 + 校验闸门 + 确认卡 |
| 出国换时区导致重复提醒错位 | 中 | 墙钟锚定 + `TIMEZONE_CHANGED` 重算 |
| API key 硬编码在 APK 里 | 低（自用） | APK 不外发；`allowBackup="false"`；用一把可随时吊销的独立 key |
| 供应商跑路 / 欠费 | 低 | 已排期提醒不受影响；改 `baseUrl` 即可换家 |

注意第一行和最后一行的对比：**整个 AI 层的所有风险加起来，严重程度都不如「闹钟没响」这一条。** 这也是为什么文档的一半篇幅在讲 §05 和 §09。

---

## 修订记录

| 版本 | 变更 |
|---|---|
| v1 | 初稿 |
| v1.1 | 决策 3.1 翻转：改用 `openai-java` 官方 SDK，补充两条待验前提和对应风险 |
| v1.2 | 决策 3.2 修订：minSdk 33 → 34（全屏 intent 相关 API 是 34 才有的，停在 33 反而要写版本分支）；确认 `setAlarmClock()` 的状态栏图标代价可接受 |
