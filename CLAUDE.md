# 到点 (daodian)

用一句话建提醒的 Android app，个人自用、不上架。**先读 [DESIGN.md](DESIGN.md)** —— 那是权威的架构和决策记录，代码注释里的 `§5.3` `§9.2` 之类都指向它。这份文件是给 Claude Code 会话本身看的操作手册，两份不重复的内容互相补。

## 现状（2026-09，下面这行过时了就更新它）

- **包结构重排**（2026-09-23，分支 `restructure-modules`）：三个模块 `agent` / `reminder` / `ledger` + `shared` 地基，模块经 `Feature` / `FeatureUi` 一个接头接到 agent 上。结构、依赖规则、旧路径 → 新路径的对照表全在 [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)；**下面各条里的旧路径（`harness/`、`ui/`、`schedule/`、`data/`…）按那张表对照**。
  挪包改了全类名（通知使用权、小组件、闹钟、WorkManager 都按全类名记），所以**装新包前要连数据卸载**（`adb uninstall com.abc.daodian.debug`，会清空提醒、账本、对话、配置、权限）；提醒库改名 `reminder.db`、回到 v1。
  **只验过编译、37 条单测、打包**，真机一条都没验 —— 验收清单在 PROJECT_STRUCTURE.md「迁移顺序」最后一步。
- **M1 调度内核**：代码完成，真机冒烟测试通过（零漂移），**48 小时放置测试没跑过**——这是唯一还没拿到的硬证据。
- **M2 AI 解析**：用的是**工具调用**（`create_reminder`），不是让模型输出 JSON。真机测试成功：模型正确调用工具，字段名、时间推算都对。**2026-09-18 起整层换成了 `harness/` agent 循环**（现 `agent/engine/`，见下面 harness 那条），这一条及下面流式、「停」、思考开关几条里提到的 `ToolCallParser` 已删除，机制由 `agent/model/ResponsesClient` 继承。
- **M3 UI**：八块 Compose 屏幕（对话/卡片/到点全屏/列表/编辑/设置/日志）全部写完、编译通过、lint 干净。
- **视觉改版（墨宋）**：整套 UI 按新视觉稿重画完，规范见 DESIGN.md §8.1。真机确认过：空状态、对话、解析骨架、卡片、收起态、设置体检页（截图），到点全屏页（用户肉眼在锁屏上看到并点了「完成」，我没截到图）。
- **"喝水"全链路真机跑通了**（2026-09-04）：点例句 → 模型调 `create_reminder` → 落库排期 → `dumpsys alarm` 有闹钟 → 10:03:49.259 准点响，漂移 259ms，通知 `not_intercepted`。
- **闲聊被当成建提醒**（已修）：`TOOL_SYSTEM` 原来开头就把用户每句话都当建提醒请求，加上 `historyOf()` 把已建卡片整条丢掉、模型看到一串"没人应的请求"，于是每句话都弹卡。现在提示词先分流（闲聊/反问/建提醒），历史里补一句中文回执。真机复验："hello"→ 文字回复，"thanks"→"不客气！"，全程只排了一个闹钟。
- **流式输出**（2026-09-04）：`ToolCallParser.parseStream()` 走 `createStreaming()`，工具调用参数 / 正文分块逐字画出来，规范和三个坑见 DESIGN.md §6.7。非流式 `parse()` 留作回退（第三方网关不一定支持 SSE），回退时先发 `FellBack` 把半截字擦掉。
  **真机验过（截图为证）**：「remind me to buy milk tomorrow at 3pm」→ 屏幕上逐字出现
  `在建提醒 · create_reminder` + `{"title":"买牛奶","firstTriggerAt":"2026-09-05T15:00:00+08:00","basis":"用户当前时间 2026-09-` + 光标，
  同时正文「我会为明天（9月5日）下午3点创建一次"买牛奶"提醒。」也在逐字长；随后 `dumpsys alarm` 里
  `RTC_WAKEUP #106 origWhen=2026-09-05 15:00:00.000 exactAllowReason=policy_permission`。
  这个工具调用块是**流式独有的**，一次性路径画不出来 —— 所以它就是「真的在流」的判据。
  **那家供应商不发 `reasoning*` 事件**（普通模型，不是推理模型），所以思考块暂时看不到，界面在没有它时长得正常。
  还差一条没验：断网/坏 key 的回退。
  **「停」半天没反应**（2026-09-11 已修）：原来阻塞 HTTP 包在 `withContext(IO)` 里、close 挂在 `invokeOnCompletion` 上，Job 卡在阻塞调用里完成不了，回调永远来不及触发 —— 骨架阶段点「停」要等到首个 token。现在走 `detached`（今天在 `ResponsesClient`），见 DESIGN.md §6.7 坑 1。真机验过：骨架阶段点「停」，下一张截图（几百毫秒内）回合已经在淡出、原话已回到输入框，`dumpsys alarm` 没多出闹钟。
  另外这台 ROM 屏蔽第三方 logcat，`Log.i` 一行都看不到，别指望用日志判断流式有没有跑 —— 只能看界面。
- **对话动效改版「一句话，到一枚印」**（2026-09-11，**卡片部分已被下面「问卡与痕」取代**）：工具行就地长成卡片（在建提醒 → 虚线起稿 → 落印 → 收起），墨条洇染、正文逐字淡入、流式逐帧贴底、喊停后原话退回输入框；落印**不震动**（用户明确说聊天里震动很怪）。规则见 DESIGN.md 决策 6.2 / 6.3，时长曲线只从 `shared/theme/Motion.kt` 取，动效稿：<https://claude.ai/code/artifact/c0493995-43b7-4220-8a00-35adb5990804>。
  **真机连拍验过**：发送 → 墨条洇染 + 圆点呼吸 + 「停」；起稿态（虚线框、`在建提醒 create_reminder`、标题逐字、时间换成人话「9月16日 周三 15:00」）；落印态（印 + 已记下、依据、两个按钮，卡片**展开**着出来）→ `dumpsys alarm` `origWhen=2026-09-16 15:00:00`；点「就这样」变形成一行；只有正文的回合（模型没调工具）。
  **没在真机上验**：推理模型的思考块（这家供应商不发）、闸门拦下的「× 没记下」、流式回退擦字、点「停」退回原话、「↓ 新内容」。
  另：同一句中文「下周三下午三点，交房租。」模型这次只回了句「明白」没调工具，英文版才调了 —— 是模型/提示词的问题，不是界面。
- **桌面小组件**（2026-09-04 新增）：RemoteViews 实现，规范和三条改动规矩见 DESIGN.md §8.2。真机上 provider 已注册、深链验过（当时是 `am start --es com.abc.daodian.widget.TARGET new/list`；2026-09-23 起换成路由：`adb shell am start -n com.abc.daodian.debug/com.abc.daodian.MainActivity --es com.abc.daodian.launch.ROUTE reminder/list`，新建一条是 `reminder/edit`），**2026-09-11 用户已手动加到桌面，真实渲染正常**（抬头「到点 +」、空状态文案）。桌面小组件没法用 adb 绑定（`cmd appwidget` 在这台 ROM 上不存在），只能手动长按桌面添加。
  **版式按条数和尺寸自适应**（2026-09-11）：一条是大字时钟；两三条在 3×2 / 4×2 是一行一条的时间表（最多三行）；3×3 / 4×3 起是大字时钟 + 底下最多三行。靠 `RemoteViews(Map<SizeF, RemoteViews>)` 让桌面按**实际量出来的**大小挑版，规则见 DESIGN.md §8.2。起因：3×2 内容区只有 83dp，旧门槛要 100dp，两条待办只画了一条。
  **真机验过**：3×2 两条（「洗澡 今天 22:06」「吹头发 今天 22:09」+ 脚上「下一条 · 13 分钟后」）；点墨印后纸从整块小组件长出来，倒推的框和截图差 4px 以内。
  **没验**：4×2 / 4×3 / 4×4 —— adb 拖不了尺寸，得用户手动拖；拖完第一次点墨印用的是「新报的尺寸 + 上次实测的差」，准不准要看荣耀的偏差是不是常数。
- **语音改成本地识别**（2026-09-11）：小组件语音「权限给了但不能用」—— 荣耀的 MagicVoice（YOYO）绑得上、会开麦克风，但对第三方一个回调都不回；网关也没有转写接口。现在 `VoiceInput` 自己录音 + sherpa-onnx 本地流式识别，桌面速记和对话页麦克风共用，来龙去脉见 DESIGN.md 决策 8.3。
  **真机验过**：从 Mac 扬声器放「你好，今天天气怎么样」→ 桌面速记里逐字出来 → 停顿自动收 → `dumpsys audio` 里是 `src:VOICE_RECOGNITION pack:com.abc.daodian.debug`，没有 YOYO。模型首次加载 1.9s，期间录音不丢。
  **代价**：release 包 35MB → 88MB（arm64 `.so` 24MB + 模型 26MB，都不压缩进包）；AAR 在 `app/libs/`、模型在 `assets/asr/`，没 Maven 坐标。验证用 debug 包的 `files/quick_trace.txt`，`voice ←` 那几行就是识别事件。
- **图标和开屏**（2026-09-11）：墨绿气泡 + 钟面 + 三道响声，照用户给的参考图描的（上一版朱砂印被用户换掉了）；开屏是同一个图形弹出来、钟响一下。规则见 DESIGN.md §8.1「图标与开屏」。`ic_launcher_foreground` / `ic_launcher_monochrome` / `splash_logo` 三个文件是同一套 path，改一个要三个一起改。
- **模型服务搬进 app**（2026-09-13）：顶栏那枚印能点了 —— 印带状态（朱砂好着 / 墨灰上次没连上 / 虚线还没配置），点开垂下一条纸签写模型名和网关，出问题才在纸签顶上压一条告警带（人话 + 原始异常），末行「改配置」进配置页（网关地址 / key / 模型 + 「测一下」）。配置存 DataStore，`secrets.properties` 降级成种子（一个字段都没存过时才用它）。规范见 DESIGN.md §8.4，设计稿：<https://claude.ai/code/artifact/03cc5792-4cef-4e61-8eec-55409437cd3a>。
  **只编译过，一条真机证据都还没有** —— 写完那天手机没连上（`adb devices` 空的）。要验的六件事：印章三态长什么样、纸签排版、点「改配置」跳得过去、改完 key 保存后下一句话真用新配置、「测一下」连得上（顺带确认 `ProviderTest` 走的是真路）、飞行模式下发一句话印章变墨灰、恢复联网后重新盖回朱砂。
  **思考开关**（2026-09-18）：配置页加了「先想一想再答」，存 DataStore（`thinking`，默认关）。开 = `reasoning.effort=medium` + `summary=auto`，关 = `effort=none`，在 `ResponsesClient.reasoning()`。**装上了、没验**：还不知道这个网关收不收 `reasoning` 参数、开了之后有没有思考流出来。

- **提醒列表改成时间轴**（2026-09-18，设计稿方向 B：<https://claude.ai/code/artifact/4de04ade-2aa5-4486-b1b7-293ff283f00d>）：一根竖线 + 朱砂「现在」横线，线上是今天已过去的（淡掉、写落定时刻），线下第一条放大成「下一条」；轴上的圈 = 完成，点行 = 编辑，左滑 = 删除，都给 5 秒墨色「撤销」条。没有单独的已完成区。**整根轴是滚轮**（2026-09-18/19，设计稿：<https://claude.ai/artifact/3EYZp9KRSBgaXc9Ec92tmG> 方向 B「宽焦带」）：从上往下 以后 → 今天 → 以前，今天之内也是晚的在上；打开时「现在」停在屏幕 33% 高（以后的不够撑就贴顶，顶上写「以后还没有安排」）；屏幕 18%–78% 完全清楚，带外淡到 0.3，贴顶时清楚带从 0 起（`Modifier.wheel`）。**不要再加虚化 / 缩放**：试过每行一个 BlurEffect，掉帧、行边透方框，今天贴顶时还被虚掉，用户否了。前几天落定的（完成 / 取消 / 响过没点）按改状态那天分组，滑远了右下角「回到今天」。左滑删除只认距离（拖过行宽 40%），不认甩的速度 —— 之前上下滑时容易误删。
  删除是当场真删，撤销 = `vm.restore()` 原样插回并重排闹钟。
  **真机验过**：时间轴排版；点圈完成 → `dumpsys alarm` 里那条消失 → 1 秒内点撤销 → 闹钟回来（`origWhen=2026-09-18 23:00`）；左滑删除后无残留闹钟；空状态。
  **没验**：告警态（过点没响、没排上 + 「重排一次」）、深色、重复提醒的完成/撤销。
- **当天事项**（2026-09-18）：只说了哪天、没说几点（「今天把报销交了」）。晚上收尾时刻（设置里改，默认 20:00）提醒一次，没做完顺延到第二天；不发早上的提醒，靠小组件看。规则见 DESIGN.md §4.3，逻辑全在 `reminder/scheduling/DayTasks.kt`，调度层没改。当时 Room 升到 v2（AutoMigration 加 `dueDay` 列）；2026-09-23 包结构重排、连数据卸载后回到 v1 —— 以后改表都要在 `ReminderDatabase.autoMigrations` 里加一条。
  **真机验过**：v1 库覆盖安装迁移成功；「remind me to submit the expense report today」→ `allDay=true`、`dumpsys alarm` 20:00；把收尾时刻临时拨到 16:55 → 准点响（漂移 23ms）、普通通知不弹全屏、闹钟顺延到 `2026-09-19 16:55`；通知上点「完成」→ DONE、闹钟和通知都收掉；两条同时到点合成一组；「remember to buy groceries」（没说哪天）→ 今天；「call mom tomorrow」→ 明天那组最上面；改收尾时刻后全部闹钟跟着挪；覆盖安装后闹钟都还在。
  **没验**：列表的「拖N天」样子（得等到明天）、小组件上的当天事项、重复的当天事项（「每天背单词」）、手动编辑页的「当天之内」切换、中文句子（adb 打不了中文，测的都是英文）。免打扰开着，组头「只响一下」没听到。
- **设置页 + 编辑页改版「一本账」**（2026-09-18，设计稿方向 A：<https://claude.ai/artifact/UdcBGTTx5quxPfsnR5Akq7>）：设置页顶上一句体检结论 + 四组纸，从系统设置回来自动重查；编辑页宋体标题 + 人话复述 + 三组纸 + 底部「记下」，几点是自绘滚轮、重复写成具体规则、已有的能在页内删。复杂重复规则（模型建的「每周一、三」）以前一存就被压扁成「每周」，现在原样保留。
  **真机验过**：设置页排版、漂移写人话（「+2 小时 4 分」）；编辑页新建 → 滚轮点常用钟点一口气滚到 21:30、手拖吸附 → 重复底纸 → 存 → `dumpsys alarm` `origWhen=2026-09-18 23:30` → 列表点进去「删掉这条」→ 闹钟消失。
  **没验**：权限缺项时的红字 / 「去开」回来变对勾、深色、「当天之内」切换后的样子、CUSTOM 规则的保留、日期选择（还是 Material 的 DatePicker，没重画）。
- **harness（agent 框架）**（2026-09-18）：`harness/` 包，ReAct 循环 + 工具接口 + `LastTurns(10)` 裁剪，规矩见 DESIGN.md §6.8。**对话页和桌面速记已迁过去**，`ai/` 目录已删。（2026-09-23 起：循环在 `agent/engine/`、调模型和供应商配置在 `agent/model/`，接线在 `agent/conversation/ChatAgent.kt`，提醒工具 + 闸门在 `reminder/tools/`、`reminder/domain/`。）（09-18 ~ 09-23 有过授权模式和授权条，已删，见下面「问卡与痕」。）
  **验过（JVM）**：`./gradlew :app:testDebugUnitTest` 9 条离线单测；打真网关（环境变量覆盖供应商，不落文件）：
  `DAODIAN_LIVE=1 DAODIAN_BASE_URL=… DAODIAN_KEY=… DAODIAN_MODEL=… ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest*' -i`。
  `deepseek-flash`（旧网关）和火山方舟 `deepseek-v4.1-flash`（`https://ark.cn-beijing.volces.com/api/plan/v3`）都过：流式调工具 → 结果回传 → 收尾，下一轮重放工具调用也认。
  **真机验过（2026-09-19，火山方舟 deepseek-v4.1-flash，英文句子）**：放开模式 → 直接落印、`dumpsys alarm` `origWhen=2026-09-20 15:00`；先问模式 → 授权条出现、卡片虚线「等你确认」→ 点「不」→「× 没记下」划掉 + 「好，不建了。」、没闹钟；授权条亮着时打字 → 缩成一行、朱砂边 → 发出去 → 旧卡划掉、新一轮按 9 点重问 → 点「好」→ 盖印、`origWhen=2026-09-20 09:00`；重装后对话还在、卡片收起。修过一次：授权条细节「不重复」被挤成两行。
  （上面这段是授权条时期的验证记录，功能已删。）
  **注意**：`secrets.properties` 里的 `gpt-5.6-sol` 旧网关已经不给了（503），app 里要在配置页换成能用的供应商。
  **对话落盘**（2026-09-18）：对话页的对话存 `agent/conversation/data/` 里单独的 `chat.db`（提醒库没动），重启读回最近 100 轮、接着聊，规矩见 DESIGN.md §6.8。桌面速记不落盘（用户定的）。真机验过：覆盖安装后对话还在、历史卡片收起，`databases/` 里有 `chat.db`。
- **记账（2026-09-23 实现中）**：**模型读懂通知**，代码只收集、存档、排期、校验。设计、数据格式、代码地图、和计划的出入全在 [LEDGER_PLAN.md](LEDGER_PLAN.md)（§11 是实现）。
  通知直接进单独的 `ledger.db`（`PaySampler` 类名没改 —— 通知使用权按组件名授）；每 3 小时后台整理 agent 读一批（有才叫模型）；每晚 21:30 还有没认出来的才弹通知；对话里能查、记、改。
  **顶栏改了**：左边抽屉键，右边印章（设计稿 <https://claude.ai/artifact/PRk3CWeu24V4tKZgxkGLwn>，抽屉方向 B「两张纸」）；提醒列表、记账、设置都在抽屉里。后台 agent 在跑时印外面转一圈细线（`agent/engine/background/AgentActivity`）。
  调研时的临时采样页已删。调研样本 `files/pay_samples.jsonl` 当时第一次启动导进库、改名 `pay_samples.imported.jsonl` 留底；2026-09-23 包结构重排要连数据卸载，导入代码（`LegacySamples`）已删，样本想留要在卸载前拷出来（命令见 PROJECT_STRUCTURE.md）。
  **真机验过**（9-23）：导入 → 整理（真网关，15 秒 20 条）→ 抽屉 → 记账三层 → 对话改账 → 对账一轮，细节和没验的见 LEDGER_PLAN.md §11「状态」。
  清账本重来：删 `databases/ledger.db*`。**同时把 `chat.db` 里聊过账的那几轮删掉**：新账本的 # 编号从 1 重排、换了别的笔，对话里旧的「改好了」会让模型以为清单上的几笔已经处理过、一笔不问（9-23 真踩过：我测试时编的回答留在对话里，用户对账时 4 笔被跳过）。
- **问卡与痕**（2026-09-23，设计稿方向 B <https://claude.ai/artifact/2fA2GMup6w4XdsdK5oAGg8>，动效稿 <https://claude.ai/artifact/BTqaHuU6hbgjmG6NqPv5HP>）：对话里不再有提醒卡片和授权条。写操作直接办，留一道**痕**（一行小字「✓ 提醒 9月24日 周四 08:00 · 带伞 ›」，代码按工具结果画，点了去编辑页 / 那一笔）；拿不准时模型调 `ask_user` 出**问卡**（先猜好答案，点一下 / 「其他…」自己写 / 直接说），答完「问」换成「答」盖印。设置页「建提醒前先问我」删了；依据挪进编辑页「原话」下面（从 `chat.db` 取）；桌面速记遇到问卡交给对话页（当时是 `WidgetTarget.Say`，现在 `Launch` 带 `say`）。规矩见 DESIGN.md §6.9。
  **真机验过（火山方舟 deepseek-v4.1-flash，英文句子）**：直接建提醒 → 墨条 → 痕打勾 + 一句话、`dumpsys alarm` `origWhen=2026-09-24 08:00`，没有任何确认；「明天下午给妈妈打电话」→ 一题问卡（15:00 / 14:00 / 17:00，输入框「都不是？直接说……」、发送键是箭头）→ 点 15:00 墨色洇满、另两颗淡掉 → 收成「答 打电话 15:00」→ 痕 + `origWhen=2026-09-24 15:00`；「下周看牙医和理发」→ 两题问卡 + 「其他…」+ 卡脚 → 点「下周三」、第二题点「其他…」（朱砂边 + 句首「哪天去理发：」）打字 → 落成实心猜测、「2 题 都点了」→「就这样」→ 收成两行记录 → 两道痕 + 两个闹钟；历史的记账轮次重启后画成「✓ 记账 改了 2 笔 ⌄」、点开逐笔；痕点进编辑页。
  **踩到的**：模型把「月底」写成 `BYMONTHDAY=-1`，`Rrule` 以前拿 -1 去 `withDayOfMonth` 会抛异常（第一次响完排下一次时出事）—— 已修，支持负数、加了单测；人话写成「每月最后一天」。模型参数写坏、立刻重来的那一次红 × 不画。答完问卡后网关那一步走了近 3 分钟（界面一直墨条 + 「停」，状态对，只是慢）—— `ResponsesClient` 没设超时，用的是 openai-java 默认的 10 分钟。
  **没验**：每晚对账的多笔问卡（账当时全对完了，没有待确认的）、直接说（不点、打一句话）、问卡等着时点「停」 / 杀进程后读回「没答」、桌面速记交给对话页、编辑页上「依据」那一行（验到一半用户在用手机，停了）、深色。
- **正文按 Markdown 画**（2026-09-24，规矩见 DESIGN.md §6.10）：**不用提示词约束模型的格式**（用户定的），模型写什么界面认什么。
  解析在 `agent/conversation/Markdown.kt`（纯 Kotlin），画在 `MarkdownText.kt`；逐字洇开的到货记录抽成了 `rememberInk`（`ChatMotion.kt`），`InkText` 用法不变。
  **在云端会话里写的，app 没编译过**：那个环境的网络策略拦了 `dl.google.com`，AGP / androidx 拉不到。
  只验了两样：`MarkdownTest` 23 条在单独的 JVM 工程里跑过；两个 Compose 文件对着 JetBrains 桌面版 Compose 1.7.0 编译通过（API 同源，但不等于 Android 编译）。
  **本地先 `./gradlew :app:testDebugUnitTest` 再装机**。真机要看：粗体 / 列表 / 表格的样子、宽表横滑和右边渐隐、流式时 `**` 不闪、深色、表格里的链接能点。
- **全屏页在锁屏上确实会弹**（2026-09-04 关屏实测，用户肉眼确认，点「完成」后闹钟正常取消、无残留排期）。
  别被 adb 骗了：`AlarmActivity` 是 `exported=false`，`am start` 起不来；关屏后隔几十秒截图也只会拍到黑屏 ——
  用户已经把它关掉了，`screencap` 拍的是关掉之后的状态。`appops` 里那条 `USE_FULL_SCREEN_INTENT rejectTime`
  同样不能当证据用（它跟这次弹出并存）。**这一屏想拿证据只能靠肉眼或录屏**，别用截图下结论。
- **键盘 inset 踩过一次**（已修）：`MainActivity` 没写 `windowSoftInputMode`，默认 adjustPan 会把整个窗口往上推，再叠上 Compose 的 `imePadding()`，输入框被顶到半空、标题被推出状态栏。现在manifest 钉 `adjustResize` + `enableEdgeToEdge()`，inset 只留 Compose 一个来源；底部用 `ime.union(navigationBars)` 而不是两个 padding 各加一遍。
  **第二次**（2026-09-23 已修）：键盘弹起时输入框上去了、对话没跟上 —— 列表是从底下被压矮的，LazyColumn 默认钉住顶上，最新一句压在输入框后面。现在 `ChatScreen` 盯着视口高度，矮了多少往下滚多少（贴底的直接滚到底），问卡长出来同理。真机验过：弹键盘后最后一句完整露在输入框上方。
- 视觉设计稿（Claude Design 画布）：<https://claude.ai/code/artifact/c7073888-2022-4a7f-bb22-4ae61961d0a1>

## 环境速查（详细坑点见 README「几个踩过的坑」）

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug   # 打包（JDK 21 已在 gradle.properties 里钉死，换机器要改那行）
./gradlew :app:installDebug    # 装机；MagicOS 会弹安装确认框，装不上重试一次通常就过
```

真机是荣耀 LGE-AN10（Android 15），一直连着 USB。`adb devices` 偶尔连不上，`adb kill-server && adb start-server` 能救。这台 ROM 屏蔽第三方 app 的 logcat 输出，看不到日志不代表代码没跑——证据以 Room 的 `fire_log` 表 / app 内「投递日志」页为准。

`secrets.properties`（gitignored，根目录）要填了 `LLM_API_KEY` 等四项，AI 功能才有东西可测；模板在 `secrets.properties.example`。

## 不要碰的假设

- **`reminder/scheduling/` 和 `reminder/data/` 是唯一不允许出错的部分**（见 DESIGN.md §05）。改这两个包之前先想清楚：AlarmManager 里的排期是易失的，Room 是唯一真相，四个重排触发源缺一个都可能导致漏提醒。
- **界面整包可丢弃、可重画**（各模块的 `presentation/`、`agent/conversation`、`agent/shell`），改起来不用犹豫。
- **包结构和依赖规则见 [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)**：`agent` 不 import 模块、模块之间不互相 import、跨模块跳转只用路由。加功能先想它归哪个模块、要不要经接头。
- 换 Kotlin/AGP/Compose 版本前看 README「关于依赖版本」那条约束链，顺序不能反。
- `minSdk` 定的是 **34**（不是当初设计文档写的 33），理由和踩坑过程见 DESIGN.md 决策 3.2 —— 已经改过一次，别改回去。

## 这一路上做过的、容易被遗忘的决定

- **openai-java 官方 SDK，不是手写 HTTP 客户端**（DESIGN.md 决策 3.1）。代价是包体：R8 之后从 2.2MB 涨到 35MB（+33MB，全是 Jackson + kotlin-reflect + victools）。这是接受了的权衡，不是 bug。
- **工具调用（Responses API `tools`），不是 `response_format: json_object`**。后者测试时模型会自己发明字段名（`{summary, details:{...}}`），工具调用把 schema 交给服务端强制，稳得多。JSON 输出那几档（`jsonMode` / `apiStyle`）已经连代码带 BuildConfig 字段删掉了。
- **多轮历史客户端自己带，不用 `previous_response_id`**。第三方 OpenAI 兼容服务大概率没实现服务端会话状态。现在是 `agent/engine/Session` 保存完整结构（含工具调用和结果），每步按 `ContextPolicy` 选轮次原样重放；每句用户话自带说话时刻。
- **字体用系统泛型（`FontFamily.Serif`/`Default`/`Monospace`），没打包视觉稿里的 Google Fonts**。理由同样是包体——Noto Serif SC 全字重能再吃掉大几 MB 到十几 MB，personal app 性价比存疑。想要像素级还原字体，需要往 `res/font/` 里塞真实字重文件，这是已知的、故意留下的差距，不是疏漏。「墨宋」这套视觉靠宋体挑大梁，系统衬体的中文 fallback 好不好看直接决定观感 —— 真机上第一眼要盯的就是这个。
- 通知全屏页是独立的 `AlarmActivity`，不是 `MainActivity` 借用 `showWhenLocked`——这样"到点响铃"和"正常打开 app"两件事不会互相污染。

## 常见误区

- 不要以为「编译通过」等于「能用」——这个项目里唯一有意义的验证是真机行为（闹钟真的进了 `dumpsys alarm`、通知真的弹出来、AI 真的调用了工具）。截图 + `adb shell dumpsys alarm` 比看代码可信。
- 不要在没有 `secrets.properties` 真实 key 的情况下猜测 AI 相关代码"应该没问题"——这个项目已经因为想当然的假设错过两次（`jsonMode` 一开始没接进请求、`json_object` 档模型自己发明字段名），都是真机测试才发现的。
