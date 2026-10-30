# 改造方案：通知管家（规则、收件箱、子代理）

2026-10-26 定的方向，**第 1–5 步做完了（10-27，没接线；建规则的工具只进 debug 包）**。为什么这样分、要守的规矩在 DESIGN §2.3、§12；这份只管怎么落：目录、接口、库、步骤、测试。做完一步在第 8 节打勾；全部做完后，要紧的决定并进 DESIGN，这份删掉（和 `REFACTOR_PLAN.md` 一样）。

## 0 一句话

通知先过每条规则的**硬条件**（代码，只管进不进来），中了的存进**收件箱**（`intake.db`），**每一条**都交给**通知管家**（intake 里唯一的 agent，带着规则的软规则定去处），管家把活交给模块的**子代理**（记账整理员、提醒子代理……）。规则只在对话里建。

**旧配置不搬、旧数据不删**：升上来从零开始，在对话里重新说要听什么；`relay.db`、DataStore `intake` / `relay`、`ledger.db` 里的原文都原样留在手机上，只是不再读（第 6 节）。

---

## 1 为什么改

| 现在 | 证据 | 后果 |
|---|---|---|
| 分发只认包名 | `intake/Router.targets` 只看 `pkg in routes[id]` | 「正文里有取件码」这种条件表达不了，快递、航班这类事进不来 |
| 更细的规则写死在订阅者里 | `Relay.accept` 里认人、剥「[3条]」、看暗号；记账一条都不筛 | 规则是代码，人配不了、模型也建不了；加一个场景就要改一个模块 |
| 配置、原文、记录页各做一份 | 勾 app 在「权限与监听」、名单在提醒设置；原文分在 `raw_notification`、`relay_messages`；「抓到的通知」页、这个人那一页 | 一条短信两边都勾就存两份；想知道它被拿去干了什么，得两边翻 |
| 读懂的人跟着模块走 | 派活 agent 只会「某人说的一句 → 提醒」 | 「快递到了 18:00 提醒我」这种交代没处放 |

---

## 2 改完的样子

### 2.1 链路

```
NoticeListenerService（不改名、不挪）
  └─ Intake.receive(notice)
       收的流水线（代码，不允许失败）：
         Normalize   按包名挑整理格式 → 整理过的标题、正文
         Match       每条启用的规则对一遍硬条件 → 中了哪几条 / 遮蔽版待定 / 没中
         Store       中了的进收件箱（按指纹去重）；没中的扔
         Wake        遮蔽版 → 叫监听层 5s / 30s / 2min 重读；落成待办 → 叫调度（poke）
  └─ StewardScheduler   有人叫 → 防抖 3 秒 → Steward.runOnce() + Dispatcher.runOnce()（一次只跑一轮）
       Steward          取一批待办 → 拼输入 → AgentRuntime.runOnce(StewardAgent)
                        工具只产出「决定」（交给谁 / 不相关）→ 一个事务写进收件箱和交接表
  └─ Dispatcher         交接表里待交的 → targets[to].accept(...) → 回执写回收件箱；失败重试 3 次
       ├─ LedgerNoticeTarget    原文复制进 raw_notification → 整理员 3 小时一批（不变）→ 整理完回写
       ├─ ReminderNoticeTarget  提醒子代理 → create_reminder → 通知你 → 回写「18:00 取快递」
       └─ 以后的模块            实现 NoticeTarget + 自己的子代理
```

### 2.2 目录

★ 是 agent。

```
intake/
  IntakeModule.kt              接头：shell + assistant（新：IntakePlugin）+ onAppStart（催绑、叫一次管家、清 30 天前的）
  Intake.kt                    门面：install(targets)、receive、listener 状态、sweep、rebind（大部分不变）
  NoticeListenerService.kt     不改名（通知使用权按组件授）；onNotificationPosted 改调 Intake.receive
  Notice.kt                    原样的一条（不变）
  core/                        纯 Kotlin，不碰 Android，JVM 单测
    format/    Normalized、NoticeFormat、ChatFormat、PlainFormat、Formats（按包名挑）
    rule/      Rule、Condition（各种硬条件 + AllOf）、Verdict、AppNames、RuleDraft、RuleCheck
    pipeline/  Stage、Catch、Pipeline；四个 Stage：Normalize、Match、Store、Wake
    inbox/     InboxState（状态机）、InboxPolicy（几个数）、InboxNotice、Stored、Landing（一条到门口怎么落）
    steward/   Decision（交给 / 不相关）、Settlement（一轮落库的全部）、DecisionBuffer（收、核、算覆盖）、StewardInput（拼输入）、Destination
    ports/     RuleRepository、InboxRepository（HandoffRepository 第 4 步跟 Dispatcher 一起写）
  data/        IntakeDatabase（intake.db）、IntakeEntities、IntakeDao、RuleCodec、RoomRuleRepository、RoomInboxRepository
  steward/     ★StewardAgent、StewardPrompt、Steward、StewardScheduler、Dispatcher、HandOffTool、IgnoreTool
  plugin/      给模块的插口：NoticeTarget、Handed、Receipt、NoticeReports
  assistant/   IntakePlugin + 六个工具 + 痕
  presentation/  权限页那一组（留）、规则页、收件箱记录页（第 7 步，先出设计稿）

ledger/capture/LedgerNoticeTarget.kt      取代 LedgerCapture（Adapter → LedgerStore.ingest）
reminder/notice/                           取代 reminder/relay/
  ReminderNoticeTarget.kt、★ReminderNoticeAgent.kt、ReminderNoticePrompt.kt
```

### 2.3 依赖规则（变的几条）

| 哪里 | 现在 | 改完 |
|---|---|---|
| `module` | 两个插口包 | 三个：加 `intake/plugin`（`Module.notice: NoticeTarget?`，和 `assistant`、`shell` 并排） |
| 模块的核心层 | 自己、`shared`、`intake` 核心（`Notice`、`NoticeSubscriber`、`Intake`、`IntakeRoutes`） | 自己、`shared`、`intake/plugin`、`Notice`（`Handed` 带着原样的那条）、`IntakeRoutes`。第 3 步先加上 `intake/plugin`，`NoticeSubscriber`、`Intake` 第 8 步再去掉 |
| `intake` 的核心（`core/`） | — | 只有自己和 `intake.Notice`：不碰 `shared`、Android、Room、`runtime`（`ArchitectureTest` 第 1 步起就查） |
| `intake` 的其余部分 | 自己、`shared`；接头和界面再加 `shell/entry`、`module` | 再加 `runtime`（管家要跑 agent）、`assistant/plugin`（`IntakePlugin`） |

`ArchitectureTest` 照新表改。intake 仍然不 import 任何模块：去处是模块经 `Module.notice` 交上来的。

### 2.4 花名册

| agent | 归谁 | 什么时候醒 | 工具 |
|---|---|---|---|
| 助手 | 助手 | 不变 | 原来的 + `list_apps`、`recent_senders`、`preview_rule`、`list_rules`、`add_rule`、`stop_rule` |
| 记账整理员 | 记账 | 不变 | 不变 |
| ★通知管家 `intake/steward/StewardAgent` | 通知监听 | 后台：收件箱有待办，防抖 3 秒 | `hand_off`、`ignore` |
| ★提醒子代理 `reminder/notice/ReminderNoticeAgent`（id `reminder-notice`，取代派活） | 提醒 | 后台：管家交来一条 | `create_reminder` |
| 记忆整理员 | 助手 | 不变 | 不变 |

---

## 3 设计模式：用在哪、换来什么

只在能换来东西的地方用；名字照模式叫，读代码的人一眼知道它干什么。

| 模式 | 用在哪 | 换来什么 |
|---|---|---|
| **Specification + Composite** | 硬条件：`Condition` 一种一个类，`AllOf` 把它们并起来 | 加一种条件 = 加一个类；匹配、编解码、预演、痕上的人话（`describe()`）都在这个类里，别处不用改 |
| **Strategy + Registry** | 整理格式：`Formats.of(pkg)` 挑 `ChatFormat`（微信、QQ……）或 `PlainFormat` | 认一个新聊天软件的格式 = 加一个策略、注册表里一行 |
| **Pipeline（Chain of Responsibility）** | 收：Normalize → Match → Store → Wake，每一段可以叫停 | 以后要加一道（比如全局隐私闸）= 插一段，前后都不动；每一段能单独测 |
| **Repository（Ports & Adapters）** | `core/ports` 只有接口，Room 实现在 `data/` | 核心是纯 Kotlin，JVM 单测用内存假实现，不起 Android |
| **State（状态机）** | 收件箱一行：待办 → 办着 → 办完 / 没办成，转移表写死 | 不会出现「办完了又退回待办」这种乱跳；转移表单测 |
| **Observer + 防抖** | 流水线的 `Wake` 落成待办时叫一声（回调），调度器把叫声 `debounce(3s)` | 收和办解开：流水线只知道「有人要听」，不知道管家存在。**不盯待办数**：管家跑失败把一批放回待办，待办数一变又叫醒自己，就成了每 3 秒一次的死循环（第 4 步改的） |
| **Command** | 管家的工具不直接改库，只往 `DecisionBuffer` 里放「决定」；一轮跑完一个事务落库 | 模型跑到一半断了，什么都没写，下次整批重来；决定能单测、能重放 |
| **Transactional Outbox** | 决定落库时同一个事务写 `handoffs`（待交）；`Dispatcher` 另起去交 | 模块 `accept` 抛错、进程被杀都不丢：交接在库里，下次接着交。管家不用等模块干完 |
| **Plugin / Registry（开闭原则）** | 去处由 `Module.notice` 交上来，`Intake.install` 收齐 | 加一个模块不改 intake、不改管家的提示词（去处清单自动多一行） |
| **Adapter** | `LedgerNoticeTarget` 把交接接到 `LedgerStore.ingest`；`ReminderNoticeTarget` 接到提醒子代理 | 模块原来的入口不用改形状 |
| **Facade** | `Intake` 这个 object（现在就是） | 监听服务、页面、模块只认一个门面 |
| **Strategy（切换开关）** | `NoticeRouting`：`Legacy`（现在的按包名分）/ `Hub`（新链路），只在 debug 包能切 | 新链路能在 debug 包上真机跑、随时切回去对比；切换那一版删掉 `Legacy` 一个类就干净 |

**不用的**（和 agent runtime 重构时一个路数）：不引入 Hilt / Koin，沿用 `install()` + 单例；不上事件总线库，Flow 够了；不做通用规则引擎、不做规则 DSL —— 硬条件就那几种，写成类比写成语言清楚；不做反射加载插件。

---

## 4 核心接口（草图，以写出来的为准）

### 4.1 整理格式

```kotlin
/** 整理过格式的一条：比硬条件、给管家看的都是它。原样的那条在 [raw] 里，存收件箱用 */
data class Normalized(val raw: Notice, val title: String?, val body: String?)

/** 一种 app 的通知长什么样：剥掉「(3条)」「[3条]」、群聊里的「名字: 」。按包名挑（[Formats]） */
fun interface NoticeFormat {
    fun normalize(n: Notice): Normalized
}
```

`ChatFormat` 就是现在 `Relay.cleanTitle` / `cleanText` 那几行抄过来（`Relay` 第 8 步才删，这之前两份并存）；`PlainFormat` 只去首尾空白。和 `Relay` 一样，正文开头只剥**和标题同名**的「名字: 」—— 群聊里别人的「张三: 」不剥，那是谁说的。「含 / 不含」比的是正文连上展开后的那段（`Normalized.fullText`），暗号只看正文开头。

### 4.2 硬条件

```kotlin
/** 判一条：遮蔽版的正文判不了，是 [UNKNOWN]（先存、等重读，DESIGN §12.3） */
enum class Verdict { YES, NO, UNKNOWN }

sealed interface Condition {
    fun test(n: Normalized): Verdict
    /** 痕上、规则页上的人话：「含 取件码 / 驿站」 */
    fun describe(catalog: AppNames): String
}

data class AppIn(val pkgs: Set<String>) : Condition
data class TitleIs(val text: String, val exact: Boolean) : Condition       // 等于 / 包含
data class BodyHasAny(val words: List<String>) : Condition                 // 词之间是「或」
data class BodyHasNone(val words: List<String>) : Condition
data class BodyStartsWith(val code: String) : Condition                    // 暗号，不分大小写
data class AllOf(val parts: List<Condition>) : Condition                   // 且：有 NO 就 NO，有 UNKNOWN 就 UNKNOWN
```

```kotlin
data class Rule(
    val id: Long,
    val name: String,       // 「快递到了提醒取」
    val hard: AllOf,        // 至少有一个 AppIn
    val soft: String,       // 软规则，给管家看
    val enabled: Boolean,
    val createdAt: Long
)
```

`RuleDraft`（工具参数原样）→ `RuleCheck.validate(draft, installed)` → `Rule` 或一串不过的理由（包名都装着、软规则不空、词至少 2 个字、总数 ≤ 30）。

### 4.3 收的流水线

```kotlin
/** 流水线上的一条：走到哪一段，带着什么。lastLook = 监听层 2 分钟那次重读；matched 里遮蔽版判不了正文的也先算中 */
data class Catch(val n: Normalized, val lastLook: Boolean = false, val matched: Set<Long> = emptySet(), val stored: Stored? = null)

/** 一段。返回 null = 到此为止（没中、重复） */
fun interface Stage {
    suspend fun handle(c: Catch): Catch?
}
```

`Intake.receive(notice)` 跑完四段才返回：扫通知栏要等所有通知都过完（抓取页报数、整理前的扫描都靠这个）。

- `Match`：没有规则听这个 app 的到此为止、不碰库（绝大多数通知）；有规则听、一条都没中的**照样往下走** —— 可能有它的遮蔽版在收件箱里等着改判。
- `Store`：遮蔽版一条都没中的不碰库；别的交 `InboxRepository.receive`。
- `Wake`：只有**新插的**遮蔽版叫重读，重读扫回来的重复不再叫。

### 4.4 收件箱和交接

```kotlin
// 转移表写在 InboxState.next 里：WAITING→PENDING→WORKING→DONE|PENDING|FAILED，DONE、FAILED→PENDING
enum class InboxState { WAITING, PENDING, WORKING, DONE, FAILED }

interface InboxRepository {
    suspend fun receive(n: Normalized, matched: Set<Long>, lastLook: Boolean, at: Long): Stored?  // 照 Landing.of 落
    fun pendingCount(): Flow<Int>
    suspend fun claim(limit: Int, now: Long): List<InboxNotice>   // 先放出等太久的遮蔽版、上回没落库的 WORKING
    suspend fun settle(s: Settlement, at: Long)                   // 一个事务：决定 + handoffs + 状态和次数
    suspend fun purgeDone(before: Long)                           // 只清 DONE，30 天
}
```

- **`WAITING`（第 1 步加的）**：遮蔽版等真正文，不交管家。真正文到了、2 分钟那次重读还是遮蔽、等了超过 10 分钟（重读排在内存里，进程没了就没人叫）都转 `PENDING`。
- **`Landing`（第 1 步加的）**：一条到门口怎么落是个纯判断 —— 新插 / 补记新规则 / 遮蔽版换成真正文 / 遮蔽版对不上删掉 / 跳过。Room 仓库只照着写库，去重和改判的边角在核心里测。遮蔽版和真正文指纹不一样（正文不同），所以仓库先按指纹找、找不到再按「包名 + key + postTime、还在等的」找。
- 回执（`report`）第 3 步跟插口一起加。

```kotlin
/** 管家的一个决定（Command）。[rules]：这个决定对的是这条通知中的哪几条规则 */
sealed interface Decision {
    val noticeId: Long
    val rules: Set<Long>
    data class HandOff(override val noticeId: Long, override val rules: Set<Long>, val to: String, val note: String) : Decision
    data class Ignore(override val noticeId: Long, override val rules: Set<Long>, val why: String) : Decision
}
```

`DecisionBuffer` 收工具交来的决定：去处不认识、通知不在这一批、规则不是这条中的、同一条规则又交又说不相关 —— 当场退回给模型（工具输出写清楚哪里不对）。`rules` 空着 = 这条还没说法的全部。同一条交给同一个去处两次，规则并起来、附言后写的算。`remaining()` 给工具每次念「还差哪几条」。一轮跑完 `settle()` 算覆盖：都有说法的办完，没覆盖全的回待办、次数 +1，第 3 次标没办成；已经有说法的那几条照样落库。**整批跑失败（`Settlement.failed`）次数不加**，见第 10 节第 7 条。

### 4.5 模块插口

```kotlin
/** 模块接管家交来的活。在模块的 Module 上交出去（Module.notice），intake 不 import 模块 */
interface NoticeTarget {
    val id: String        // 「ledger」「reminder」：交接表里存它，存在手机上，不改
    val label: String     // 「记账」
    /** 给管家的一句：接什么、附言写什么。跟着去处走（工具说明跟着工具走，DESIGN §6.3 第 6 条） */
    val brief: String
    /** 管家交来的几条。可能交不止一次（重试），按 [Handed.handoffId] 去重 */
    suspend fun accept(handed: List<Handed>): List<Receipt>
}

data class Handed(
    val handoffId: Long, val noticeId: Long, val pkg: String, val app: String, val at: Long,
    val title: String?, val body: String?, val extra: String?, val extras: String, val redacted: Boolean,
    val note: String       // 管家附言：「18:00 提醒取快递，标题带 3-2-1088」
)

/** 当场的回执；后来才办完的（记账整理完）用 NoticeReports.report 补 */
data class Receipt(val handoffId: Long, val text: String, val route: String? = null)
```

- **记账**：`accept` 把原文写进 `raw_notification`（新列 `noticeId`，指纹照旧去重 —— 重试天然幂等），回执「待整理」；整理员写完那一笔时按 `noticeId` 回写「午饭 58.00」/「不是账」。
- **提醒**：`accept` 一条一条跑提醒子代理（锁住，同现在 `Relay` 的 `Mutex`），建成后回执带编辑页路由，照旧发「建了一条提醒」的通知（渠道 id 还是 `relay_v1`）。重试防重复：先查收件箱里这个 `handoffId` 有没有回执，有就跳过。进程在「建好提醒」和「写回执」之间被杀，会多建一条 —— 接受，记在第 10 节。

**写出来和上面草图不一样的**（第 3 步）：
- `accept(context, handed)` 带 `Context`（同 `NoticeSubscriber.accept`）。
- `Handed` 直接带原样的 `Notice`（记账要存原文、算指纹要 key 和 postTime），另带 app 的人话名字、整理过的标题正文、附言。
- **没回执 = 这次没交成**：模型没配、跑失败的不回执，`Dispatcher`（第 4 步）过一会儿再交、记次数。第 3 次还交不出时要不要通知你（以前派活带暗号的没接住会弹一条），第 4 步定。
- `NoticeReports` 三样：`report(handoffId)`、`receiptOf(handoffId)`、`reportNotice(noticeId, target)`（记账整理完时只知道原文上的 noticeId，补在交给记账的最近一次交接上）。提醒建成了马上 `report`，不等 `Dispatcher`。
- 记账的回写：`LedgerStore.record` 落完账、事务提交后回写「午饭 58.00」「收入 · …」（金额照 `Money.signed`）、「不是账：…」「看不清：…」；回写失败不影响账。早就收过的那条（以前 `LedgerCapture` 收的）又被交来：照指纹找到，补上 `noticeId`，回执「已经收过」。
- 提醒子代理落库时 note 后面加「来自老婆（微信）」，同派活的「老婆派的」；`gentle` 照附言（软规则里明说了轻提醒，管家才会写进附言），不再一律压成 false。
- 通知渠道还用派活那个的名字「别人派的事」：两边现在都在建这个渠道，名字不一样会来回改。第 8 步删派活时再改名。

### 4.6 管家

- `StewardScheduler`：叫声（`poke`）→ `debounce(3_000)` → 跑一轮（管家 + 交接）。流水线落成待办、`IntakeModule.onAppStart` 叫它；一轮说「还有」（这一批满了、有没覆盖全的）就再叫自己一次 —— 没覆盖全的有次数上限，不会空转；**整批跑失败不再叫**，等下一次来了新的或打开 app。`collect` 一次只跑一轮，跑着时又叫的跑完再防抖一次。
- `Steward.runOnce`：没配模型就不领（待办留着）→ 领一批 → 中了的规则后来停了、删了的，代码直接标「规则停了」（管家看不到它的软规则）→ 剩下的交模型 → `settle` 一个事务。跑模型那一下可以换（`ask`），单测换成照剧本回话的假网关，走的是真的 `AgentRuntime`（strict）。
- `StewardInput`：去处清单 → 启用中的规则这两段垫在背景里，**排在记忆后面**（`MemoryPreamble.of(memories, null, listOf(standing))`，和对话页垫模块现状一样）；这一批是输入。两段都只在规则、模块、记忆变了时变，前缀照样稳。
- `AgentRuntime.runOnce`，不挂顶栏（界面上不露面，DESIGN §12.4），debug 包 trace 写 `files/trace_steward.txt`（runtime 按 agent id 写，不用另加）。
- 每批最多 20 条，一轮最多 8 步（一步里能并着调好几个工具）。
- `Dispatcher`：去处 id → `Accept`（`suspend (List<Handed>) -> List<Receipt>`，装的时候拿 `NoticeTarget.accept` 绑上 Context），不碰 Android，JVM 能测。按去处分好交；抛错的整组、没回执的那条算没交成，第 3 次标交不出；没有这个去处（模块删了）的同样记一次没交成。交接重试也只在下一轮时：不另排闹钟。

### 4.7 助手的工具

`IntakePlugin`（`IntakeModule.assistant`）交六个工具；用法写进 `AssistantPrompt`（`assistant-v?` +1）。`add_rule` 的参数就是 `RuleDraft` 的样子：`name`、`apps[]`、`title {text, exact}?`、`body_any[]`、`body_none[]`、`starts_with?`、`soft`。痕（`trace`）照 `describe()` 写：「监听 · 短信、菜鸟（含 取件码 / 驿站）→ 快递到了 18:00 提醒我去取 · 通知栏里有 1 条会进来」。

---

## 5 库

**`intake.db`**（新，version 1，`exportSchema`）：

| 表 | 列 | 说明 |
|---|---|---|
| `rules` | id、name、hard（JSON，`RuleCodec`）、soft、enabled、createdAt、stoppedAt | 停了不删，设置页能撤销 |
| `notices` | id、pkg、notifKey、postTime、at、title、text、extra、extras、redacted、how、fingerprint（唯一）、normTitle、normBody、state、attempts、receivedAt、doneAt | 收件箱。receivedAt 是第一次存下的时刻，遮蔽版换成真正文也不变 |
| `notice_rules` | noticeId、ruleId（主键）、outcome（`HANDED` / `IGNORED` / null）、targets（「ledger,reminder」）、why、decidedAt | 一条中了哪几条规则，各自怎么定的。不和 `rules` 连外键：规则删了，记录还在 |
| `handoffs` | id、noticeId、target、note、state（`DUE` / `DELIVERED` / `FAILED`）、attempts、createdAt、error、receipt、route、reportedAt | 交接（outbox）+ 回执。同一条后来又交给同一个去处是新的一行 |

- 表、列、枚举的名字（`InboxState`、`HandoffState`、`RuleOutcome`、`RuleCodec` 的 `type`）都存在手机上，不改名。
- 没用外键：清 30 天前的时三张表在一个事务里一起删（`purgeDone`）。
- `RuleCodec` 读不懂的整条规则读成 null、不听（`RoomRuleRepository`），不跳过那一个条件 —— 少一个条件规则就变宽了。

**`ledger.db`** v1 → v2：`raw_notification` 加可空列 `noticeId`（auto migration，生成的是 `ALTER TABLE raw_notification ADD COLUMN noticeId INTEGER DEFAULT NULL`）。旧行是 NULL，不动。**这一条一合进 main 就跟着下一次发版走**，不等第 6 步的开关 —— 见第 8 节第 2 步。

`reminder.db`、`chat.db` 不动。

---

## 6 升级：旧配置不搬，旧数据不删

| 手机上的 | 怎么办 |
|---|---|
| DataStore `intake`（`apps.ledger`、`apps.relay`） | 不读、不删 |
| DataStore `relay`（名单、暗号） | 不读、不删 |
| `relay.db`（`relay_message`） | 不开、不删，文件留着 |
| `ledger.db` 的 `raw_notification`、流水 | 照常用；新列旧行是 NULL |
| 通知使用权 | 组件 `intake.NoticeListenerService` 不挪不改名，不用重开 |
| 通知渠道 `relay_v1` | **id 一直留着**，提醒子代理接着用（你对这个渠道的设置按 id 记）；显示名改成新的 |

**升上来那一刻什么都不听**，要在对话里重新说（同 09-30 那次「记账先一条都不收」）。所以发版说明里写清楚，第 10 节第 1 条另有一个小办法。

**留壳（像 `agent/memory/tidy/TidyWorker.kt`）：只留手机上按名字记着的，切换那一版之后再过两个版本删**

| 壳 | 为什么要留 | 留成什么 |
|---|---|---|
| 路由 `reminder/relay/{id}`、`reminder/relay` | 通知栏里还挂着的旧「派的事没接住」通知，PendingIntent 里存着这个路由；NavHost 找不到页会崩（DESIGN §2.2「路由」） | 一个空 composable，进来就跳规则页 |

纯代码不留壳：`Relay`、`RelaySettings`、`RelayDatabase`、`Router`、`NoticeSubscriber`、`AppPicker*`、`LedgerCapture` 在切换那一版直接删。删类不会删手机上的文件，留着只会让两套并存、被误用。`Legacy` 那条开关只在 debug 包里活到切换那一版。

---

## 7 不碰的、不做的

- `reminder/scheduling`、`reminder/data` 一行都不改（§05）。提醒子代理建提醒还走 `Reminders.commitPlan`。
- 记账整理员、护栏、对账、账本页不改，只是原文的来路换了（加一列）。
- 收通知的几样不改：扫通知栏的时机、遮蔽后重读、重连的办法（DESIGN §2.3「收」那几段）。
- 不做全局隐私闸（10-26 定的）；流水线留着插这一段的位置。
- 不做设置页里建规则、改规则。
- 不引入新的第三方库。

---

## 8 步骤

**1–5 步不接线、行为不变**，每步单独提交，`./gradlew :runtime:test :app:testDebugUnitTest` 过了就算完；第 6 步起在 debug 包上真机验；第 8 步才发版。

### 第 1 步：核心（纯 Kotlin）

- [x] `core/format`：`NoticeFormat`、`ChatFormat`（从 `Relay` 抄那几行正则）、`PlainFormat`、`Formats`
- [x] `core/rule`：`Condition` 那几种 + `AllOf`、`Verdict`、`Rule`、`RuleDraft`、`RuleCheck`
- [x] `core/pipeline`：`Stage`、`Catch`、四段（仓库用接口）
- [x] `core/inbox`：`InboxState` 转移表（加了 `WAITING`）、`Landing`
- [x] `core/steward`：`Decision`、`Settlement`、`DecisionBuffer`、`StewardInput`
- [x] 单测见第 9 节（`app/src/test/.../intake/core/`，40 个）；`ArchitectureTest` 加了 `intake/core` 那条和「不碰 Android」

### 第 2 步：库

- [x] `intake.db`：四张表、Dao、`RuleCodec`、两个 Room 仓库（交接的那个第 4 步跟 `Dispatcher` 写）、`purgeDone`
- [x] `ledger.db` v2：`raw_notification.noticeId`
- [x] schemas 导出进库（`app/schemas/…IntakeDatabase/1.json`、`…LedgerDatabase/2.json`）
- [x] `RuleCodecTest`：每种条件往返、存的样子不漂、读不懂的整条是 null
- **没测到的**：两个 Room 仓库本身（JVM 起不了 Room，不为它加 Robolectric）。怎么落、怎么算在核心里测过，仓库只照着写库；测试里的 `FakeInbox` 和 `RoomInboxRepository` 是同一套写法，改一边要改另一边。真库第 6 步在 debug 包上拉 `intake.db` 看
- **ledger.db v2 迁移没在真机上验过**：照 CLAUDE.md「演一遍库迁移」，或者下次覆盖装 debug 包时打开一次记账页再拉库，`PRAGMA user_version` 是 2、`raw_notification` 多了 `noticeId`、旧行都在

### 第 3 步：插口 + 两个子代理

- [x] `intake/plugin`：`NoticeTarget`、`Handed`、`Receipt`、`NoticeReports`；`Module.notice`（提醒、记账都交了）
- [x] `LedgerNoticeTarget` + `LedgerStore.ingest(notice, noticeId)` + 整理员写完回写
- [x] `ReminderNoticeTarget` + `ReminderNoticeAgent` + `ReminderNoticePrompt`（从 `RelayPrompt` 改写：输入是「通知 + 附言」，「我 / 你」那几条写成「通知是某个人发来的话时」；版本 `reminder-notice-v1`）
- [x] 花名册（现在六个里的五个：派活第 8 步才删，管家第 4 步加）、`AgentsTest`、`ArchitectureTest`（`module` 和模块能碰 `intake/plugin`）
- **没测到的**：两个 `accept` 和记账回写都要碰库、跑模型，JVM 测不了，第 6 步真机验；提示词只测了输入怎么拼，模型照不照附言建，第 4 步 `LiveGatewayTest` 那组一起打真网关

### 第 4 步：管家

- [x] `StewardAgent`（id `steward`）、`StewardPrompt`（`steward-v1`）、`HandOffTool`、`IgnoreTool`（`StewardTools.kt`）
- [x] `Steward`、`StewardScheduler`、`Dispatcher`；`HandoffRepository` 和 `RoomHandoffRepository`；`Wake` 加了叫调度那一下
- [x] 花名册六个（加了管家）、`ArchitectureTest`（`intake` 除 `core/` 外能用 `runtime`）
- [x] 单测 `StewardRunTest`：落库、错了退回再改、没覆盖全回待办、整批失败不算次数、没配模型不领、规则停了不叫模型、交接三种结果、交不出、防抖合并
- [x] `LiveGatewayTest` 加了一条（`steward follows the soft rules…`）：四条通知三条规则打真网关，看交给谁、附言带没带 18:00、广告标没标不相关，再拿附言喂提醒子代理看建出来是不是 18:00。**10-27 在火山方舟 `deepseek-v4.1-flash`（Responses）上过了**，约 6 秒：快递 → 提醒「18:00 提醒取快递，标题带取件码 3-2-1088（幸福里驿站）」，寄件优惠 → 不相关「寄件优惠广告」，招行 → 记账（附言空），老婆带暗号 → 提醒；子代理建了 18:00「取快递 3-2-1088（幸福里驿站）」，不是轻提醒。没跑 `DAODIAN_API=chat`
- **第 3 次交不出要不要通知你**：先不通知，记录页（第 7 步）上看得到；以前派活带暗号的没接住会弹一条。第 10 节第 8 条

### 第 5 步：助手建规则

- [x] `IntakePlugin` + 六个工具（`intake/assistant/IntakeTools`）+ 痕（`IntakeTrace`）；`IntakeModule.assistant`
- [x] `AssistantPrompt`：什么时候建规则、拿不准就问（时间说法、哪几个 app、哪类算、交给谁、短信要不要加「不含 验证码」）
- [x] 单测 `IntakeToolsTest`（假的 `RuleDesk`）、`AgentsTest` 多一条（带规则工具时用 `assistant-v2`，strict 过）
- [x] `LiveGatewayTest` 多一条（`the assistant builds a rule from a clear request`）：**10-27 在火山方舟上过了** —— 话说全了它直接 `list_apps` → `preview_rule` → `add_rule`，软规则写了 18:00、响铃、标题带取件码

**写出来和计划不一样的**（第 5 步）：
- **建规则的工具现在只进 debug 包**（`IntakeModule.assistant` 看 `BuildConfig.DEBUG`）：新链路第 6 步才接线、第 8 步才进 release，release 包上的助手先不认得规则，免得建了不响。所以 `AssistantPrompt` 暂时有两份：`assistant-v1`（release，原样）和 `assistant-v2`（v1 + 「听通知」一段 + 六个工具），`AssistantAgent` 看手上有没有 `add_rule` 挑。第 8 步只留 v2。
- 工具要的东西走一个接口 `RuleDesk`（真机上是 `IntakeDesk`：`AppCatalog`、通知栏、`intake.db`），测试换成假的。
- 规则仓库全 app 一份（`intake/data/IntakeRepos`）：它缓存启用着的规则，流水线和工具得用同一份，不然这边建了那边还拿旧缓存。第 6 步接线也从这里拿。
- `recent_senders` = 内存里记的（`intake/RecentTitles`，第 6 步接线后流水线每条记一笔）+ 通知栏里现在挂着的（`Intake.active()`，只读不交）。`preview_rule` 对的是通知栏里现在的 + 收件箱里 7 天内的。
- 建好规则后**还不会当场扫一遍通知栏**（以前勾上 app 当场扫）：流水线第 6 步才接，到时候 `add_rule` 之后补一下 `Intake.sweep`。
- 痕点不进去：规则页第 7 步才有，到时候加路由。

### 第 6 步：接线 + 开关（debug 包真机验）

- [x] `NoticeRouting`：`LEGACY` / `HUB`；`NoticeListenerService` 改调 `Intake.receive`
- [x] debug 设置里一个开关（默认 `LEGACY`），切到 `HUB` 时老的订阅者不收（不会两边各建一条提醒）
- [ ] 真机验：对话里建三条（招行 → 记账、快递 → 提醒、老婆 + 暗号 → 提醒）；拉 `intake.db` 看 `rules`、`notices`、`handoffs`；`trace_steward.txt`；记账「抓到的通知」里有交过去的那条、整理完回写；快递短信建出提醒；没带暗号的不进库；取件码短信被遮蔽时等到真正文（DESIGN §12.8 第 1 条）；杀进程后待办接着办

**写出来的样子**（第 6 步）：
- 开关在「权限与监听」页最底下一组「走哪条路（debug）」，存在 DataStore `notice_routing`（不碰旧的 `intake`）；release 包读都不读，永远老链路。
- `Intake.receive` 是唯一的入口（实时、扫一遍、重读都走它）：两条路都给 `RecentTitles` 记标题；新链路过 `NoticeHub` 的流水线，老链路照旧 `dispatch`。
- `NoticeHub`（`intake/`）：全 app 一份流水线 + `StewardScheduler`，管家跑完一轮接着 `Dispatcher` 交一轮；去处清单由 `DaodianApp` 从 `MODULES.mapNotNull { it.notice }` 交进来。冷启动和切到新链路时 `purgeDone` + 叫一下管家（杀进程后的待办、没交成的交接、等过 10 分钟的遮蔽版）。
- 遮蔽版重读：老链路照旧隔 5s / 30s / 2min 重扫整个通知栏；新链路只在流水线新存下 `WAITING` 时单读这一条（`getActiveNotifications(keys)`），2 分钟那次带 `lastLook`。通知划掉了读不到，等 `claim` 那 10 分钟。
- `add_rule` 建好后 `RuleDesk.sweep()`：新链路开着时扫一遍通知栏（`how = rule`）。
- **10-27 真机（debug 包，火山方舟）验过的**：对话里建「短信取件」规则（`com.hihonor.mms`、含 取件码 / 驿站、不含 验证码，软规则写了 18:00）。通知用 `adb shell cmd notification post` 从 `com.android.shell` 发，对应的三条测试规则直接写进库：快递 → 提醒子代理建了 18:00「取快递（幸福里驿站 3-2-1088）」、闹钟排上、不是轻提醒；老婆 + 「到点」→ 20:00「买酱油」；老婆不带暗号 → 不进库；招商银行 → 记账「待整理」，`raw_notification.noticeId` 对得上；进程在 3 秒防抖里被杀，重开后接着办完。`ledger.db` 是 v2、有 `noticeId`（debug 包里本来就没有旧原文，「旧行都在」没得验）。
- **真机上发现、已修的**：冷启动叫管家那一下丢了（`StewardScheduler` 用的 SharedFlow，收的那头还没起来就叫，没人收就扔），杀进程后的待办要等下一条通知才办。改成 `Channel.CONFLATED`，加了单测。
- **真机上发现、没修的**：
  - **同一条通知重发一遍，会再建一条提醒**：指纹带 `postTime`，app 把同样的字重发（同一个 key、新的 postTime）就算新的一条。`cmd notification post` 同一个 tag 再发一次就是这样，真 app 会不会这么干没核过。第 10 节第 2 条（同一件事连发几条）是一回事，一起定。
  - **验证码短信被系统遮蔽**（Android 15 认出验证码就遮，标题也成了「Shell」）：等了 2 分钟真正文都没来，照遮蔽版交，管家标「看不清」不相关。这条本来就该不进，结果对；但说明**遮蔽的不一定等得到真正文**，取件码短信要是也被认成验证码，就只能「看不清」。真短信上再看。
  - 记账整理完回写回执、真短信、锁屏下遮蔽的取件码短信没验。
- **没做的**：交接没交成的（模块抛错、子代理没跑成）不会自己再叫，要等下一条通知进来、或下次冷启动；等 10 分钟的遮蔽版也一样。真机上看是不是要加个定时。

### 第 7 步：界面（先出设计稿）

- [x] 设计稿（<https://claude.ai/artifact/Qg7TytQpTJd5N4CtuacTB8>，10-27 用户选了方向 B，**固定的说明字少写**）：「权限与监听」→「规则」/「收到的通知」两个入口；规则页分在听 / 停了；一条规则 = 在听开关、听什么、进来之后、最近进来的、删掉（退回规则页给 5 秒撤销）；收到的通知按天分组，底下写变成了什么，没办成的带「再试」；一条通知 = 全文、中了的规则、办成了什么（点进那条提醒）
- [x] 代码：`intake/presentation/InboxScreens.kt`、`InboxViewModel.kt`（四页共用，读 `intake.db` 的 Flow）；路由 `intake/rules`、`intake/rule/{id}`、`intake/notices`、`intake/notice/{id}`；`RuleRepository.restore`（撤销删除，编号不变）；Dao 加了记录页的三个 Flow 和两个「再试」
- [x] 「权限与监听」页：**新链路开着时**「谁在听」「按 app 看」换成两个入口、组名改「听通知要的」、去掉使用权和扫一遍的说明；老链路原样（release 第 8 步才切）
- 提醒设置里派活那一组：挪到第 8 步删了
- **10-27 用户在真机（debug 包）上逐页看过，说都对**：入口两行、规则页停 / 开 / 删 + 撤销、一条规则、收到的通知、一条通知、开关关掉变回老样子

### 第 8 步：切换发版

- [x] 只剩新链路（10-27，没发版）：删了 `NoticeRouting` 开关、`Relay*`（含 `reminder/presentation/relay`、`relay` agent、`RelayDatabase` 的 schema）、`NoticeSubscriber`、`Router`、`IntakeSettings`、`AppPicker*`、`LedgerCapture`；「权限与监听」只剩新的几组，提醒设置里派活那一组去掉；助手的提示词并成一份 `assistant-v2`，建规则的工具 release 也有；记账页「一个 app 都没勾」那句去掉
- [x] 留路由壳：`reminder/relay/{id}`、`reminder/relay` 进来先退掉自己、再开 `intake/rules`（`ReminderShell.RelayShim`）
- [x] 导出诊断：规则几条、各听哪些包名；近 7 天进来各状态几条、不相关几条、交给谁各几次（`IntakeShell.diagnostics`）；提醒那一段的派活名单、派活收到的删了
- **一直留着、不是壳的**：渠道 id `relay_v1`（系统按 id 记你对它的设置，显示名改成「通知建的提醒」）；监听组件 `intake.NoticeListenerService` 的全类名（通知使用权按它授）
- **手机上留着没删的文件**（照第 6 节「旧数据不删」）：`relay.db`、DataStore `intake`、`relay`，debug 包还有 `notice_routing`、`files/trace_relay.txt`。代码不再碰；要清就在 0.2.2 那一版里一起删，待定（`docs/cleanup.md`）
- **升上来记账先一条都收不到**：以前给记账勾的 app 不搬，要在对话里说一次「招行、支付宝的通知记账」。第 10 节第 1 条定了：新功能弹窗那一页说（DESIGN §11.4）
- [ ] DESIGN：§2.3 删「现状」那段，§2.2、§6.1 花名册、§08 设置页、§10.2、§12 跟着改；CLAUDE.md
- [ ] 发版说明写「升级后在对话里重新说一次要听什么」

### 第 9 步：删壳（切换那一版之后第二个版本）

- [ ] 删 `reminder/relay` 路由的壳：定在 0.2.2（0.2.0 是切换那一版），清单在 `docs/cleanup.md`，和 `TidyWorker`、速记的 alias 一起

---

## 9 测试

| 测什么 | 怎么测 |
|---|---|
| 整理格式 | 微信标题「老婆(3条)」、正文「[3条]老婆: 晚上带瓶酱油」、群聊前缀、短信原样 |
| 硬条件 | 且 / 或；暗号开头不分大小写；遮蔽版正文条件是 `UNKNOWN`、只有包名标题条件的照样 `YES`；`describe()` 的人话 |
| `RuleCheck` | 没装的包名、空软规则、1 个字的词、超过 30 条都退回，理由写清楚 |
| 流水线 | 内存假仓库（照 `Landing` 落，`Fakes.kt`）：没中不存；中了存；同指纹只存一次；已存的又中了新规则只补新的；遮蔽版先存、真正文到了按全部条件改判（对不上删）；最后一次还是遮蔽就交 |
| `Landing` | 每种「已有的那一行」× 来的是不是遮蔽版 / 最后一次 |
| 状态机 | 合法转移都过、非法的抛 |
| `DecisionBuffer` | 不认识的去处、批外的通知、不是它中的规则都退回；覆盖全的办完，没覆盖全的回待办、第 3 次标没办成 |
| `StewardInput` | 规则、模块不变时前两段逐字节一样 |
| `Dispatcher` | `accept` 抛错重试 3 次后标交不出；同一 `handoffId` 交两次，子代理只办一次 |
| 依赖、花名册 | `ArchitectureTest` 新表；`AgentsTest` 五个 agent、工具对得上提示词 |
| 真网关 | `LiveGatewayTest` 管家那组（第 4 步） |
| 真机 | 第 6 步那张单子 |

---

## 10 待定

1. **升上来什么都不听，要不要提醒一句**：**10-29 定了：不在对话里垫，用新功能弹窗那一页说（DESIGN §11.4）。** 原来的想法：倾向第一次打开新版时，在对话里垫一句「通知改成在对话里说了，比如『帮我监听招商银行，记账用』」。只看旧 DataStore 里有没有东西决定说不说，不搬内容。
2. **同一件事连发几条**（DESIGN §12.8 第 3 条）：管家输入里带这条规则最近交出去的几条，先定 24 小时内 5 条。
3. **一次性规则谁来停**（§12.8 第 4 条）。
4. **附言给不给整理员**（§12.8 第 5 条）。
5. **提醒子代理重复建**：进程在「建好」和「写回执」之间被杀会多一条。要根治得在 `reminder.db` 记来源，碰了不该碰的库；先接受。
6. **收件箱里的原文要不要在记录页上全文可看**：隐私上和「抓到的通知」一样（只在本机），倾向能看。
7. **整批跑失败算不算一次**（第 1 步先定成不算）：断网时算的话，几条通知很快就被耗成「没办成」，账就断了。代价是万一有一条每次都让网关报错（内容审核），它会一直拖着同一批。真碰上再改：比如失败后下一批只领一条。
8. **交接第 3 次交不出时通知不通知**（第 4 步先定成不通知）：提醒子代理连着三次没跑成（断网、模型挂了），这件事就没建成，你不看记录页不会知道。倾向通知一条「有 N 件事没接住」，点开是记录页 —— 等第 7 步有了记录页再加。
