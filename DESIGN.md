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
- **`FLAG_INSISTENT`**：不加的话铃声和震动各播一遍就停（真机上就是「只嗡一下」）。加了之后系统循环到通知被取消 ——
  「完成」「稍后」（通知按钮和全屏页都走 `NotificationActionReceiver` → `cancel()`）、点开通知、划掉通知都会让它停。
  不在 `AlarmActivity` 里自己放铃：亮屏时系统只给 heads-up、不起全屏页，那条路就又只响一下了
- **震动节奏用渠道默认的，没有自定义波形**。自定义要 `vibrationPattern`，而那也是建渠道时定死的，得换 id ——
  可 MagicOS 会把新渠道从 HIGH 静默降到 DEFAULT（见 `HealthCheck`），v1 是手动调回 HIGH 的，换 id 等于把锁屏全屏弹出赌掉。
  默认波形在 `INSISTENT` 下会从头循环，本身就是一下一下的节奏，够用

---

## 06 AI 解析层

整层可以被一个手动编辑页完全替代 —— 这是设计它的前提，不是妥协。

> **已修订（真机测试后）**：下面 §6.2-6.3 描述的是「让模型输出 JSON」这条路，实际写代码时验证下来
> 走了**工具调用**（`create_reminder` 函数）而不是 `response_format`。原因很直接：`json_object` 档
> 实测时模型会自己发明字段名（`{summary, details:{...}}`），根本不按我们要的 schema 走；工具调用把
> 参数 schema 交给服务端强制，模型没有自由发挥的空间。`ai/OpenAiCompatParser.kt` 和 `ai/PlanSchema.kt`
> （JSON 输出模式的实现）已删除，`ai/ToolCallParser.kt` + `ai/ReminderTool.kt` 是唯一在用的路径。
> 反问之后的多轮对话是客户端拼文本重放（`ai/ChatTurn.kt`），不依赖 `previous_response_id` 这类
> 服务端会话状态 —— 第三方 OpenAI 兼容接口大概率没实现那个。下面 §6.2/6.3 的设计意图（三档降级、
> 校验闸门）基本保留，只是"怎么让模型给出结构化数据"这一步换了机制，具体接口形状以代码为准。

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

实际在用的实现是 `ToolCallParser(profile)`（见 §06 顶部的修订说明），换供应商 = 改这四个字段。

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

### 6.7 流式：把过程摊开给人看

非流式那版实测 6–7 秒空白，只能拿骨架条撑着。现在走 `client.responses().createStreaming()`，
四类事件各自对应界面上的一块：

| SDK 事件 | 我们的事件 | 界面 |
|---|---|---|
| `reasoningTextDelta` / `reasoningSummaryTextDelta` | `Reasoning` | 左侧一条细线圈起来的浅色小字 |
| `outputTextDelta` | `Text` | 正文，末尾跟一个闪的墨块光标 |
| `outputItemAdded`（functionCall） | `ToolStarted` | 「在建提醒 · create_reminder」 |
| `functionCallArgumentsDelta` / `...Done` | `ToolArgs` | 原始 JSON 不上屏，只抠 `title` / `firstTriggerAt` 画草稿卡（决策 6.2） |
| `completed` / `incomplete` / `failed` / `error` | `Done` | 草稿落印成回执卡片，或者落定成文字 |

**终局仍然是 `ParseResult`。** 这是整个改动的支点：`MainViewModel` 里「落库 → 排闹钟 → 出卡片」
那一段一个字都没动，没有第二条落库路径，风险全关在网络层和界面层，够不着 `schedule/` 和 `data/`。
判定逻辑也只有一份 —— 流式收到 `completed` 时，走的是和非流式完全相同的 `resultOfResponse()`。

**决策 6.1 · 保留非流式 `parse()` 作为回退**

不是死代码。第三方 OpenAI 兼容网关不一定实现 SSE，两种情形自动回落：

1. `createStreaming()` 直接抛（对 `stream:true` 返回 4xx 是最常见的一种）
2. 流开了但一个事件都没吐就结束

回落前先发一个 `FellBack` 事件，界面把已经吐出来的半截字**擦掉**、退回骨架条 ——
不擦的话，半截字后面再蹦出一份完整答案，看着像模型把同一句话说了两遍。

**流开到一半断掉不算「没跑起来」**，不回退：那时候工具参数可能已经收全了，
硬回退等于把同一条提醒建两遍。这种情况按手上的残料判结果。

**三个坑，改这块之前先读**

1. **`stream()` 是阻塞迭代**。取消（用户点「停」、离开页面）时必须主动 `close()` 掉
   `StreamResponse`，否则要一直卡到下一个事件到达才醒得过来。实现里把 `close` 挂在了
   协程 Job 的 `invokeOnCompletion` 上。
2. **思考过程可能压根没有**。`reasoning*` 事件只有推理模型才发。界面在「没有思考块」时
   必须长得正常，不能留一个空槽 —— 所以一个字都还没来的时候，退回的是原来那个骨架条。
3. **参数以 `...Done` 给的完整串为准**，不用 delta 拼出来的那份 —— 拼串可能缺尾巴。

思考过程流完之后不直接丢掉：折成一行「想了 3 秒 · 看看」留在正文上面，点开可看。
模型把时间算歪的时候，那段和卡片上的 `basis` 是仅有的两条线索。但它**不进对话历史**
（见 `ChatTurn`）—— 把模型自己的思考喂回给它没有意义，只会挤掉真正的上下文。

**决策 6.2 · 一个回合原地长大，正文不清掉**

`ChatMessage` 里助手侧只有一个 `AssistantTurn`：思考 → 卡片 → 正文，
摞在一条消息上，从流式态一路长成终态，中途不换消息类型。上一版是流式占位消失、
卡片另起一条，模型说的那段话被丢掉了 —— 而那段话往往是唯一解释「它为什么这么排」的东西。

三条摆放规矩（2026-09-11 按动效稿改过，见决策 6.3）：

1. **卡片在正文上面**。模型的动作先于它的解说；而且两者同时流时，卡片的高度起稿就定了，
   正文在它下面长，谁也不推谁。
2. **原始参数不上屏，但草稿卡露出标题和时间**。`ToolArgs` 增量里宽松地抠出 `title`（逐字显示）
   和 `firstTriggerAt`（整串收全才换成人话，之前留占位条），见 `ui/chat/DraftArgs.kt`。
   建成前一两秒就能看出它听没听懂。只拿来画草稿 —— 落库用的仍是 `...Done` 给的完整参数（§6.7 坑 3）。
3. **工具行和卡片是同一个元素**：在建提醒 → 起稿（虚线框）→ 落印（已记下）→ 收起。
   不再是「落定即藏、卡片另起」的硬切。闸门没放行（§6.5）时退回成「× 没记下」的工具行，
   下面一句说清原因 —— 这是工具行唯一留下来的情形。形态判定见 `cardPhaseOf()`。

**决策 6.3 · 动效：整个回合只有一个重拍**

动效稿（九种情形的可播放原型 + 逐拍规格）：<https://claude.ai/code/artifact/c0493995-43b7-4220-8a00-35adb5990804>。
时长曲线只从 `ui/theme/Motion.kt` 取：墨落 / 行文 / 退场三条曲线，180 / 160 / 280 / 420ms 四档时长，
外加一个只给「已记下」那枚印用的弹簧。

- **等待**：两根墨条，一道墨色从左往右洇过去；「· 到点」的圆点呼吸。墨条收起和第一块内容进场同一帧，没有空白帧。
- **正文**：每段增量 180ms 淡入；光标在字流动时常亮，停顿超过 400ms 才闪。
- **落印**：朱砂印从 1.45 倍、−10° 盖下来带回弹，印边洇开一圈，下面几行错峰展开。**不震动** ——
  聊天里震一下太突兀（用户反馈），震动只留给到点响铃。
- **滚动**：流式期间逐帧贴底（一个回合原地长大时条数不变，只盯条数的话新内容会掉到屏幕外）；
  你一拖列表就不跟，冒出「↓ 新内容」。
- **喊停**：这一回合撤掉，原话也撤下来、退回输入框。气泡改不了，留着没用；退回去改两个字就能重发。
- 无限循环的动画（墨条、细线、呼吸点）只在真正需要时才进组合 —— 挂着不用也会一直要帧、耗电。

顶栏那枚 wordmark 换成了朱砂小印：左上角的「到点」和每条回复前面的「· 到点」重复，
说话人标记留在对话里（它就在说话人的位置上），品牌位缩成一枚不占字的印。

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
| **桌面小组件** | 下一条（大字时钟）+ 其后几条 + 右下角一枚墨印。点墨印在桌面上说一句就建好，不进 app。见 §8.2 / §8.3 |

**投递日志页**：每条显示「应响 → 实响 → 漂移 `+3s`」和来源标签。它是判断 §09 保活配置有没有生效的唯一客观依据，别做成调试开关藏起来。

---

### 8.1 视觉语言 · 墨宋

视觉稿（Claude Design 画布，九块画板）：<https://claude.ai/code/artifact/c7073888-2022-4a7f-bb22-4ae61961d0a1>

宣纸底、墨色为主、朱砂只做印章式点缀、宋体挑大梁。四条规矩，改界面之前先读：

1. **实心块一律是墨色，不是彩色**。用户气泡、主按钮、发送键用 `solid`（浅色主题下即墨黑 `#1C1A17`，深色主题下翻成米白 `#E6E2D6`）。
2. **朱砂 `accent`（`#9E3B2E`）只做小点缀** —— 助手圆点、「已记下」的对勾、可点的链接字。铺成色块就俗了。
3. **徽标描边不填色**，圆角 3dp；卡片圆角 5dp；按钮圆角 22dp（大按钮 34dp）。
4. **层次靠字号和字距，不靠字重**。系统衬体通常只有 400/700 两档，稿子里的 300/500/600 会被合成到最近一档 —— 小标签一律加大字距（0.2em 起）。

字体角色：宋体（`FontFamily.Serif`）担标题、大时钟、序号；黑体（`Default`）担正文；等宽（`Monospace`）只用在「依据」那一行。没有打包 Noto Serif SC，理由见 [CLAUDE.md](CLAUDE.md) 的包体权衡。

「依据」行是模型的推算过程，**不要删** —— 模型算错时，那是唯一能看出哪儿歪了的线索。补发的提醒必须标出迟到多久，不能假装准时。

顶部状态栏那一条留空给系统自己画（含 `setAlarmClock()` 带来的常驻闹钟图标），我们不自绘，画了会重影。

到点全屏页目前钉死深色（半夜三点是它最常见的使用场景）；视觉稿的浅色版也在，`AlarmScreen` 直接读主题色板，把 `AlarmActivity` 里的 `darkTheme = true` 换成 `isSystemInDarkTheme()` 就能跟随系统。

---

### 8.2 桌面小组件

一句话：**桌面上那块是快照，不是第二个真相**。它只读 Room，不排闹钟、不撤闹钟、不参与触发链路 —— 整个小组件全挂掉，提醒照响。

版面自上而下：抬头（「到点」+ 条数）→ **下一条**（宋体大字时钟 + 标题）→ 其后至多 4 行 → **脚**：左边一行小字，右下角一枚墨印（麦克风）。下面没有别的行时，「明天 · 23 小时后」就写在脚上、和墨印并排，省下一整行，3×2 也放得下大字时钟。墨印永远在；高度不够时先砍行，再把「下一条」压成脚上的一行小字（「下一条 · 明天 15:00 交房租」）—— 小组件最要紧的是「在桌面上说一句」，其次才是「看一眼」。

墨印旁边不写「说一句话」之类的提示：一枚麦克风自己就说明了，字只会挤掉「下一条」的位置。上一版底部那根整宽的说话条（加条尾键盘）占掉 54dp，已经拿掉。

| 位置 | 点下去 |
|---|---|
| 抬头 | 提醒列表 |
| 右下角墨印 | 桌面速记，一进来就开始听（§8.3） |
| 其余空白 | 进 app（对话页）。顺手记下整块小组件的框，见 §8.3 |
| 下一条 / 某一行 | 编辑那一条 |
| 完成圈 / 行尾 `✓` | 直接完成 |

原来抬头上的 `＋`（手动新建）拿掉了：逃生舱仍然在 app 里（列表页、解析失败时的「手动填一条」），桌面速记解析失败时也有这个出口。

`✓` 的语义和列表页的「完成」**完全一致**：先撤闹钟再改状态，重复提醒也整条停掉。顺序反了就会留下一个指向 DONE 记录的闹钟，到点照响。它没有复用 `NotificationActionReceiver.ACTION_DONE` —— 那条路径处理的是「已经响过的提醒」，不撤闹钟；小组件按的是**还没到点**的，必须撤。

已经过点却还挂在列表里的那条，时间写成红字。这不是装饰：主闹钟路径被 ROM 掐掉的时候，它是桌面上第一眼能看见的告警（配合 §9.3 的投递日志）。行首的点一律是灰的，朱砂只点在**刚从桌面速记记下**的那一条上（「刚记下」，亮 6 秒后褪掉）—— 满屏印章就不是印章了。

**决策 8.1 · 用 RemoteViews，不用 Glance**

Glance 能把这块界面写成 Compose，但它是另一套运行时，而包体已经因为 openai-java 涨到 35MB（决策 3.1）。小组件一共就一个抬头加几行字，为它再背一套依赖不划算。代价是这里的颜色和字体没法复用 `ui/theme/` 的色板，见下面第三条。

**决策 8.2 · 行是 `addView` 塞进去的，不是 `ListView` + `RemoteViewsService`**

集合视图要跨进程适配器（桌面进程反过来 bind 我们的 Service），是一条实打实会掉链子的链路，换来的只是「能滚动」。桌面不是列表页，看完前几条就该点进 app。行数按桌面报上来的高度算：竖屏取 `OPTION_APPWIDGET_MAX_HEIGHT`、横屏取 `MIN_HEIGHT`（文档约定竖屏窄而高）。早先一律取 MIN，在只竖屏的桌面上等于按矮个子排版 —— 底下压了说话条之后，「下一条」会被直接砍掉。「下一条」之外最多 4 行，剩下的在抬头写「还有 N 条」。各块高度常量在 `WidgetRenderer`，和布局 XML 一一对应。

**三条规矩，改小组件之前先读**

1. **刷新是推过去的，不是拉回来的**。桌面收不到 Room 的 Flow —— 它在别的进程里。每个改动点都要喊一声 `WidgetUpdater.refresh()`：app 内的增删改（`MainViewModel` 盯 `observeAll()` 一处兜住）、响铃（`FireHandler`）、通知按钮（`NotificationActionReceiver`）、巡检和重排（`SweepWorker`、`RescheduleReceiver`）。**漏喊的后果是桌面显示旧数据，不是漏提醒。**
2. **`updatePeriodMillis`（30 分钟，系统最小值）只用来兜「3 小时后」这种相对时间的自然变旧**，不是数据变化的通知渠道。它不唤醒设备；跨零点、改时间、换时区另外由 `DATE_CHANGED` / `TIME_SET` / `TIMEZONE_CHANGED` 三条受保护广播补一刀。
3. **`res/values/colors.xml` 是墨宋色板的第二份拷贝**（`values-night/` 是深色那份），改色时和 `ui/theme/Palette.kt` 一起改。颜色必须写成 `@color` 引用交给桌面去解析 —— 在 Kotlin 里 `getColor` 算好再 `setTextColor` 塞进去的色值，会在用户切换深浅色时僵在原地；代码里要换色，用 `RemoteViews.setColor(id, "setTextColor", R.color.xxx)`（API 31），同样由桌面按资源解析。矢量图标的线色也写 `@color`。同理，字体只能写 `android:fontFamily="serif"`，宋体挑大梁那条规矩在这里只能靠系统衬体。

**RemoteViews 做不了动画。** 桌面进程里没有我们的帧回调，小组件自己这一侧能给的回应只有「刚记下」那一亮一褪（`WidgetUpdater.announce`）。动效全部放在桌面速记那张纸上（§8.3）。

---

### 8.3 桌面速记

一句话：**在桌面上说一句就建好，不进 app，而且只有语音**。要聊天、要改字，回 app 的对话页 —— 那边体验完整，桌面这块不做第二个输入框。交互稿：<https://claude.ai/code/artifact/14ee235b-dafd-45ff-9a43-9e11cd16d8ce>

小组件右下角的墨印拉起 `QuickAddActivity`：透明窗口，桌面原样不变色，一张纸**从整块小组件里长出来**（容器变换）：纸框一开始就是小组件那一块（同样的系统圆角），底边钉住，往上、往两边长成整张纸；记完再缩回小组件。三件事叠在一起，才读得出「是小组件在变大」而不是「一张白卡盖上来」：

1. 纸框用 `Motion.Expand`（0.4, 0, 0.2, 1）伸展，放 460ms、收 340ms。别用 `Settle` —— 它三成时间走完七成路，整块纸是蹦出来的。
2. 进度按**时间**线性走，各层自己换算：纸色在头两成时间盖住小组件上的字，纸上的字在三成到八成时间淡进来。拿缓动后的值去切这些区间，前两成二十毫秒就走完了。
3. 小组件右下角那枚墨印**跟着飞**到纸中间、从 44dp 放大成纸上 64dp 的印；收起时飞回去。它是前后唯一没断过的东西，眼睛跟着它走，就认得出这张纸是从哪儿来的。
4. 投影等纸色盖实了（两成时间之后）再出 —— 纸还半透明时，投影会从纸底下透上来，框里一圈灰，真机慢放截图看到的。

debug 包里可以慢放看接缝：`adb shell run-as com.abc.daodian.debug sh -c 'echo 8 > files/slowmo'`，删掉那个文件恢复正常速度。

**只有墨印拉起速记，空白处进 app**（2026-09-11 改，用户要的：误触空白就开始录音太吓人）。代价是整块的框不再是现成的：点墨印时桌面给的 `Intent.sourceBounds` 只是墨印那一小圆。右边、下边从它倒推是准的（内边距 10dp），宽高推不出来，而 `OPTION_APPWIDGET_*` 报的尺寸在荣耀上是错的 —— 报的宽是 224 / 302dp，实际 242dp，哪个都对不上，第一帧就错位。所以借「点空白处进 app」那一下：那个点击挂在整块上，`sourceBounds` 就是整块的框，`WidgetFrame` 把宽高记进 SharedPreferences，连同桌面当时报的尺寸 —— 报的变了（拖大拖小了）就作废。从没点过空白处、或者刚拖过尺寸，就拿报的尺寸凑合，第一帧会错位十几 dp，点一次空白处之后就准了。（上一版为了拿到准的框，点击挂在整块上、空白处也是「说一句」；更早还试过从墨印的小圆长出来，看着像一颗按钮炸开，怪。）拿 `sourceBounds` 要求 PendingIntent 是 `FLAG_MUTABLE` —— IMMUTABLE 会把桌面的 fill-in 整个丢掉，这也是真机上踩出来的；墨印和空白处两个都得是 MUTABLE。

**纸外面不蒙。** 上一版铺过一层 72% 的宣纸色，真机上等于把整个桌面洗白了。现在只靠纸自己的投影和桌面分开；点纸外面＝取消。

流程：点墨印 → 纸长出来、印外墨圈一圈圈洇开、字边说边洇出来 → 停顿自动收音（或点印）→ 送去解析，回合和对话页**长得一模一样**（直接复用 `AssistantTurnRow`：墨条 → 起稿 → 落印）→ 印落下，纸底边一根细线走 2.6 秒，走完缩回小组件；这期间碰一下纸就不走。模型反问时**自动接着听**，一路说下去不用碰屏幕，历史照样拼（同 `ChatTurn`）。

做不了「按住说话」：桌面只把「点了一下」转给我们，拿不到按下和抬起，所以是点一下开始、停顿自动结束。

| 情况 | 去向 |
|---|---|
| 第一次用 | 弹 `RECORD_AUDIO` 授权；拒了，纸上只剩一句原因和「去 app 里说」 |
| 包里没带识别模型 | 同上 |
| 没听清 | 印熄成待命，那句「没听清，再说一次？」顶替提示语；不是出错，不写红字 |
| 说到一半识别出错 | 已经听到的字就当说完了，照样送去解析 |
| 点「停」 | 掐断这条流，纸回到待命，点一下重说 |
| 「改一下」/ 解析失败「手动填一条」 | 打开 app 的编辑页，桌面速记关掉 |
| 按 Home、锁屏 | 纸直接关掉（`onStop` 里 finish，等授权弹窗时除外） |

**决策 8.3 · 语音在手机上本地识别（sherpa-onnx），不用系统的识别服务**

系统给的两条路在荣耀 MagicOS 上都走不通，供应商那边也没有第三条：

- `RecognizerIntent`：没有 Activity 接 `ACTION_RECOGNIZE_SPEECH`（`cmd package query-activities` 查过，空的），按下去只会 `ActivityNotFoundException` —— 对话页的麦克风以前在这台手机上按了没反应，就是这个。
- `SpeechRecognizer`：默认识别服务是 MagicVoice（`settings get secure voice_recognition_service`），绑得上，它也真把麦克风打开了 —— 状态栏同时挂着「到点」和 YOYO 两个在用麦克风 —— 但**一个回调都不回**，Ready、Error 都没有，等 18 秒也没有，纸永远停在「在听」。它只伺候自家语音助手。（上一版就是这么绑的，还为它在 manifest 里加过 `<queries>`，已删。）
- 供应商网关：`/audio/transcriptions` 返回 Route not found，`/responses` 和 `/chat/completions` 带音频输入也都失败。

所以自己录音（`AudioRecord`，`VOICE_RECOGNITION` 源，16kHz 单声道），sherpa-onnx 在本地流式识别，模型 `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`（26MB，在 `assets/asr/`）。实现只有 `VoiceInput` 一处，桌面速记和对话页的麦克风共用：

- 模型第一次 `start()` 时才加载（真机约 1.9 秒），加载期间录音照常进来、先攒着，好了一口气喂进去 —— 开头的字不丢。实例活多久模型就在内存里待多久，`release()` 放掉。
- 端点用 sherpa 自带的三条规则：还没开口给 5 秒，开了口停 1.2 秒就收，一句最长 20 秒。
- 声音不出手机，断网也能听写（解析那一步照样要网）。
- 对话页：字边说边写进输入框、接在已经打的字后面，**不自动发** —— 那边是能改字的地方。桌面速记只有语音，听完直接送去解析。

代价：

- 包体：arm64 的 `libsherpa-onnx-jni.so` 24MB（onnxruntime 静态链接在里面）+ 模型 26MB（`noCompress`，原样进包）。`abiFilters` 只留 `arm64-v8a`，不然四个 ABI 加起来 70MB+。
- 官方只发 GitHub Releases 的 AAR，没有 Maven 坐标，放在 `app/libs/`；升级就是换那个文件。
- release 包必须 `-keep class com.k2fsa.sherpa.onnx.**` —— JNI 按名字读配置类的字段，AAR 自带的 proguard.txt 是空的。
- 模型不出标点；开口前的环境音偶尔会被认成几个乱字（真机测过一次）。送去解析的就是这串字，模型一般看得懂。

**没有第二条落库路径。** 桌面速记和对话页共用 `ToolCallParser`、`ChatMessage.kt` 里的回合规则（`patched` / `finished` / `historyText`）和 `PlanCommitter.commit()`。`commit` 整段 `NonCancellable`：插完库、闹钟还没排上的一瞬间被取消（纸被 Home 掉），会留下一条没有闹钟的 SCHEDULED 记录。

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
| v1.3 | §06 修订：AI 解析改用工具调用，不用 JSON 输出模式（实测 `json_object` 档模型会自造字段名）；多轮对话靠客户端拼历史文本，不依赖服务端会话状态。M1/M2 真机测试通过；M3 正式界面（Compose，八块屏幕）已实现，端到端真机验证未完成。现状与操作细节见 [CLAUDE.md](CLAUDE.md) 和 [README.md](README.md) |
| v1.4 | §8.1 新增：视觉语言换成「墨宋」（宣纸底 / 墨色实心块 / 朱砂点缀 / 宋体标题），八块界面照新视觉稿重画；到点全屏页改成直接读主题色板（仍钉死深色），通知副行补上「什么时候的事」和补发标记 |
