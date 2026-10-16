# 改造方案：拆出 agent runtime

2026-10-15 定的方向。**第 1–5 步做完了（10-15，都没装机）**，剩第 6 步（可选）；第 4 步要的两项真机验证、第 5 步的装机说一句还欠着。做完一步在第 8 节打勾；全部做完后，要紧的决定写进 DESIGN（§2.2、§06），这份删掉（和当初的 `TEST_PLAN.md` 一样）。

## 0 一句话

循环、工具、上下文、调模型这几样抽成纯 Kotlin 的 `runtime`。系统里有几个 agent，列在一份花名册上，每个都用 runtime 跑。每个 agent 的提示词写全在自己的目录下，不再拼别的模块的段落。`agent` 包拆成 `assistant`（助手）和 `shell`（壳），代码里不再叫 chat。

**行为不变，手机上存的东西一样都不动。** 唯一有意改的是派活提示词里自相矛盾的那一条（第 4 节）。

---

## 1 为什么改

| 现在 | 证据 | 后果 |
|---|---|---|
| 想跑 agent，就得自己拼一遍 | `Organizer`、`RelayAgent`、`Tidy` 各写一遍同样的步骤：读配置 → 查配没配 → `new ResponsesClient` → `new AgentLoop` → 收事件 → 手动 `ApiHealth.record` → 加锁、挂到顶栏 | 再加一个后台 agent 就要再抄约 50 行；而且后台每跑一次都新建一个 OkHttp 连接池 |
| 看不出系统里有几个 agent | 它们散在 `agent/conversation`、`agent/memory/tidy`、`ledger/organize`、`reminder/relay` 四处，没有一个地方列全 | 加 agent、查问题时，得先翻一遍代码 |
| 助手的提示词是运行时拼出来的 | `BasePrompt` 后面接着各 `Feature.prompt`，按 `FEATURES` 的顺序拼；派活又另外拼了 `ReminderPrompt.RULES` | 想看助手的提示词全文，只能把程序跑起来；调一下模块顺序，system 就变了；派活拼进来的轻提醒规则，和它自己写的「gentle 一律填 false」打架 |
| 提示词放得乱 | 散在 6 处，有 4 种叫法；`LedgerPrompt` 一个文件装着两个 agent 的提示词；两个标记在提示词里各手抄了一遍 | 改了一处，另一处不会跟着改 |
| 工具结果没有类型 | 有 3 处 `payload as? …`（整理员、派活、记忆整理员） | 类型写错了也不报错，结果悄悄变成空 |
| `agent` 一个包装了四样东西 | 运行时、对话页、速记、宿主壳都在里面；模块想碰哪样都碰得到：提醒编辑页直接读对话库（`ChatStore`），小组件 import 了速记的 Activity，派活一个文件就 import 了 12 个 agent 的内部类 | 模块该依赖哪一半说不清；规矩全靠自觉，越界了编译照样能过 |

---

## 2 改完的样子

### 2.1 分层与依赖

```
根目录              只做装配：Modules.kt（模块清单）、Agents.kt（agent 花名册）
shell               宿主壳：导航、抽屉、设置页、权限页、模型配置页、诊断
  ↓ 用
assistant           助手：它的 agent、对话页、桌面速记、记忆
  ↑ 插口（assistant/skill、shell/entry）
reminder  ledger    模块：自己的核心 + 自己的后台 agent
  ↓ 用（assistant 也用）
runtime             跑 agent 的：纯 Kotlin，不认识 Android、模块和对话页
intake  shared      不变
```

| 包 | 可以 import | 不可以 |
|---|---|---|
| `runtime` | Kotlin、协程、openai-java | `android.*`、`androidx.*`、app 里的任何包（第 5 步起由 Gradle 模块边界管） |
| `assistant` | `runtime`、`shared` | 模块、`shell` |
| `shell` | `assistant`、`runtime`、`shared`、`update` | 模块（模块的东西都经 `ShellEntry` 进来） |
| 模块的核心层（提醒的 `domain/data/application/scheduling/delivery`，记账的 `domain/data/capture`） | `shared`、`intake` 核心 | `runtime`、`assistant`、`shell`（同今天） |
| 模块的其余部分 | `runtime`，两个插口包 `assistant/skill`、`shell/entry`，和接头接口 `module` | `assistant`、`shell` 的其余部分；别的模块 |
| `module` | 两个插口包 | 其余一切（只放 `Module` 这一个接口，只有根目录和模块的接头用它） |
| `intake`、`update`、`shared` | 不变 | 不变 |

规矩由一条单元测试守着（`ArchitectureTest`）：扫各包的 `import`，和上表对照。

### 2.2 目录

★ 是 agent，一共 4 个。

```
runtime/                       纯 Kotlin（第 5 步变成 Gradle 模块 :runtime）
  engine/                      AgentLoop、Session、Item / Turn、AgentEvent
  tool/                        Tool、ToolContext、ToolOutcome、ToolRegistry
  ask/                         问卡协议、ask_user
  context/                     ContextPolicy、Fold*、Compaction、Background / Preamble
  model/                       LlmClient、ResponsesClient、ProviderProfile、ApiHealth、ProviderTest
  memory/                      Memory、MemoryBackend、MemoryRules、记忆那段怎么拼
  Agent.kt                     AgentInfo、Agent、Input、RunResult
  AgentRuntime.kt              run / runOnce / exclusive、锁与顶栏、ModelSource、Tracer

assistant/                     助手
  AssistantAgent.kt            ★ 助手
  AssistantPrompt.kt
  skill/                       AssistantSkill 插口（原 Feature 给对话的那一半）、痕、PartialArgs
  conversation/                对话页（入口一）、chat.db
  quick/                       桌面速记（入口二）
  memory/                      记忆、记忆页、edit_memory
    tidy/TidyAgent.kt          ★ 记忆整理员
    tidy/TidyPrompt.kt
  provider/                    ProviderStore（模型配置存哪，实现 runtime 的 ModelSource）
  voice/

shell/                         导航、抽屉、设置、权限页、模型配置页、诊断与崩溃记录
  entry/                       ShellEntry 插口（原 FeatureUi + health / diagnostics）

module/Module.kt               模块接头：id、label、给助手的、给壳的、冷启动

reminder/
  ReminderModule.kt
  assistant/ReminderSkill.kt   给助手的：create_reminder、痕、例句、手动填一条
  presentation/ReminderShell.kt 给壳的：页面、抽屉卡、设置行、体检、诊断
  relay/RelayAgent.kt          ★ 派活
  relay/RelayPrompt.kt
ledger/
  LedgerModule.kt
  assistant/LedgerSkill.kt     给助手的：四个工具、类别表、痕、每晚对账那一轮、问卡上打标签
  presentation/LedgerShell.kt
  organize/OrganizerAgent.kt   ★ 记账整理员
  organize/OrganizerPrompt.kt
  reconciliation/              每晚对账：不是 agent，是助手的一轮
intake/ update/ shared/        位置不变

Modules.kt                     模块清单（原 Features.kt）
Agents.kt                      agent 花名册
```

### 2.3 花名册

```kotlin
// Agents.kt
/** 系统里一共这几个 agent。加一个 = 写 XxxAgent + XxxPrompt，再在这里加一行 */
val AGENTS: List<AgentInfo> = listOf(AssistantAgent, OrganizerAgent, RelayAgent, TidyAgent)
```

| id | 名字 | 归谁 | 什么时候醒 | 能问你 | 工具 |
|---|---|---|---|---|---|
| `assistant` | 助手 | assistant | 你在对话页说一句；桌面速记；每晚对账由记账开场 | 能 | ask_user、edit_memory、create_reminder、list_expenses、add_expense、update_expenses、add_category |
| `organizer` | 记账整理员 | ledger | 后台：每 3 小时、对账前、手动点 | 不能 | record_expenses、list_expenses |
| `relay` | 派活 | reminder | 后台：名单上的人发来一句 | 不能 | create_reminder |
| `tidy` | 记忆整理员 | assistant | 后台：闲 10 分钟、攒够 16 轮 | 不能 | save_tidy |

有三样不算 agent：
- **桌面速记**：助手的第二个入口，system 和工具都是同一份。
- **每晚对账**：助手的一轮，开场白由记账来写。
- **配置页「测一下」**：只调一次模型。

花名册不只是给人看：
- debug 包里，跑一个不在花名册上的 agent 直接崩，所以花名册不会漏写。
- 导出诊断多一段「agent」：列出这 4 个和各自的提示词版本。

---

## 3 runtime

### 3.1 里面有什么

基本是原样挪包，都不是按全类名记的类：

| 现在 | 挪到 |
|---|---|
| `agent/engine/` 的 `AgentEvent`、`AgentLoop`、`Session`、`Transcript` | `runtime/engine/` |
| `agent/engine/tool/Tool.kt` | `runtime/tool/` |
| `agent/engine/ask/`（`Ask`、`AskUserTool`） | `runtime/ask/` |
| `agent/engine/context/`（6 个文件） | `runtime/context/` |
| `agent/engine/background/AgentActivity.kt`（锁、顶栏、`LockedTool`） | 第 2 步先原样挪到 `runtime/AgentActivity.kt`，第 3 步并进 `AgentRuntime` |
| `agent/model/` 的 `LlmClient`、`ResponsesClient`，`provider/` 下的 `ProviderProfile`、`ApiHealth`、`ProviderTest` | `runtime/model/` |
| `agent/memory/Memory.kt`（`Memory`、`MemoryBackend`、`MemoryRules`）；`Recall.preamble` 的拼法第 3 步再挪（它带着摘要 `Digest`，到时候和 `AgentRuntime.memory` 一起定） | `runtime/memory/` |

要切掉的依赖只有两处：
- `Transcript.kt` 用了 `shared.format.Format.weekday`：把七个字抄进 runtime。
- `ProviderProfile.fromBuildConfig()` 用了 `BuildConfig`：挪进 `ProviderStore`，在那里当种子。

**不进 runtime 的**：
- `ProviderStore`（DataStore）、`MemoryBook`（Room）：runtime 只认接口，实现由 app 装进来；
- 对话落盘、`Recall.chat / quick`（要加上摘要和模块现状）；
- 写 trace 文件。

### 3.2 跑一个 agent

```kotlin
/** 花名册上的一行：是谁、归谁、什么时候醒 */
interface AgentInfo {
    val id: String        // "organizer"：顶栏、trace、诊断都按它认
    val label: String     // 「记账整理员」
    val owner: String     // "ledger"
    val wakes: String     // 「后台：每 3 小时、对账前、手动点」
    val canAsk: Boolean get() = false   // 只有助手能出问卡
}

/** 一份提示词：全文、版本号、这份是照着哪几个工具写的（第 4.3 节） */
class Prompt(val text: String, val version: String, val tools: Set<String>)

/** 跑一次要的全部东西。工具要连库，由 XxxAgent.build(...) 现建 */
data class Agent(
    val info: AgentInfo,
    val prompt: Prompt,
    val tools: List<Tool>,
    val context: ContextPolicy = LastTurns(1),
    val maxSteps: Int = 6,
)

data class Input(val text: String, val at: ZonedDateTime, val trigger: Boolean = false, val images: List<String> = emptyList())

object AgentRuntime {
    /** DaodianApp 里装一次。strict = BuildConfig.DEBUG：对不上就崩 */
    fun install(agents: List<AgentInfo>, models: ModelSource, memory: MemoryBackend, tracer: Tracer, strict: Boolean)

    /** 前台用：事件流交给界面边收边画（对话页、速记）。健康状态、trace 自动记 */
    fun run(agent: Agent, session: Session, input: Input,
            asker: Asker = Asker.NONE, background: Background = Background.NONE): Flow<AgentEvent>

    /** 后台用：跑完收成一个结果。没配模型直接返回 NotConfigured，不打网络。不自己挂顶栏（做的时候改的：派活一直不在顶栏上，要挂的包一层 exclusive） */
    suspend fun runOnce(agent: Agent, input: Input, background: Background = Background.NONE): RunResult

    /** 按名字的锁 + 顶栏。拿不到锁返回 null（原 AgentActivity.tryRun） */
    suspend fun <T> exclusive(lock: String, id: String, label: String, block: suspend () -> T): T?

    val models: ModelSource             // 当前模型配置（流）；client 按配置缓存一份，大家共用
    val running: StateFlow<List<Running>>
    suspend fun memories(): List<Memory>
    val memory: Background              // 只垫记忆（派活用）
}

sealed interface RunResult {
    data object NotConfigured : RunResult
    data class Done(val turn: Turn, val stop: StopReason, val outcomes: List<ToolOutcome>) : RunResult
    data class Failed(val turn: Turn, val reason: String, val outcomes: List<ToolOutcome>) : RunResult
}
val RunResult.reply: String?                          // 最后一句话
inline fun <reified T> RunResult.payloads(): List<T>  // 替掉那 3 处 as?
```

### 3.3 调用方改完的样子

```kotlin
// reminder/relay/RelayAgent.kt
object RelayAgent : AgentInfo {
    override val id = "relay"
    override val label = "派活"
    override val owner = "reminder"
    override val wakes = "后台：名单上的人发来一句"

    fun build(commit: suspend (ReminderPlan, ToolContext) -> Long) =
        Agent(this, RelayPrompt.PROMPT, listOf(CreateReminderTool(commit)), maxSteps = 3)
}

// 派活收到一句（Relay 里）
val r = AgentRuntime.runOnce(RelayAgent.build(commit), Input(RelayPrompt.input(m.who, task, coded), at), AgentRuntime.memory)
val created = r.payloads<CreateReminderTool.Created>()
```

| 调用方 | 改完 |
|---|---|
| 对话页 | `AgentRuntime.run(AssistantAgent.build(ctx), session, input, asker, Recall.chat(ctx))`；不再手动记健康 |
| 桌面速记 | 同上，背景换成 `Recall.quick` |
| 整理账目 | `exclusive(LedgerTools.LOCK, …)` 里每一批调一次 `runOnce`，结果用 `payloads<Recorded>()` 取；`agent_runs` 照旧写 |
| 派活 | 见上面的代码 |
| 整理记忆 | `exclusive("assistant-tidy", …)` 里调一次 `runOnce`，结果用 `payloads<Saved>()` 取；`tidy_runs` 照旧写 |

trace 走 runtime 的统一钩子：
- 助手照旧写 `context_trace.txt`；
- 整理员自己数个数的那行照旧写 `organize_trace.txt`；
- 其余 agent 在 debug 包里写 `files/trace_<id>.txt`。

CLAUDE.md 里的命令不用改。

---

## 4 提示词

### 4.1 规矩

- **一个 agent 一份提示词，写全，放在 agent 自己的目录下。** 不拼别的模块的段落，也不和别的 agent 共用段落。
- **模块只给能力和数据。** 能力是工具，数据是类别表、对账清单这类；用这些工具的规矩，由用它的 agent 自己写。工具自己的说明（`description`、参数说明）还是跟着工具放。
- **每份提示词的文件里放三样**：
  - system 全文：常量，会变的东西不进来，同 §6.3；
  - 版本号；
  - `input(...)`：每轮会变的那部分怎么拼。system 里说「每次给你什么」，拼的代码就在同一个文件，两边对得上。
- **runtime 造的标记用常量插进去，不手抄**：`Preamble.MARK`、`Item.TRIGGER_MARK`，插进去以后字不变。

### 4.2 每个 agent 的

| agent | 文件 | 怎么变 |
|---|---|---|
| 助手 | `assistant/AssistantPrompt.kt` | 原 `BasePrompt` + `ReminderPrompt.RULES` + `LedgerPrompt.CHAT`，按原顺序写进一个文件，中间空一行。**字一个不变**，前缀缓存不受影响 |
| 记账整理员 | `ledger/organize/OrganizerPrompt.kt` | 从 `ledger/tools/LedgerPrompt` 拆出来，只留整理员的。两份（开不开打标签）都在这里；版本沿用 `organize-v7`；拼输入那段挪进来当 `input(...)`（从库里取数还留在 `Organizer`） |
| 派活 | `reminder/relay/RelayPrompt.kt` | 从 `RelayAgent` 里拆出来，不再拼 `ReminderPrompt.RULES`，只写自己用得上的几条（当天事项、31 号顺延）。**轻提醒那条去掉**：它和自己的「gentle 一律填 false」打架（代码里本来就压成了 false）。这是全方案唯一改字的地方 |
| 记忆整理员 | `assistant/memory/tidy/TidyPrompt.kt` | 第 4 步跟着挪位置，字不动；那时把 `TidyText` 里拼输入的部分并进来 |

做完以后删掉：`Feature.prompt`、`ReminderPrompt.kt`、`LedgerPrompt.kt`、`BasePrompt.kt`。另外，`Features.kt` 注释里那句「改顺序 = system 变了」不再成立。

助手和派活新加版本号 `assistant-v1`、`relay-v1`（第 3 步和 `Prompt` 一起加，那之前没处可写）。版本号写进 trace 和导出诊断；已经有记录列的照旧写（流水的 `parsed_by`、`tidy_runs.model`）。

### 4.3 对得上工具

提示词里的工具名是写死的字，模块改了工具名、删了工具，提示词不会知道。办法是：每份 `Prompt` 写明自己是照着哪几个工具写的（`tools`），runtime 每次跑之前，拿它和这个 agent 手上真有的工具对一遍。debug 包里对不上就直接崩，报出多了哪个、少了哪个。下面这些情况都能当场发现：
- 加了工具，提示词没跟着写；
- 模块从清单里拿掉了；
- 工具改了名。

同一处还顺带查一条：`canAsk = false` 的 agent，手上不能有 `ask_user`。

### 4.4 一个例外：对账开场白

每晚对账那一轮的开场白是记账拼的（`LedgerCheckPrompt`），里面有两句规矩：「以这份为准」「每一笔都要问到」。这两句是真机上踩出来的，放在那一轮里比放进 system 管用，所以先留着。它是跟着数据的交代，不算往助手提示词里追加。

---

## 5 接头

`Feature` 15 个成员、`FeatureUi` 7 个，拆成下面几样：

| 现在 | 去哪 |
|---|---|
| `id`、`label`、`onAppStart` | `Module`（`module/Module.kt`） |
| `tools`、`background`、`trace`、`examples`、`manualEntry`、`trigger`、`pendingTrigger`、`dismissTrigger`、`askNote`、`AskAddon` | `AssistantSkill`（`assistant/skill/`） |
| `prompt` | 删掉，写进助手自己的提示词 |
| `routes`、`DrawerCard`、`settingsEntries`、`PermissionSection`、`permissionStatus`、`HealthNote`、`health`、`diagnostics` | `ShellEntry`（`shell/entry/`） |

```kotlin
// module/Module.kt
interface Module {
    val id: String                                  // 「reminder」：路由前缀、trigger 键前缀
    val label: String                               // 「提醒」
    val assistant: AssistantSkill? get() = null     // 给助手的
    val shell: ShellEntry? get() = null             // 给壳的
    fun onAppStart(context: Context) {}
}

// Modules.kt（根目录）
val MODULES: List<Module> = listOf(ReminderModule, LedgerModule, IntakeModule)
val SUBSCRIBERS: List<NoticeSubscriber> = listOf(LedgerCapture, Relay)   // 不变

// DaodianApp.onCreate
CrashLog.install(this)
Intake.install(SUBSCRIBERS)
AgentRuntime.install(AGENTS, ProviderStore.get(this), MemoryBook.get(this), Traces(this), strict = BuildConfig.DEBUG)
AssistantSkills.install(MODULES.mapNotNull { it.assistant })
ShellEntries.install(MODULES.mapNotNull { it.shell })
Launch.install(quickAdd = QuickAddActivity::class.java)
MODULES.forEach { it.onAppStart(this) }
```

- 两个插口各自带 `id`（和模块的一样），装的时候查一遍。
- 通知监听层只实现 `shell`，不用再假装有工具、有提示词。
- `MODULES` 的顺序和现在的 `FEATURES` 一样：工具顺序、抽屉、设置页都不变。

**模块碰得到 agent 内部的那几处，这时候一起清掉：**

| 现在 | 改成 |
|---|---|
| 提醒编辑页的「依据」直接读 `ChatStore.callArguments` | 改走 `assistant/skill` 的 `CallHistory.arguments(tool, ref)`（插口包里，模块可以用） |
| 小组件用 `ShellRoutes.CHAT` 回对话页 | `Launch.HOME`（还是 `"chat"` 这个字符串，放在 `shared/navigation`） |
| 小组件 import `QuickAddActivity` 拉起速记 | `Launch.quickAdd(context)`，Activity 由根目录装进来 |
| 两个 `XxxDiagnostics` 用了 `agent.diagnostics` 的 `time`、`rows` | `time` 挪进 `shared/format`，`rows` 挪进 `shared/data` |
| 派活、整理员 import `ProviderStore`、`ResponsesClient`、`ApiHealth`、`AgentLoop`、`Session`、`MemoryBook`、`Recall` | 只用 `AgentRuntime` |
| 记账两个 ViewModel 用 `AgentActivity.running` | `AgentRuntime.running` |

---

## 6 命名：chat 改叫 assistant

**规则**：这个 agent 叫 assistant（助手），它的对话页叫 conversation。**代码里**不再出现 chat。

| 现在 | 改成 |
|---|---|
| `agent/`（整个包） | `assistant/` + `shell/`，运行时那部分进 `runtime/` |
| `ChatAgent` | `AssistantAgent` |
| `BasePrompt` | `AssistantPrompt` |
| `Feature`、`FeatureRegistry` | `AssistantSkill`、`AssistantSkills`（另有 `ShellEntry`、`ShellEntries`、`Module`） |
| `FeatureUi` | `ShellEntry` |
| `agent/conversation/Chat*`（`ChatScreen`、`ChatViewModel`、`ChatInputBar`、`ChatComponents`、`ChatMotion`、`ChatTopBar`、`ChatStore`、`ChatDatabase`、`ChatImageFiles`、`ChatItemEntity`） | `assistant/conversation/Conversation*` |
| `ChatMessage` | `ConversationItem`（不叫 `AssistantMessage`：runtime 里的 `Item.AssistantMessage` 是模型回的那句，会撞名） |
| `ShellRoutes.CHAT`、`ShellRoutes.MEMORY` | `AssistantRoutes.HOME`（值是 `Launch.HOME`）、`AssistantRoutes.MEMORY`，放在 `assistant/`：速记、记忆的痕、`WidgetFrame` 要用，助手又不能 import 壳。`ShellRoutes` 只留设置、模型配置、权限三个 |
| `agent/entry/quick` | `assistant/quick` |
| `agent/memory`、`agent/voice` | `assistant/memory`、`assistant/voice` |
| `agent/model/provider/ProviderStore` | `assistant/provider/ProviderStore` |
| `agent/diagnostics` | `shell/diagnostics` |
| 记忆整理的锁 `"chat-tidy"`（只在内存里） | `"assistant-tidy"` |

**存在手机上的名字一个不改**，改了内测的人升级会出事：
- 库文件 `chat.db`；表名 `chat_items`、`memories`、`tidy_runs`（都显式写在 `@Entity(tableName = …)` 里，实体类改名不影响）；
- `files/chat_images/` 和缓存目录；DataStore 的名字（`provider`、`memory`）；
- WorkManager 的唯一名 `chat-tidy-idle`；
- 路由字符串 `"chat"`：通知栏里挂着的、小组件上的入口都认它，换了以后点进来 NavHost 找不到页会崩；
- `agent_runs.kind = "organize"`；对话里手记的那笔，`parsedBy` 写的是 `"chat"`；
- `context_trace.txt`、`organize_trace.txt`、`quick_trace.txt` 这几个文件名（CLAUDE.md 里的命令在用）；`Launch` 那几个 extra 的键。

**按全类名记的，agent 里有三个：**

| 类 | 谁记着它的全类名 | 怎么办 |
|---|---|---|
| `QuickAddActivity` | manifest；小组件上的 PendingIntent | 类挪到 `assistant/quick/`；manifest 里给旧名字 `.agent.entry.quick.QuickAddActivity` 留一个 `<activity-alias>` 指向新类。小组件还没刷新时，点墨印走的是 alias |
| `TidyWorker` | WorkManager 里排着的那一次 | 挪到 `assistant/memory/tidy/`；旧全类名处留一个空壳子类。过两个版本删掉 |
| `ChatDatabase` | `app/schemas/<全类名>/` 目录（autoMigrations 要读） | 改名 `ConversationDatabase` 挪走，`app/schemas/` 下的目录跟着改名。Room 运行时只认库文件名 `chat.db` 和表结构的哈希，所以不受影响 |

提醒、记账、监听的接收器、Worker、小组件、库类，一个都不挪。

---

## 7 不碰的、不做的

- `reminder/scheduling`、`reminder/data` 一行都不改（触发路径，§05）。
- 所有库的版本号都不升，表结构不改。
- 循环的规矩一条不改（§6.1：写操作直接办、整轮裁剪、边跑边记、停了补结果）。
- 不做 agent 之间传话（§10.1 定过：整理员和对话模型不互相留言）。
- 用户看得到的东西一样不变：界面、文案、通知。
- 不引入 Hilt / Koin，沿用 `install()` 加单例的路数（同 `Intake.install`）。
- reminder、ledger 不拆 Gradle 模块：每个都要再配一遍 Room / KSP / Compose，依赖测试已经守得住。
- 不做 `prompts/` 快照目录。

---

## 8 步骤

每一步行为都不变，都能单独提交、单独装机。每一步做完，把 DESIGN、CLAUDE.md 里对应的路径和说法跟着改掉。

### 第 1 步：提示词各归各的（小，先做）

- [x] 改之前先把现在 5 份 system（助手、整理员两份、派活、记忆整理员）存进临时文件。
- [x] `BasePrompt` 改名 `AssistantPrompt`，把提醒那段、记账那段按原顺序写进去；两个标记改成插常量。（`VERSION`、`TOOLS` 挪到第 3 步，和 `Prompt` 一起加）
- [x] `Feature.prompt` 删掉；`ChatAgent.system` 直接用 `AssistantPrompt`；`IntakeFeature` 里的 `prompt = ""` 跟着删。
- [x] `LedgerPrompt` 只留整理员的部分，改名 `OrganizerPrompt`、挪进 `ledger/organize/`；拼输入那段挪进来（取数还在 `Organizer`）。
- [x] 派活的 SYSTEM 挪进 `RelayPrompt`，不再拼提醒规则，删掉轻提醒那条；拼输入的代码挪进来。`ReminderPrompt.kt` 删掉。
- [x] 验：
  - 助手、整理员两份、记忆整理员的 system 改前改后逐字节一样；派活只少了轻提醒那三行（临时文件已删）；
  - `./gradlew :app:testDebugUnitTest`：128 条，123 过、5 条真网关的跳过；
  - `LiveGatewayTest` 加了一条派活（「明天帮我取个快递，不用响铃」→ 当天事项、gentle = false；「你在干嘛呀」不建）。**还没跑**：repo 里的网关已经 503，要用火山方舟的 key 跑。
- [x] 文档：DESIGN §2.2、§6.1 包表、§6.3（第 2 条改写、加第 6 条）、§6.8；README 目录树；`Features.kt`、`Transcript.kt` 的注释；CLAUDE.md 现状、容易忘的决定。

### 第 2 步：runtime 抽成包（还在 :app 里）

- [x] 按 3.1 节挪包（20 个文件、47 个声明，类名都没改），切掉 `Format.weekday`、`BuildConfig` 两处依赖（星期几在 `Transcript` 里留一份；打包时的种子挪进 `ProviderStore.seed`）。`Ask.kt` 注释里点 app 全类名的两处改成了文字。
- [x] 加 `ArchitectureTest`：`runtime/` 下不许 import `android.*`、`androidx.*`，也不许出现 runtime 以外的 `com.abc.daodian.*`（注释里也不行）。
- [x] 引擎、上下文、问卡的测试跟着挪包。
- [ ] 验：
  - [x] 编译（debug、release）；单测 129 条，124 过、5 条真网关的跳过；查过挪的类没有按类名存在手机上的（没有序列化、R8 规则里没点名）。
  - [ ] **装 debug 包说一句「三分钟后提醒我喝水」还没做**：那会儿手机没连上（`adb devices` 是空的）。另外 debug 包是新装，模型配置的种子是已经 503 的旧网关，得先在配置页填上火山方舟才说得了话。
- [x] 文档：DESIGN §2.2（加 `runtime`、第 3 条、第 10 条）、§6.1 包表、决策 3.1、§6.2、§6.6、§6.9、§6.10 里的路径；README 目录树；CLAUDE.md 现状和依赖规则。

### 第 3 步：跑 agent 那一层 + 花名册

- [x] 加 `AgentInfo`、`Prompt`、`Agent`、`Input`、`RunResult`、`AgentRuntime`（`runtime/Agent.kt`、`runtime/AgentRuntime.kt`）。`AgentActivity` 并了进去（`exclusive` / `queued` / `running`）。`ModelSource` 是 `ProviderStore.source(context)`，client 按配置缓存一份大家共用；`Tracer` 是 `DaodianApp` 里一个 lambda，交给 `ContextTrace`。
  做的时候和 3.2 节的草稿不一样的：`AgentInfo` 多了 `prompts`（它跑的时候可能用的几份，整理员两份；诊断按它列版本、strict 查跑的那份在不在里面）；记忆库按用到时才取（`memory: () -> MemoryBackend`，进程可能是闹钟拉起的，装的时候不碰 chat.db）；没加 `health` 别名，界面照旧读 `ApiHealth.state`；`runOnce` 多一个 `onOutcome`（整理员边跑边数，跑到一半被停也记得住记了几笔）。
- [x] `Recall.preamble` 的拼法挪进 `runtime/memory/MemoryPreamble`（连 `Digest`），`AgentRuntime.memory` 用它；助手的 `VERSION`、`TOOLS` 和派活的 `VERSION` 这时加上（`assistant-v1`、`relay-v1`）。
- [x] 写好四个 `XxxAgent`（`build(...)`）：`ChatAgent` 改成 `agent/AssistantAgent`；`RelayAgent` 只留定义，跑的那段挪进 `Relay`（订阅者自己的 `ask`）；新加 `OrganizerAgent`、`TidyAgent`。根目录加 `Agents.kt`，`DaodianApp` 里 `install`。
- [x] 五个调用方换上去（3.3 节）。手动 `ApiHealth.record` 5 处、拼循环 4 处全删了；事后 `as?` 取结果的 3 处换成 `payloads<T>()`，整理员边跑边数那一处（`onOutcome` 里）还是 `as?`。
- [x] 打开 strict：花名册检查、`Prompt.tools` 对工具（4.3 节）、`canAsk`。单测 `AgentRuntimeTest`（10 条）测这一层；`AgentsTest` 拿真的四个 agent 按 strict 查一遍（助手的工具照 `Features.kt` 的接法照搬，要 Context 的接头测试里建不出来）。
- [x] 导出诊断加「agent」一段（名字、id、提示词版本），`context_trace.txt` 每行带版本号；后台的几个各写 `trace_<id>.txt`。
- [x] 编译（debug、release）；单测 142 条，137 过、5 条真网关的跳过。
- [ ] 验（debug 包，**还没做**，先不装机）：
  - 助手：说一句建提醒；
  - 整理员：设置 → 记账 → 现在整理，`agent_runs` 多一行、顶栏细线转一圈；
  - 派活：跑 live test，或者让名单上的人真发一句；
  - 记忆整理员：聊到攒够 16 轮、或者等 10 分钟，`tidy_runs` 多一行；
  - 断网说一句：印变灰（健康状态改成自动记了，确认没丢）；
  - 导出诊断里看得到 4 个 agent。
- [x] 文档：DESIGN §2.2、§6.1（加花名册那张表和 `AgentRuntime` 那段）、§6.9、§8.4；README 目录树；CLAUDE.md 现状、「怎么拿证据」（`trace_<id>.txt`、诊断的「agent」段）。

### 第 4 步：拆接头 + 改名（diff 最大，基本是改 import 和改文档）

- [x] 按第 5 节拆成 `Module`、`AssistantSkill`、`ShellEntry`；`Features.kt` 改名 `Modules.kt`；每个模块拆成 `XxxModule` / `XxxSkill` / `XxxShell`（通知监听层只有 `IntakeModule` + `IntakeShell`）。
  做的时候定的细节：两个插口各自带 `id`（用 `XxxModule.ID` 这个常量），`DaodianApp` 装的时候查和模块的一样；`HealthItem` 跟着体检挪进 `shell/entry`；`AppNav.chat` 改名 `AppNav.assistant`。
- [x] 按第 6 节改名挪包：`agent/` 拆成 `assistant/` 和 `shell/`（挪了 50 个文件，Chat 开头的类改名 12 个）；小写的也改了：`Recall.conversation`、`Tidy.conversationBusy`、`LedgerTools.forAssistant`、`ToAssistantBand`、内存里的锁名 `assistant-tidy`。`AppCatalog.isChat`（是不是聊天软件）不是一个意思，留着。`ShellRoutes.CHAT`、`MEMORY` 挪成 `AssistantRoutes.HOME`、`MEMORY`。
  按全类名记的三个照表处理了：`QuickAddActivity` 在 manifest 里留了旧名字的 `activity-alias`；`TidyWorker` 改成 `open`，旧全类名处留空壳子类；`ChatDatabase` 改名 `ConversationDatabase`，`app/schemas/` 目录跟着改名 —— KSP 照新类名导出的 3.json 和原来那份逐字一样（identity hash `667766dc…`）。release 包里查过（dexdump、aapt2）：两个 `TidyWorker` 都在、alias 在。
  中途出了一下岔子，都收拾了：挪包脚本跑到一半停了（新加的 `TidyAgent.kt` 还没进 git，`git mv` 不认），续跑时补齐；一遍 import 重排碰了 14 个只排了序的文件（其中 5 个在 `reminder/scheduling`），都还原成原样了，`reminder/scheduling`、`reminder/data` 这一步一行没改。
- [x] 清掉第 5 节表里那几处：编辑页的「依据」走 `CallHistory`；小组件走 `Launch.HOME`、`Launch.quickAdd`（`DaodianApp` 里 `Launch.install`）；诊断的 `time` 挪进 `shared/format/DiagnosticText`、`rows` 挪进 `shared/data/Rows`。
- [x] `ArchitectureTest` 补全 2.1 节那张表（DESIGN §2.2 也换成了这张表）。故意在提醒的 `tools/` 里塞一行 import 助手对话页的，测试报了，撤掉了。
- [x] 编译 debug、release；单测 143 条，138 过、5 条真网关的跳过。
- [ ] 验（**还没做**，没装机）：
  - 库类改名，用 debug 包演一遍：先装改之前的 debug 包、聊几句、记一条记忆，再盖上新包，拉库看 `PRAGMA user_version` 还是 3、记录都在。办法见 CLAUDE.md「演一遍库迁移」。
  - release 包覆盖装（版本号不变、签名对）：
    - 上一个进程的退出原因是 `PACKAGE UPDATED`；
    - `dumpsys alarm` 装前装后一样；
    - 通知使用权还在、监听连着；
    - **不开 app**，直接点小组件的墨印，速记那张纸能出来；
    - 对话历史、图片都在，记忆页条数一样；
    - 说一句建提醒、问一句账；
    - 设置、权限页、提醒页、记账页、派活那一页都点一遍。
- [x] 文档：DESIGN §2.2 整节重写（模块、依赖规则那张表、接头表、路由、界面归属、挪包怎么留旧名字），其余各节里的 `agent/…` 路径和旧类名；CLAUDE.md 现状、代码指针、依赖规则、踩过的坑里「挪包要连数据卸载」那条；README 目录树。

### 第 5 步：runtime 变成 Gradle 模块

- [x] 新建 `:runtime`：`kotlin("jvm")`，Kotlin 2.0.21，和 app 一样，产 Java 17 的字节码。不用 AGP，不碰 README 里那条版本约束链。源码放 `runtime/src/main/java`（和 app 一个习惯）。
- [x] `runtime/` 目录挪进去；openai-java 跟过去（`implementation`，app 看不见它），协程是 `api`（对外接口里全是 Flow）；测试跟过去；app 加 `implementation(project(":runtime"))`，另外自己依赖 Jackson（各模块的工具读参数用）；`ArchitectureTest` 里关于 runtime 的两条删了（编译器管了）。协程、Jackson 进了 `libs.versions.toml`，钉的是 app 原来就解析出来的版本。
  拆开时编译器抓出两处同一个模块里看不出来的：`MemoryJson`（`internal`）只有 app 的 `edit_memory`、`save_tidy` 在用 —— 挪回 `assistant/memory`，和这两个工具放一起；问卡（`AskCard`）里对 `AskQuestion` 几个属性的 smart cast 跨模块不成立 —— 先取成局部变量。对话页测试要用的 `FakeWriteTool` 在 app 的测试里留了一份（测试代码不跨模块）。
- [x] 验（不装机的部分）：`./gradlew :runtime:test :app:testDebugUnitTest :app:assembleDebug` 过（`:runtime` 42 条、`:app` 100 条，5 条真网关的跳过）；`:app:assembleRelease` 过，R8 映射表里 runtime 的 115 个类都在；拆前拆后 release 包解析出来的依赖逐个对过，一样（137 个）。
- [ ] 验：装 debug 包说一句（**还没做**，没装机）。
- [x] 文档：README（两个模块、目录树、单测命令、依赖版本那段）、DESIGN §2.2、§03 技术栈；CLAUDE.md 发版前跑的单测命令、现状。

### 第 6 步（可选）：工具写起来省点事

- [ ] runtime 里加一个 schema 小 DSL 和一个参数读取器。现在每个工具都自己手写 `mapOf("type" to …)` 和一份 `string()` / `boolean()` 小函数，strict 要求的 required 全列、`additionalProperties = false` 也是手写的。
- [ ] 新工具用新写法，旧工具碰到的时候再改。改的时候对一下 schema 序列化出来的 JSON 一样。

---

## 9 待定

- 对话页一层的类名用 `Conversation*`（第 6 节就是照这个写的）。要是想全叫 `Assistant*`，第 4 步之前说。
- 时间推算那段，助手和派活现在是两种写法。要不要合成一样的说法，第 3 步之后单独看：合了会改字，助手的前缀缓存要失效一次，得真机再验。
- `TidyWorker` 的空壳子类和 `QuickAddActivity` 的 alias，留到哪个版本删。
