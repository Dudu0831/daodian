# 工程目录规划

本文件描述 `app/src/main/java/com/abc/daodian/` 的**目标包结构**和迁移方案，不是当前目录清单。仍是一个 Gradle 模块，下面说的「模块」指包层面的业务边界。

迁移方式已定：**一次挪到位，不留旧路径的兼容层；新包连数据卸载后全新安装**。所以不用照顾任何按旧类名存下的东西：数据库版本回到 1、schemas 重新导出、类名想怎么挪就怎么挪。代价是提醒、账本、对话记录、配置、权限全部清空重来，见「卸载重装」一节。

## 思路

三个模块：`agent`、`reminder`、`ledger`。

- `agent` 是智能交互：引擎、对话界面、把大家拼起来的宿主壳。它**只认识一个接口**（`Feature` / `FeatureUi`），不知道提醒和账是什么。
- `reminder`、`ledger` 各自能独立运转（提醒断网也要响，手动建一条不经过模型；记账在后台采集、整理、对账），再通过**一个接头**接到 agent 上：给工具、给提示词、给痕、给页面、给抽屉卡、给设置组。
- 以后加模块 = 新目录 + 一个接头 + `Features.kt` 里加一行。

另外两样不算模块：根目录的装配（3 个文件）和 `shared/` 地基。

## 目录

```text
com/abc/daodian/
├── DaodianApp.kt                # 启动：把模块清单装进注册表，挨个调 onAppStart
├── MainActivity.kt              # 唯一的宿主 Activity，只挂 agent/shell 的 NavHost
├── Features.kt                  # 全 app 唯一的模块清单：listOf(ReminderFeature, LedgerFeature)
│
├── agent/                       # 模块一：智能交互。只认识 Feature 接口
│   ├── engine/                  # Agent 循环、会话、事件
│   │   ├── ask/                 # ask_user：执行中向用户提问、挂起等回答
│   │   ├── context/             # 选择喂给模型的轮次
│   │   ├── tool/                # 工具接口、ToolRegistry
│   │   └── background/          # 后台 agent 在跑什么；账本锁这类命名锁
│   ├── model/                   # 调一次模型：请求、流式、回退
│   │   └── provider/            # 供应商配置、存储、连通状态、「测一下」
│   ├── feature/                 # 模块接入 agent 的唯一接口：Feature、TraceView、HealthItem、Trigger、注册表
│   ├── prompt/                  # 基础提示词：时间、何时用工具、怎么问用户、怎么报结果
│   ├── conversation/            # 对话页：回合、问卡、痕的通用画法、输入框、顶栏印章、ChatAgent、ChatViewModel
│   │   └── data/                # chat.db：对话历史存取
│   ├── voice/                   # 本地语音识别（对话页和桌面速记共用）
│   ├── entry/
│   │   └── quick/               # 桌面速记：从小组件长出来的那张纸
│   └── shell/                   # 宿主壳：导航、抽屉框、设置页框、模型配置页、FeatureUi
│
├── reminder/                    # 模块二：提醒。可靠排期和送达是核心，脱离 agent 也要能用
│   ├── ReminderFeature.kt       # 接头：实现 Feature + FeatureUi
│   ├── domain/                  # 计划、闸门、重复规则
│   ├── data/                    # 提醒表、投递日志（reminder.db）
│   ├── application/             # Reminders：所有写操作的唯一入口
│   ├── scheduling/              # 排闹钟、到点、重排、兜底巡检、当天事项
│   ├── delivery/                # 通知、通知按钮、权限体检
│   ├── tools/                   # create_reminder、提醒那段提示词、提醒的痕怎么写
│   ├── widget/                  # 桌面小组件：显示提醒；墨印拉起 agent 的桌面速记
│   └── presentation/            # ReminderViewModel、抽屉卡、设置组，以及：
│       ├── list/                # 时间轴列表
│       ├── edit/                # 手动建 / 改（逃生舱，不依赖模型）
│       ├── alarm/               # 到点全屏页
│       └── log/                 # 投递日志
│
├── ledger/                      # 模块三：记账
│   ├── LedgerFeature.kt         # 接头
│   ├── domain/                  # 流水、类别、金额、LedgerBackend、护栏、给模型读的写法、日期
│   ├── data/                    # LedgerStore、记账设置
│   │   └── db/                  # ledger.db
│   ├── capture/                 # PaySampler（通知监听服务）、听哪几家、遮蔽正文
│   ├── organize/                # 后台整理 agent
│   ├── reconciliation/          # 每晚对账
│   ├── tools/                   # 查账 / 记账 / 改账 / 加类别、记账提示词、记账的痕怎么写
│   └── presentation/            # 总览、类别、一笔、LedgerViewModel、抽屉卡、设置组
│
└── shared/                      # 地基，不是模块。三个模块都能用，它谁也不依赖
    ├── theme/                   # 色板、字体、动效
    ├── ui/                      # 图标、徽标、页头、PaperGroup、SettingRow
    ├── format/                  # 通用的人话时间、星期、主机名
    └── navigation/              # Launch：用路由字符串拉起 app（通知、小组件、速记共用）
```

## 接头

```kotlin
// agent/feature/Feature.kt —— 模块给 agent 的（不碰 Compose）
interface Feature {
    val id: String                                     // "reminder"；也是它的路由前缀
    val prompt: String                                 // 接在基础提示词后面的一段
    fun tools(context: Context): List<Tool>            // 对话 agent 的工具（桌面速记同一套）
    fun trace(call: Item.ToolCall, result: Item.ToolResult?): TraceView? = null  // 它的写操作留什么痕
    val examples: List<String> get() = emptyList()     // 对话空状态的例句、速记的提示
    val manualEntry: String? get() = null              // 连不上模型时「手动填一条」去哪（路由）
    suspend fun trigger(key: String): Trigger? = null  // app 自己发起的一轮（每晚对账）
    fun health(context: Context): List<HealthItem> = emptyList()  // 体检项
    fun onAppStart(context: Context) {}                // 冷启动要做的（重排、排巡检、排对账…）
}

// agent/shell/FeatureUi.kt —— 模块给界面壳的
interface FeatureUi {
    fun NavGraphBuilder.routes(nav: NavController)             // 自己的页面，路由以 id 开头
    @Composable fun DrawerCard(open: (String) -> Unit) {}      // 抽屉里的一张纸（外框和标题行由壳画）
    @Composable fun SettingsSection(open: (String) -> Unit) {} // 设置页里的一组
}
```

- `ReminderFeature`、`LedgerFeature` 各是一个同时实现两个接口的 object。
- `TraceView`：在办时的标签、办成 / 没办成的标签、一行字、可展开的逐条、点了去的路由。
- `Trigger`：要么是一轮的开场白（交给 agent 开一轮），要么是一句话（直接显示，比如「账都对上了」）。
- 注册表 `agent/feature/FeatureRegistry`：`DaodianApp.onCreate` 把 `Features.kt` 的清单装进去，agent 只读它。Worker、Receiver 都在 Application 之后跑，拿得到。
- **路由约定**：agent 用 `chat`、`settings`、`provider`；提醒用 `reminder/list`、`reminder/edit?id=`、`reminder/log`；记账用 `ledger`、`ledger/category/…`、`ledger/txn/{id}`。`Launch` 的 intent 带 `route`，可选 `trigger`（如 `ledger:check`）、`say`（速记交给对话页的那句话）。

## 界面归属

按「它画的东西归谁」分三种：

1. **对话的画法 → `agent/conversation`、`agent/entry/quick`**。回合怎么长、问卡、痕的样子、输入框、速记的纸。只认识「回合、问卡、痕」。痕的文字和去处来自 `Feature.trace`，agent 统一画、统一跳。
2. **模块自己的页面 → 各模块 `presentation/`**。提醒的列表、编辑、到点全屏、投递日志；账本三层。ViewModel 按 Activity 作用域取，和今天「MainActivity 建一个 VM 往下传」效果一样。
3. **宿主壳 → `agent/shell`**。只有框，内容由模块塞：
   - **导航**：`chat` 是起点；遍历各模块的 `routes` 注册；`Launch` 带来的 route / trigger / say 在这里分发。
   - **抽屉**：顶上「到点 · 回到对话」；中间每个模块一张纸；底下「设置」和体检结论（汇总各模块 `health`）。
   - **设置页**：体检结论 →「模型服务」（agent 自己的）→「系统权限」（汇总 `health` 的每一项，统一画法）→ 各模块的 `SettingsSection`。
   - **模型配置页**。

ViewModel 的分工：
- `ChatViewModel`（agent）：对话、问卡、停、重试、app 发起的一轮、模型配置。
- `ReminderViewModel`：提醒列表、投递日志、收尾时刻；写操作一律转给 `application/Reminders`。
- `LedgerViewModel`：不变。

## 依赖规则

1. `agent` 不 import `reminder`、`ledger`。需要什么都经 `Feature` / `FeatureUi` 拿。
2. `reminder`、`ledger` 互不 import。
3. 模块的**核心层不 import `agent`**：提醒的 `domain / data / application / scheduling / delivery`，记账的 `domain / data / capture`。能 import `agent` 的只有接头文件、`tools/`、`presentation/`、`widget/`、`organize/`、`reconciliation/`。
4. `shared` 不 import 任何模块，也不 import 根目录。
5. 跨模块跳转只用路由字符串。模块不 import `MainActivity`，拉起 app 走 `shared/navigation/Launch`。
6. 根目录只做装配：列模块、装注册表。

## 今天的耦合，各收到哪

| 今天写死的 | 收到哪 |
|---|---|
| `ui/Agents.kt` 列工具、拼 `HarnessPrompt + LedgerPrompt.CHAT` | `ChatAgent` 遍历注册表：基础提示词 + 各模块 `prompt`，各模块 `tools` |
| `HarnessPrompt` 末尾的「提醒的规则」 | `reminder/tools/ReminderPrompt`。原样切出，按原顺序拼回去逐字节不变（前缀缓存） |
| `ChatMessage.TRACED_TOOLS` 写死 4 个工具名 | 改看 `Tool.effect == WRITE`（`Tool.kt` 本来就这么定义痕） |
| `ChatMessage.createdReminderId`（速记判断「记好了」） | 回合的 `committed` |
| `Trace.kt` 的 `traceViewOf`、`whenOf` | `reminder/tools/ReminderTrace`、`ledger/tools/LedgerTrace`，经 `Feature.trace` |
| `TraceTarget.Reminder / Txn` | `TraceView.route` |
| 对话空状态的四条例句；速记的「比如明天下午三点交房租」 | `Feature.examples` |
| 出错时「手动填一条」；速记的 `onManual` | `Feature.manualEntry` |
| `TriggerDivider` 写死「每晚对账」 | 删掉特判，已有的「取冒号前一段」就够 |
| `MainViewModel.startLedgerCheck` 调 `LedgerCheck`、`LedgerCheckPrompt` | 记账的 `trigger("check")` |
| `MainViewModel` 的提醒增删改、日志、收尾时刻；`PlanCommitter` | `reminder/application/Reminders` + `ReminderViewModel` |
| `MainDrawer` 的 `ReminderPaper`、`LedgerPaper`；`ChatScreen` 为抽屉拿 `LedgerViewModel` | `FeatureUi.DrawerCard` |
| `SettingsScreen` 的提醒组、记账组，拿 `LedgerViewModel` | `FeatureUi.SettingsSection` |
| `HealthCheck`（设置页结论、抽屉「还差 N 项」） | `Feature.health`；「应用启动管理」那条手动项归提醒 |
| `DaodianNavHost` 的全部路由 | `FeatureUi.routes` |
| `WidgetTarget` 枚举各模块的去处 | `Launch`：route + trigger + say |
| `ChatStore.basisOf` import `CreateReminderTool` | `ChatStore` 只给「某工具指向某条记录时的参数」，提醒自己解析 |
| `ProviderTest` import `CreateReminderTool`、`HarnessPrompt` | 用 `ChatAgent` 组好的 system 和工具（顺带和真实请求对齐：今天少了记账那段和 ask_user） |
| `QuickAddViewModel` 调 `WidgetUpdater.announce` | 提醒落库时由 `Reminders` 自己刷新、点亮小组件 |
| `DaodianApp` 挨个调两边的启动逻辑 | `Feature.onAppStart` |
| `Rescheduler`、`Notifier` 引用 `MainActivity` | `shared/navigation/Launch` |
| `WidgetFrame` 靠 `DaodianWidget` 类找小组件 | 小组件在 PendingIntent 里带上 appWidgetId |
| `LedgerStore`、账本页用 `ListExpensesTool.UNCATEGORIZED` | 挪进 domain：`ExpenseQuery.UNCATEGORIZED` |
| `LedgerText`、`LedgerCheck` 用 `LedgerJson` 的日期函数 | 拆到 `ledger/domain/LedgerDays`；`LedgerJson` 只留 schema 和读参数 |

## 卸载重装

挪包 = 改全类名。系统按全类名记住的东西（启动器图标、通知使用权、桌面小组件、已排的闹钟、WorkManager 里的 Worker、Room 的 schemas 目录）卸载时一起清掉，所以**哪个类都能挪，不用照顾旧名字**。代码里要做的只剩：

- **数据库回到 1 版**：提醒库改名 `ReminderDatabase`、文件名 `reminder.db`，`version = 1`，删掉 `autoMigrations` 里那条 1→2（机制留着，以后改表照样往里加）。聊天库、账本库本来就是 1 版。`app/schemas/` 下三个旧目录删掉，首次编译按新全类名重新导出。
- **巡检任务名** `daodian_sweep` 直接改成 `reminder_sweep`，不用 cancel 旧名。
- **调研样本导入删掉**：`LegacySamples` 和 `DaodianApp` 里调它的那行。卸载后 `files/pay_samples*.jsonl` 不在了，这段代码再也跑不到。
- **Launch 的 extra 换成 route**：CLAUDE.md 里的 `am start --es com.abc.daodian.widget.TARGET new/list` 跟着改。

**会清空的**（`com.abc.daodian.debug` 的全部私有数据）：

| 清掉的 | 装回来之后 |
|---|---|
| 提醒、投递日志（`daodian.db`） | 空的，重新建 |
| 账本（`ledger.db`）、调研样本 `files/pay_samples.imported.jsonl` | 空的，从下一笔真实支付通知开始记。**样本是 9-18 起唯一的原始通知**，想留底的话卸载前拷到电脑上（「迁移顺序」第 6 步） |
| 对话记录（`chat.db`） | 空的 |
| 模型配置（DataStore `provider`） | 退回 `secrets.properties` 种子 —— 那个网关已经 503，要在配置页重新填火山方舟 |
| 收尾时刻（`day_tasks`）、记账设置（`ledger`） | 默认值（收尾 20:00） |
| 通知、精确闹钟、全屏通知、通知使用权、电池 / 应用启动管理 | 按设置页体检一条条重新开 |
| 桌面小组件 | 长按桌面重新添加 |

## 迁移对照表（现在 → 目标）

**根目录**
- `DaodianApp.kt`、`MainActivity.kt` → 位置不动（改成装注册表、挂 `agent/shell` 的 NavHost；删掉调样本导入那行）
- 新增 `Features.kt`

**`harness/` → `agent/`**
- `AgentLoop`、`AgentEvent`、`Session`、`Transcript` → `agent/engine/`
- `ask/`、`context/`、`tool/`、`background/` → `agent/engine/` 下同名子包
- `llm/LlmClient`、`ResponsesClient` → `agent/model/`
- `provider/*`（含 `ProviderTest`） → `agent/model/provider/`
- `prompt/HarnessPrompt` → `agent/prompt/BasePrompt`（前四节）+ `reminder/tools/ReminderPrompt`（「提醒的规则」）
- `builtin/reminder/ReminderPlan`、`PlanValidator` → `reminder/domain/`
- `builtin/reminder/CreateReminderTool` → `reminder/tools/`
- `builtin/ledger/LedgerModel`、`LedgerBackend`、`LedgerGuard`、`LedgerText` → `ledger/domain/`
- `builtin/ledger/` 的 5 个工具、`LedgerTools`、`LedgerJson`、`LedgerPrompt` → `ledger/tools/`

**`data/`、`schedule/`、`notify/`、`recur/` → `reminder/`**
- `data/Entities`、`Daos`、`Converters` → `reminder/data/`
- `data/DaodianDatabase` → `reminder/data/ReminderDatabase`（`reminder.db`，回到 1 版）
- `data/chat/ChatDatabase`、`ChatStore` → `agent/conversation/data/`
- `schedule/Rescheduler`、`FireHandler`、`DayTasks`、`AlarmReceiver`、`RescheduleReceiver`、`SweepWorker` → `reminder/scheduling/`
- `schedule/NotificationActionReceiver`、`notify/Notifier` → `reminder/delivery/`
- `recur/Rrule` → `reminder/domain/`

**`ledger/`**
- `PaySampler.kt` → `capture/`（里面的 `LegacySamples` 删掉）
- `PaySources` → `capture/`
- `LedgerStore`、`LedgerSettings` → `data/`；`db/*` → `data/db/`
- `organize/*` → 位置不变
- `check/LedgerCheck.kt` → `reconciliation/` 下 `LedgerCheck`、`CheckReceiver`、`CheckWorker` 三个文件；`check/LedgerCheckPrompt` → `reconciliation/`

**`widget/`**
- `DaodianWidget`、`WidgetActionReceiver`、`WidgetRenderer`、`WidgetUpdater` → `reminder/widget/`
- `WidgetFrame` → `agent/entry/quick/`
- `WidgetLaunch` → `shared/navigation/Launch`（`WidgetTarget` 换成路由）

**`ui/`**
- `Agents` → `agent/conversation/ChatAgent`
- `MainViewModel` → `agent/conversation/ChatViewModel` + `reminder/presentation/ReminderViewModel` + `reminder/application/Reminders`
- `PlanCommitter` → `reminder/application/Reminders`
- `HealthCheck` → `reminder/delivery/`（`HealthItem` → `agent/feature/`）
- `DaodianNavHost` → `agent/shell/AppNavHost`
- `chat/ChatScreen`、`ChatInputBar`、`ChatComponents`、`ChatMotion`、`AskCard`、`ChatMessage` → `agent/conversation/`
- `chat/Trace` → `agent/conversation/TraceLine`（通用画法）+ 各模块 `tools/XxxTrace`（`TraceView` → `agent/feature/`）
- `chat/DraftArgs` → `agent/feature/PartialArgs`（只留 `partialText`）
- `common/ProviderSeal`；`common/TopBar` 里的 `ChatTopBar` → `agent/conversation/`
- `common/TopBar` 里的 `ScreenTopBar`、`IconTapTarget`；`common/Icons`、`Badge` → `shared/ui/`
- `common/Ledger` → `shared/ui/PaperGroup`（`LedgerLabel/Group/Rule` → `GroupLabel/PaperGroup/GroupRule`，和记账功能撞名）
- `common/Format` → 拆开：通用的人话时间、星期、主机名 → `shared/format/`；`humanRrule` → `Rrule` 旁边（合掉两份 RRULE 解析）；`dayTaskWhen` → 提醒；`chineseDate` → `reminder/presentation/alarm/`
- `theme/*` → `shared/theme/`
- `alarm/*` → `reminder/presentation/alarm/`
- `list/ReminderListScreen` → `reminder/presentation/list/`
- `edit/EditReminderScreen`、`EditSheets` → `reminder/presentation/edit/`
- `settings/SettingsScreen` → `agent/shell/`（框）+ `reminder/presentation/ReminderSettingsSection` + `ledger/presentation/LedgerSettingsSection`；`SettingRow`、`Marker`、`FixLink` → `shared/ui/`
- `settings/ProviderScreen` → `agent/shell/`
- `settings/FireLogScreen` → `reminder/presentation/log/`
- `ledger/LedgerScreens`、`LedgerViewModel`、`LedgerFormat` → `ledger/presentation/`
- `ledger/MainDrawer` → `agent/shell/AppDrawer`（框）+ `reminder/presentation/ReminderDrawerCard` + `ledger/presentation/LedgerDrawerCard`
- `quick/QuickAddActivity`、`QuickAddScreen`、`QuickAddViewModel`、`QuickTrace` → `agent/entry/quick/`
- `quick/VoiceInput` → `agent/voice/`

**`test/`**
- `recur/RruleTest` → `reminder/domain/`
- `harness/AgentLoopTest`、`ask/AskUserToolTest` → `agent/engine/`
- `ui/chat/ChatMessageTest` → `agent/conversation/`
- `harness/builtin/ledger/LedgerToolsTest` → `ledger/tools/`
- `harness/LiveGatewayTest` → 测试根目录（它测的是整条链路：真网关 + 提醒工具）
- `AgentLoopTest`、`ChatMessageTest` 里借用的 `CreateReminderTool` 换成测试自己的假工具 —— agent 的测试不依赖提醒

**顺手删掉**：`DraftArgs.parse / EMPTY / isEmpty / whenText`、`InkHairline`、`LedgerTools.NAMES`（都没人用了）；`LegacySamples`（卸载后没有样本文件可导）；`app/schemas/` 三个旧目录。重复的小工具合并：星期数组 7 份 → `shared/format`；`hostOf` 2 份 → `shared/format`；`dayInt` 4 份 → `ledger/domain/LedgerDays`。

## 迁移顺序

每步单独提交，编译通过、单测通过再进下一步。

1. **地基和引擎**：建 `shared/`；`harness/` → `agent/engine`、`agent/model`。只挪包、改 import。
2. **接头**：写 `agent/feature`、`agent/shell/FeatureUi`；`ChatAgent`、对话画法、NavHost、抽屉、设置页改成只走接头，逐条消掉「今天的耦合」表。
3. **两个模块落位**：提醒、记账按新结构搬，写 `ReminderFeature`、`LedgerFeature`；`MainViewModel` 拆完；`Format` 拆开；删死代码。
4. **Android 收尾**：Manifest 路径；`ReminderDatabase` 回 1 版、删旧 schemas；巡检改名 `reminder_sweep`；`Launch` 路由化；小组件带 appWidgetId；单测挪位、agent 测试换假工具。
5. **文档**：DESIGN / CLAUDE / LEDGER_PLAN / README 里的路径；CLAUDE.md「不要碰的假设」改成 `reminder/scheduling/` 和 `reminder/data/`；「Room 升到 v2、在 `DaodianDatabase.autoMigrations` 加」改成 `ReminderDatabase`、1 版；小组件那条的 `am start` 命令；「清账本重来」那段删掉（卸载就是清账本）。
6. **卸载前留底（可选，你决定）**：debug 包能 `run-as`，想留的拷到电脑上，比如调研样本：
   `adb exec-out run-as com.abc.daodian.debug cat files/pay_samples.imported.jsonl > pay_samples.jsonl`
7. **卸载、装新包**：`adb uninstall com.abc.daodian.debug`（清掉全部数据，你来执行）→ `./gradlew :app:installDebug`。
8. **真机验收**：
   - 设置页体检：通知、精确闹钟、全屏通知、通知使用权、电池 / 应用启动管理逐条开到全绿
   - 配置页填火山方舟 →「测一下」通过（这时 `ProviderTest` 已经走真实的 system 和工具）
   - `databases/` 里是 `reminder.db`、`chat.db`、`ledger.db`，没有 `daodian.db`
   - 对话里建一条提醒 → 痕打勾 → `dumpsys alarm` 里是 `reminder.scheduling.AlarmReceiver` → 点痕进编辑页，「依据」有值
   - WorkManager 里巡检是 `reminder_sweep`
   - 桌面加小组件 → 点墨印速记建一条 → 纸自己收起、小组件亮「刚记下」
   - 付一笔真钱 → 账本里出现 →「现在整理一次」跑通
   - 对账通知点「现在」→ 对话页开一轮对账
   - 定一条两分钟后的提醒，真响一次，通知上点「完成」后闹钟和通知都收掉
