# 通知监听层（intake）开工清单

把「听通知」从记账里拆出来，做成记账、派活都能订阅的一层。做完、真机验过就删掉这份，要看用 `git show`。

---

## 一、定下来的

**分层**：`intake` 放在顶层，和 `agent`、`reminder`、`ledger`、`shared` 并排，但在模块下面一层。

```
agent            只认接头（Feature / FeatureUi）
  ↑ 接头
reminder    ledger     订阅 → 存进自己的库 → 读懂、生成数据
    ↓ 订阅    ↓
intake                 授权、连接、抓、按包名分发。一条都不存、不读懂
    ↓
shared                 地基：没有状态
```

**依赖规则**（写进 DESIGN §2.2）：

1. `intake` 的核心只 import `shared`。只有接头文件 `IntakeFeature.kt` 和 `presentation/` 能碰 `agent/shell`，核心**永远**不 import `agent`，以后要叫模型的也做成订阅者。
2. `reminder`、`ledger` 可以 import `intake` 的核心（`Notice`、`NoticeSubscriber`、`Intake`），不 import 它的页面，跳过去只用路由。
3. `intake` 不认识任何订阅者，订阅者在根目录装进来。
4. 规则 3「模块核心层不 import agent」的白名单加上 `reminder/relay/`（派活 agent 在里面，同记账的 `organize/`）。

**监听层管什么、不管什么**：

| | 监听层 | 订阅者（模块） |
|---|---|---|
| 通知使用权（按组件查）、连没连着、重连、冷启动催绑 | ✓ | |
| 实时收 + 连上、解锁、手动扫、整理前扫 | ✓ | 可以喊它扫 |
| 正文被遮蔽，5s / 30s / 2min 重读再投 | ✓ 标上 `redacted` | 自己定遮蔽版怎么算 |
| 每个订阅者听哪些 app（路由表）、勾选页 | ✓ | 给名字、用途、红字、排前面的 |
| 听谁、暗号、解析微信格式 | | ✓ |
| 存原文、去重（按指纹） | | ✓ |
| 读懂、生成数据 | | ✓ |

**两张表**：

- 谁有资格订阅：写在根目录 `Features.kt` 的 `SUBSCRIBERS`。
- 每个订阅者听哪些 app：你勾出来的，存在 intake 自己的 DataStore，内存里留一份 `pkg → 订阅者`。

**分发的规矩**：

- 每个订阅者分开调，一个抛错、一个慢，都不影响别的。
- 同一条通知可能投不止一次，订阅者按指纹去重。
- `accept` 只做落库这种快事，叫模型之类的重活订阅者自己另起。
- 扫通知栏要等所有订阅者的 `accept` 都返回才算完（抓取页要报「新存几条」）。

**勾选页**：

- 按模块勾：各模块设置里一行「听哪些 app」，跳 `intake/apps/{id}`，一个通用页。
- 按 app 看：设置 →「通知监听」页里列出「微信 → 派活」「招商银行 → 记账」，点一下跳到对应的勾选页。

**其他**：

- 不兼容：服务改名、勾选不迁移，**卸载重装**，重新开一次通知使用权。
- 通知使用权**不进体检结论**，理由不变：它挂了不影响提醒响（DESIGN §9.1）。它在设置页「通知监听」那一组。

---

## 二、新目录

```
intake/
  IntakeFeature.kt          接头：设置组「通知监听」、页面路由、冷启动催绑。prompt 空、没有工具
  IntakeRoutes.kt           intake/status、intake/apps/{id}
  Notice.kt                 一条通知，纯数据
  NoticeSubscriber.kt       订阅接口
  Intake.kt                 注册表、路由表、分发、状态（授权、连接、重连、扫一遍）
  Router.kt                 纯 Kotlin 的分发逻辑（pkg → 订阅者、隔离），单元测试测它
  NoticeListenerService.kt  系统服务（原 ledger/capture/PaySampler）
  data/IntakeSettings.kt    DataStore「intake」：每个订阅者一个 `apps.<id>`
  presentation/
    AppPickerScreen.kt      通用勾 app 页（原 ListenAppsScreen）
    AppPickerViewModel.kt
    IntakeStatusScreen.kt   使用权、连着 / 重连、扫一遍、谁在听哪些 app
    IntakeSettingsSection.kt
```

订阅者：

- `ledger/capture/LedgerCapture.kt`：`id = "ledger"`，`accept` 调 `LedgerStore.ingest`。
- `reminder/relay/Relay.kt`：`id = "relay"`，`accept` 记下她说的、按暗号决定交不交模型。

---

## 三、接口草稿

```kotlin
data class Notice(
    val pkg: String,
    val key: String,
    val postTime: Long,        // 系统收到的时刻
    val at: Long,              // app 自己写的时刻（Notification.when），聊天软件是消息的时刻；没写就是 postTime
    val title: String?,
    val text: String?,
    val extra: String?,        // bigText、subText、消息样式里和正文不重复的
    val extras: String,        // extras 全量 JSON，记账存原文用
    val redacted: Boolean,
    val how: String            // posted / active / unlock / organize / retry+5s / tap …
)

interface NoticeSubscriber {
    val id: String                                 // 存勾选、路由都用它
    val label: String                              // 「记账」「派活」
    val purpose: String                            // 勾选页顶上：勾上的通知拿去干什么
    fun warn(pkg: String): String? = null          // 某个 app 底下的红字
    fun suggested(pkg: String): Boolean = false    // 排在前面的
    suspend fun accept(context: Context, notice: Notice)
}

object Intake {
    fun install(subscribers: List<NoticeSubscriber>)
    val subscribers: List<NoticeSubscriber>

    fun appsFlow(context: Context, id: String): Flow<Set<String>>
    suspend fun setApp(context: Context, id: String, pkg: String, on: Boolean)   // 勾上当场扫一遍

    fun granted(context: Context): Boolean          // 按组件查，不按包名
    fun grantIntent(context: Context): Intent
    val listener: StateFlow<Listener>               // connected、since
    suspend fun sweep(how: String): Sweep?          // null = 没连着；Sweep(active, routed: Map<id, Int>)
    suspend fun reconnect(context: Context, timeoutMillis: Long = 6000): Boolean
    fun rebind(context: Context)
}
```

---

## 四、扫出来的依赖

### 清单

| 位置 | 现在 | 改成 |
|---|---|---|
| `AndroidManifest.xml` `<service>` | `.ledger.capture.PaySampler`，label「到点 · 支付通知采样」 | `.intake.NoticeListenerService`，label「到点 · 通知监听」，注释改 |
| `AndroidManifest.xml` `<queries>` | MAIN / LAUNCHER | 不动（勾选页要列 app） |

### shared

| 文件 | 改成 |
|---|---|
| `shared/notify/NoticeHub.kt` | **删**，`Intake` 取代 |
| `shared/apps/AppCatalog.kt` | 留。加 `isChat(context, pkg)`（微信、QQ、TIM、钉钉、飞书、默认短信），删掉 `ListenAppsViewModel`、`RelayViewModel` 里各一份的 CHAT 表 |

### ledger

| 文件 | 现在 | 改成 |
|---|---|---|
| `capture/PaySampler.kt` | 系统服务 + 入库 + 遮蔽重读 + 往 NoticeHub 分 | **删**。服务、遮蔽重读挪进 `intake/NoticeListenerService`；入库变成 `capture/LedgerCapture.kt` |
| `capture/PaySources.kt` | `listened`、`REDACTED`、`ACTION_SWEEP`、`granted`、`grantIntent`、`sweep`、`rebind`、`listener`、`sweepNow`、`reconnect` | **删**，全部挪进 `Intake`；`listened` 由路由表取代 |
| `data/LedgerSettings.kt` | `LISTEN`、`listenFlow`、`listen`、`setListen` | 删这四样 |
| `data/LedgerStore.kt` `ingest` | 自己用 `PaySources.REDACTED` 判遮蔽 | 改成收 `Notice`，遮蔽看 `notice.redacted` |
| `organize/Organizer.kt:96` | `PaySources.sweep(context)` + `delay(1500)` 干等 | `Intake.sweep("organize")`，扫完才返回，不用干等 |
| `LedgerFeature.kt` | `onAppStart` 里 `PaySources.rebind`；路由注册 `APPS → ListenAppsScreen`；设置组 `onOpenApps` | 去掉 rebind（intake 自己催）；删 `APPS` 路由；`onOpenApps` → `IntakeRoutes.apps("ledger")` |
| `LedgerRoutes.kt` | `APPS = "ledger/apps"` | 删 |
| `presentation/ListenAppsScreen.kt`、`ListenAppsViewModel.kt` | 记账专用的勾选页；`alsoCaptures` 标红字 | **挪**成 `intake/presentation/AppPicker*`，按订阅者 id 取；红字变成 `LedgerCapture.warn` |
| `presentation/LedgerSettingsSection.kt` | 第一行「通知使用权」；「听哪些 app」读 `LedgerSettings.listenFlow`；「抓到的通知」断线提示读 `PaySources.listener` | 「通知使用权」挪进 intake 的设置组；另两行改读 `Intake` |
| `presentation/CaptureViewModel.kt`、`CaptureScreen.kt` | 使用权、连接状态、重连、现在抓一下都走 `PaySources` | 使用权、重连两行换成一行状态，断了就点去 `intake/status`；「现在抓一下」调 `Intake.sweep("tap")`，取 `routed["ledger"]`；原文列表不动 |
| `presentation/LedgerScreens.kt:294` | `PaySources.granted` | `Intake.granted` |
| `presentation/LedgerViewModel.kt:313` | `listened` 读 `LedgerSettings.listenFlow` | 读 `Intake.appsFlow(app, "ledger")` |

### reminder

| 文件 | 现在 | 改成 |
|---|---|---|
| `relay/Relay.kt` | `NoticeHub.Sink`，自己读 `RelaySettings.apps` 过滤 | `NoticeSubscriber`（`id = "relay"`），不再过滤包名 |
| `relay/RelaySettings.kt` | `apps`、`who`、`code` | 删 `apps` |
| `presentation/relay/RelayViewModel.kt` | 自己列 app、`isChat`、按**包名**查使用权、`NoticeHub.sweepNow` | 删这几样，状态和扫一遍走 `Intake` |
| `presentation/relay/RelayScreen.kt` | 「通知监听」一组、「听哪个 app」一整组勾选 | 各换成一行：状态（断了点去 `intake/status`）、「听哪个 app：微信 ›」跳 `intake/apps/relay` |
| `ReminderFeature.kt:79` | `NoticeHub.register(Relay)` | 删，根目录装 |
| `presentation/ReminderViewModel.kt:44` | `RelaySettings.Values(emptySet(), "", "")` | 跟着去掉 `apps` |

### agent

| 文件 | 改成 |
|---|---|
| `conversation/ChatAgent.kt:36` | 拼 system 时跳过空 prompt。`IntakeFeature` 没有提示词，这样拼出来和现在**逐字节一样**，前缀缓存不失效 |

### 根目录

| 文件 | 改成 |
|---|---|
| `Features.kt` | `FEATURES` 末尾加 `IntakeFeature`（设置页里「通知监听」排在记账后面）；新增 `SUBSCRIBERS = listOf(LedgerCapture, Relay)` |
| `DaodianApp.kt` | `Intake.install(SUBSCRIBERS)` 放在 `FeatureRegistry.install` 前面 |

### 文档

| 文件 | 改什么 |
|---|---|
| `DESIGN.md` §2.2 | 分层图、加 intake 和四条规则、「三个库」那段不动（intake 不落库，只有一个 DataStore） |
| `DESIGN.md` §2.1 表（72 行） | 「采集」那一行指向 intake |
| `DESIGN.md` §9.1（677 行） | 通知使用权在「通知监听」那一组，不在记账那一组 |
| `DESIGN.md` §10.1（710–711 行） | 流程图 `PaySampler` → intake → `LedgerCapture` |
| `DESIGN.md` §10.2（760–792 行） | 「听哪些 app」「连接」「抓取页」的类名和路径；连接、遮蔽那几条挪进新写的 intake 一节，§10.2 只留记账自己的（给得出什么、合并、原文永不删） |
| `DESIGN.md` 新一节 | 通知监听层：本清单第一节的内容 |
| `README.md` 目录树 | 加 `intake/`；`ledger/capture/` 改成「订阅者：通知入库」；`shared/` 加 `apps/` |
| `CLAUDE.md` | 现状表「派活」「抓取页」两行；「怎么拿证据」里「抓到的通知」路径、加「通知监听」页；「踩过的坑」加一条：这版起要卸载重装、重新开通知使用权 |

### 测试

现在没有测试碰这些类。新增 `intake/RouterTest`：

- 包名只投给勾了它的订阅者，没人勾就谁都不投。
- 一个订阅者抛错，另一个照收。
- 路由表里有、代码里没注册的 id，直接忽略。

---

## 五、步骤（每步编得过）

- [x] 0. 试验版现在没提交。开工前先提交一次，方便对照。
- [x] 1. 建 `intake/` 核心：`Notice`、`NoticeSubscriber`、`Router`、`IntakeSettings`、`Intake`，加上 `RouterTest`。
- [x] 2. 服务搬家：`PaySampler` → `intake/NoticeListenerService`（遮蔽重读、解锁扫、连上扫一起搬），改清单。
- [x] 3. 记账接上：写 `LedgerCapture`，`ingest` 改收 `Notice`，`Organizer`、抓取页、设置组、总览换成 `Intake`；删 `PaySampler`、`PaySources`、`LedgerSettings` 的 LISTEN。
- [x] 4. 派活接上：`Relay` 改成订阅者，删 `RelaySettings.apps`，页面上两组换成两行；删 `NoticeHub`。
- [x] 5. 页面：勾选页挪进 intake、按订阅者 id；写「通知监听」页和设置组；`IntakeFeature` 接上；`ChatAgent` 跳过空 prompt；根目录装配。
- [x] 6. 扫一遍：`grep PaySampler|PaySources|NoticeHub|LedgerSettings.listen|ListenApps|RelaySettings.apps` 应该只剩文档。
- [x] 7. 文档：DESIGN、README、CLAUDE.md。
- [ ] 8. 装机，真机验收（下一节）。

**和上面定的不一样的两处**（做的时候改的）：

- 不用卸载：别的都没挪，只挪了监听组件。覆盖安装后重新开一次通知使用权、重新勾 app 就行，提醒、账、对话、模型配置都留着。
- 「按 app 看」那几行不能点：一个 app 两个模块都勾了的话，点了不知道该去谁的勾选页。要改勾选，点上面「谁在听」那几行。

---

## 六、真机验收

- [ ] 覆盖安装 → 设置 →「通知监听」→ 开使用权 → 回来显示「连着」。
- [ ] 系统设置里监听的名字是「到点 · 通知监听」。
- [ ] 记账勾支付宝、招商银行、掌上生活、建设银行 → 真付一笔 →「抓到的通知」里实时出现 → 手动整理照常进账。
- [ ] 派活勾微信 →「听谁」底下出现她的名字 →「试一句」建成提醒。
- [ ] 记账、派活**都勾微信** → 她发一句，`ledger.db` 的 `raw_notification` 和 `relay.db` 各有一条。
- [ ] 没勾的 app 一条都不进：拉 `ledger.db`、`relay.db`，按 `pkg` 数一数，只有勾了的。
- [ ] 「通知监听」页按 app 列出「微信 → 记账、派活」，点进去是对应的勾选页。
- [ ] 重连、扫一遍、解锁后补扫（`capturedHow` 里有 `unlock`）。
- [ ] 整理前的扫描不再干等 1.5 秒（`organize_trace.txt` 的时刻对得上）。
- [ ] 单元测试全过。

---

## 七、这次不做

- 派活转正：DESIGN 里写一节，§01「不做：分享、协作」那条要改。等她真在微信里用过一阵再说。
- 短信、「分享给到点」这类入口。短信 app 本来就会发通知，勾上「信息」就收得到。
- 通知使用权进体检结论。
