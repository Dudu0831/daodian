# 到点 (daodian)

给 Claude Code 会话的操作手册。是什么、怎么构建看 [README.md](README.md)；**改代码前读 [DESIGN.md](DESIGN.md) 对应那一节**（为什么这样、要守的规矩，代码注释里的 `§5.3` 都指它）。这份只记三样：现状、怎么拿证据、坑；以前的进展在 [docs/history.md](docs/history.md)。

## 现状（2026-10-22，过时了就更新）

这一节只记**现在**：手机上装的是什么、线上是哪一版、还差什么没验。每一步当时怎么做的、装过哪些包、真机上验过什么，在 [docs/history.md](docs/history.md)（10-22 之前的原样挪过去了）。以后做完一件事先在这里写一两行；验完了、或者被后面的版本盖过去了，就挪进 history。

**手机和线上**
- 真机是荣耀 LGE-AN10（Android 15），模型是火山方舟 `deepseek-v4.1-flash`。
- 线上：**0.1.10 / versionCode 12**（10-24 用 `scripts/publish.sh` 发的：模型服务能选 Chat Completions 接口、「测一下」照聊天真发的请求测）。上一版 0.1.9（11）是 10-22 发的（拍一张、权限页随时能点、每晚对账修、诊断补到点那一刻）。
- 用户手机：release 包 `com.abc.daodian` 是**本地打的 0.2.0（13）**，10-27 用 adb 覆盖装的（没发版，线上还是 0.1.10），带通知管家清理后的新链路和对话页改版，还没提交；版本号比线上大，「检查更新」不会再提示 0.1.10。debug 包 `com.abc.daodian.debug` 也在（10-22 装的，带「拍一张」）。两个包是两个 app（§11.1）。
- 库：`reminder.db` v3、`chat.db` v3、`ledger.db` v1（main 上已经是 v2，下一次发版带上去）。库升上去之后旧版本打不开，只能往前走。

**main 上有、还没在真机上验过的**（细项见下表）
- **10-26 `ledger.db` v1 → v2**（通知管家第 2 步，`raw_notification` 加可空列 `noticeId`，auto migration）：下一次发版就带上去，不管通知管家接没接线。要验：覆盖装 debug 包、打开一次记账页，拉库看 `PRAGMA user_version` 是 2、`noticeId` 这一列在、旧原文和流水都在。`intake.db` 还没有代码会去开它。
- **10-24 0.1.10 两种协议 + 「测一下」照聊天发**（DESIGN §6.2；`runtime/model/ChatCompletionsClient`、`StreamingClient`、`AgentRuntime.test`；配置页「接口」两选一）：JVM 测试过了，用户手机上装了，**用户自己在测，结果还没回来；没打过真网关**。要验：配置页选 Chat Completions 测一下、聊几句带工具调用的（建提醒）、带图的一句；`LiveGatewayTest` 加 `DAODIAN_API=chat` 跑一遍；只认 chat 的那家网关上，选 Responses 时「测一下」要报错。
- **10-21 权限页每一行随时能点**（`shell/PermissionsScreen.HealthRow`、`HealthCheck.manualIntent`）：好着的行也整行可点、右边淡箭头；「应用启动管理」跳荣耀系统管家那一页（manifest `<queries>` 里声明了 action，解析不到退应用详情页）；电池优化好着时跳系统列表。没核的：「精确闹钟」好着时点进去是不是一闪就退（只有 `USE_EXACT_ALARM`）。
- **10-21 导出诊断的「全屏没弹」「只响不震」**（`reminder/ReminderDiagnostics`、`reminder/delivery/FireProbe`）：导出的声音、免打扰、渠道、振动、全屏许可几段在荣耀上读不读得出来（整表读设置那几行可能是空的）；到点后 `files/fire_probe.txt` 有没有那一刻的样子和「全屏页起来了 · 发出后 N ms」两行。
- **10-21 每晚对账「弹了通知、点进去什么都没有」**（`ledger/reconciliation/LedgerCheck.asks`，三处只认 3 天内的）：是从代码上推出来的，没核对用户那几条「看不清」是不是真在 3 天以前；装上后到点看还弹不弹，弹了点进去要有东西可对。
- **10-18 0.1.8 里的两样**：重复提醒的「完成」「稍后」（`Series`，见表里调度内核那一行）；整理员把转进来的钱记成收入（`organize-v8`），没拿真通知验过。
- **10-16 每天后台查三次更新**（`update/DailyCheck`）：只有 release 包排这个闹钟，`dumpsys alarm | grep -A4 CHECK_FIRE` 看下一个钟点；真发通知要线上有比手机大的版本。
- **10-15 下载记得住、不重下**（§11.2）：手机上的 release 包已经带新代码，从设置 →「版本」升 0.1.10 那一跳就看得到效果。
- **10-15 `minSdk` 33**（决策 3.2）：没有 Android 13 的手机验过 —— 锁屏到点弹不弹全屏页、点「完成」无残留排期、体检「全屏 intent」是对勾、速记那张纸进出没有系统动画叠着。

**还没提交的**
- **10-28 设置页重排**（设计稿 <https://claude.ai/artifact/VhupokZXiZjFpi4iijS72w> 方向 A；DESIGN §08、§9.1、§2.3）：首页三组 助手 / 功能（提醒、记账、通知管家）/ 系统（权限、导出诊断、版本），行首不放记号、不写固定说明；「权限与监听」拆成「权限」（只放权限）和通知管家自己一页（`intake/presentation/StewardScreen`，路由 `intake`）；投递日志换成统计页「响得准不准」（`reminder/presentation/log/AccuracyScreen`，路由照旧 `reminder/log`）；记账的「抓到的通知」和抓取页删了，记账总览「少了一笔？」跳收到的通知；`IntakeModule.LABEL` 改成「通知管家」。JVM 测试过了、`assembleDebug` 过了，**没装机看过**（10-28 那会儿 adb 认不到手机）。要看：首页三组对齐、通知管家那行「连着 · 今天 N 条」和右边规则数、监听断了 / 没开使用权变红；权限页顶上没有投递那行；响得准不准页的竖条和三个数；记账设置页四行；「少了一笔？」点进收到的通知；深色。
- **10-27 对话页改版**（设计稿 <https://claude.ai/artifact/2GLz38yT7agaU24ygRbLh2> 方向 A；DESIGN §6.5「间距」、§6.6、§6.7「分层」；`MarkdownText`、`AssistantTurnRow.gapBefore`、`AskCard`）：长回复分层（第一段照旧、后面退一档，金额日期钟点换宋体，粗体改成划重点）、连着的痕挨着排、问卡收紧、答完收成一题一行。稿子 B1 的旧回合收起做了又删了（用户说奇怪）。JVM 测试过了；10-27 装进 release 包打开看过一眼（深色）：答完的多题问卡收成一题一行、「答」印在第一行，后面的痕挨着排，都对。其余还要验：长回复第二段起变浅变小、数字是宋体、粗体底下那道淡墨压在字下半截（深色下也看得见）；一题的问卡是卡头问句 + 一排胶囊、点一颗就答；多题的金额打头；答完那一下卡化成行、「答」印盖下来；速记那张纸上的回合（同一个 `AssistantTurnRow`）没被挤坏。
- **10-21 长按桌面图标的「拍一张」**（§6.10，`shell/AppShortcuts`）：debug 包上 `dumpsys shortcut` 里有这一项，用户试过。从桌面进来拍的图不进输入框，原因是叠了第二个对话页，已修（`AppNavHost.toAssistant()`）：从外面进来后按返回一下就回桌面，app 关着、开着两种都验过。修完后拍照进输入框要用户再试一次。

**还开着的事**
- **通知管家**（DESIGN §12、§2.3，10-26 定的方案；**第 1 步纯 Kotlin 核心 `intake/core`、第 2 步 `intake.db` 和 `ledger.db` v2、第 3 步模块插口 `intake/plugin` 和提醒子代理、第 4 步通知管家（`intake/steward`）、第 5 步助手建规则（六个工具，**只进 debug 包**，提示词 `assistant-v2`；release 还是 `assistant-v1`）写完了；**第 6 步接了线，debug 包「权限与监听」页最底下有开关（默认老链路），10-27 真机上用模拟通知走通了（细项和发现的问题在 NOTICE_PLAN 第 6 步），真短信没验；第 7 步规则页、收到的通知（设计稿方向 B）写完了，用户 10-27 在真机上看过；第 8 步老代码删完了（派活、订阅者、开关都没了，release 也只走新链路），**没发版、DESIGN 没跟着改**，清理之后没装机看过**；管家、助手建规则两条打真网关的测试 10-27 在火山方舟上过了**）：规则 = 硬条件（代码，只管进不进管家）+ 软规则（管家读）；中了的进收件箱 `intake.db`（办完的留 30 天），**每一条都过通知管家**（3 秒防抖、界面上不露面），管家交给模块子代理（记账整理员、派活改成的提醒子代理）。规则只在对话里建、说清楚才建，设置页只看 / 停 / 删。**旧配置不搬、旧数据不删**（升上来在对话里重新说），只留 `reminder/relay` 路由的壳。工程文件是根目录 `NOTICE_PLAN.md`（九步，前五步不接线；第 6 步 debug 包开关真机验；界面先出设计稿）；它第 10 节和 §12.8 还有几条没定。
- agent runtime 重构（根目录 `REFACTOR_PLAN.md`）：第 1–5 步和复查后补的那一轮都合进 `main` 了，剩第 6 步（可选）和第 9 节待定，方案先留着。debug 包上的「演一遍库迁移」、strict 故意改坏那条没做。
- **48 小时放置测试**（DESIGN §9.3）：调度唯一还缺的硬证据。
- 包重排（09-24）那份测试清单里「main 上就没验过、顺手可以验的」那一节，可选：`git show 8e5ee3c:TEST_PLAN.md`。

| 功能 | 还没验 |
|---|---|
| 调度内核 | **48 小时放置测试**。**重复提醒**（`Series`）：`reminder.db` v2 → v3 迁移（照「演一遍库迁移」，迁完 `seriesNextAt` 全是 NULL、闹钟都在）；建一条几分钟后的每天提醒，到点在全屏页点「完成」，`dumpsys alarm` 里还排着明天同一钟点、列表里还在；点「稍后」10 分钟后再响，响完排回明天原来的钟点（不是晚 10 分钟）；稍后着在全屏页点「完成」，稍后那次不响 |
| 对话 agent | 断网 / 坏 key 的回退；推理模型的思考块（这家供应商不发）；思考开关网关收不收；「↓ 新内容」 |
| 对话页按住说话（§8.3；`ConversationInputBar` 的 `HoldToTalk`、`VoiceHoldOverlay`、`VoiceInput.start(hold = true)`） | **没逐条验过**（用户上手试过，细项没记）：点麦克风换档、再点换回打字，打了一半的草稿还在；按住出文字框，没出字是波形、出字后只有字，变宽、往上长、很长时在里面滚；松手这句发出去、文字框淡掉、输入条留在「按住说话」；上滑过线变虚线划掉 +「松手取消」，滑回来接着说；按不到 0.3 秒的提示、没听清的提示 3 秒自己收；回合在跑时这一档压暗、「停」在；问卡等着时按住说算回答；旁边有人说话时松手照样收得了；按满 60 秒；说着切到后台不发；深色；`quick_trace.txt` 里 `voice start (hold)`；模型头一回加载期间按住说，开头的字丢不丢 |
| 对话页发图片（§6.10；`ConversationInputBar` 最右那一格、`PendingImages`、`ImageSourceSheet`、`BubbleImages`、`ImageViewer`、`FoldImages`、`ConversationImageFiles`） | **没逐条验过**（用户上手试过，细项没记）。**模型收不收图**（`LiveGatewayTest` 带图那条，或者真发一张）；`chat.db` v2 → v3 迁移后旧对话都在；最右那一格 ＋ / 发送 / 停三样换；点 ＋ 出底纸，相册（系统选图页，不问权限）、相机；选 1 张、4 张，第 5 张加不上；叉去掉一张、虚线 ＋ 再加；不打字只发图；说话那一档带着图按住说、点发送只发图；气泡里一张（横的、竖的、很长的截图）和 2–4 张的排版，图解出来那一下列表跳不跳；点图全屏、两指放大、双击、几张左右滑、放大时不翻页；喊停撤回时图退回待发那一行；杀进程重开图还在；隔 3 轮后 `context_trace.txt` 里「带图片 0 张、折掉 N 句」；模型看不了图时那句报错、印不变灰、「换个模型」进配置页、换完「重试」不用重选图、不重试接着说别的不再被退；相机拍的照片方向对不对；深色 |
| 长按图标「拍一张」（§6.10，`shell/AppShortcuts`） | 拍完那张进待发那一行（叠页修完之后）；app 停在别的页时点它回对话页；取消相机不留东西；已有 4 张时提示、不开相机；release 包上也有这一项 |
| 轻提醒（§5.6；`Reminder.gentle`、`Notifier.fireGentle`、渠道 `gentle_v2`） | **10-11 改完的这一版到点时什么样**：`gentle_v2` 带震动 —— 通知音量是 0 时震不震（`dumpsys vibrator_manager` 里看有没有这个包的一次）、建它时 `gentle_v1` 删没删；到点后一次性的直接是 `DONE`、列表里落成打勾的过去一行，没有「响过了，还没点完成」；通知上只有「稍后 10 分钟」，稍后那次还是轻提醒；没送到的（关掉渠道）留在 `FIRED`；已经建过的 `gentle_v2` 渠道下次到点会不会改名成「轻提醒」；列表小字、痕上的「轻提醒」。**一直没验的**：通知音量开着时是不是只响一声；手机静音；重复的写成「每天 · 轻提醒」、到点后翻到下一次；选「当天之内」那行收起；对话里说「不用响铃」痕多「· 轻提醒」，没说的照旧响铃；派活来的不会变成轻提醒；体检里那一项（系统里关掉渠道变红）；浅色 |
| 问卡与痕 | 不点直接说；问卡等着时点「停」、杀进程后读回「没答」；多笔的对账问卡；深色 |
| 当天事项 | 列表的「拖 N 天」；小组件上的当天事项；重复的当天事项 |
| 提醒列表（时间轴） | 告警态（过点没响、没排上）；深色；重复提醒的完成 / 撤销 |
| 设置页、编辑页 | 深色；「当天之内」切换；CUSTOM 规则保留 |
| 模型服务（印章、纸签、配置页） | 印章三态；改完 key 下一句就用新的；飞行模式下变墨灰再盖回朱砂 |
| 桌面小组件、速记 | 4×2 / 4×3 / 4×4（只能手动拖） |
| 记账 | 3 小时后自动整理；21:30 通知的「晚点」「今天算了」；**对话末尾的对账虚线段**：从图标进 app 看得到、点「现在对」开一轮、「今天算了」连通知一起收、空对话时压在例句下面、抽屉卡写「还等你对」；**两个模型怎么传话**（§10.1）：整理员读记忆（`organize_trace.txt` 里「记忆 N 条」）、「待问」带猜测和依据、对账问卡的选项照它出、对话页垫类别表（整理员后来建的二级，对话里认得）、说「8837 是老婆的卡」会进记忆；**打标签**（§10.4）：对账问卡每笔底下「＋ 打标签」（模型得在题上写 `ref`）、答完收起接「· 约会」；账单页「标签」行原地挂 / 取下、打字找、撞类别当场提示；对话里「今天除了打车都是约会」痕写「改了 N 笔 · 标签 +约会」、「那笔不算」取下；设置「整理员自己打标签」默认关（开着时 `organize_trace.txt` 里多「标签 N 个」）；**听哪些 app**（§10.2）：「没勾的一条不存」「勾上当场扫通知栏」没单独查过库；**转进来的钱记成收入**（`organize-v8`）没拿真通知验过 |
| 上下文压缩、记忆（§6.9） | 记忆页改 / 删 / 撤销 / 「+」、写超 60 字或卡号当场报；说「记住…」出痕、痕点进记忆页；闲 10 分钟后的整理；设置页「聊完自己整理」关掉 / 再打开；攒满 2.4 万字触发的压缩、旧查询结果折掉；压缩失败时退回最近 24 轮；速记垫记忆；提醒列表左滑删和撤销（组件挪进了 `shared/ui`）照旧 |
| 通知管家（§12；设置 →「通知管家」→ 规则 / 收到的通知） | **真短信、真微信**（10-27 只用 `cmd notification post` 模拟过）；锁屏下被遮蔽的取件码短信等不等得到真正文；同一条重发会再建一条（NOTICE_PLAN 第 6 步）；记账整理完回执从「待整理」变成那一笔；清掉老代码后的 debug 包、release 包都没装机看过；派活旧通知点进来跳规则页（壳） |
| 抓取页 | 10-28 删了，记账总览「少了一笔？」改跳通知管家的「收到的通知」 |
| 设置页（§08：首页一列入口，一个模块一行） | 10-28 重排后没装机看过（见上面「还没提交的」）；页面之间只往下走、不横跳；「聊完自己整理」开关在记忆页 |
| 内测：签名、检查更新、导出诊断（§11） | **从设置 →「版本」覆盖升级**（0.1.6 起一次都没在真机上点过）；**每天后台查更新**：`dumpsys alarm` 里排着下一个钟点、开机后还在；到点查了（导出诊断「检查更新」那行的上一次）；有新版时弹「新版本」通知、同一版第二次不弹、点开进设置页、开始下载后收掉；没网时等有网再查；**崩溃记录**（还没真崩过一次，没见过诊断里那段堆栈）；release 包上导出诊断、发微信；10-21 加的诊断几段（见上）；别的机型（小米 / OPPO / vivo）的后台限制 |

## 环境速查

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug   # 打包（JDK 21 在 gradle.properties 里钉死，换机器要改那行）
./gradlew :app:installDebug    # 装机；MagicOS 会弹安装确认框，装不上重试一次通常就过
```

真机是荣耀 LGE-AN10（Android 15），一直连着 USB。`adb devices` 偶尔连不上，`adb kill-server && adb start-server` 能救。
`adb` 不在 PATH 上，用 `$ANDROID_HOME/platform-tools/adb`。**装包时手机得解着锁**：锁着屏 MagicOS 的确认框没人点，报 `INSTALL_FAILED_ABORTED: User rejected permissions`。

**内测发版**：先在 `app/build.gradle.kts` 增加 `versionCode`、修改 `versionName`，然后：

```bash
./gradlew :runtime:test :app:testDebugUnitTest
python3 -m unittest discover -s scripts -p 'test_pgyer_release.py'
./scripts/publish.sh --check          # 只检查环境
./scripts/publish.sh "这一版改了什么"  # 正式发布
```

自动打签名包 → 上传蒲公英 → 校验 → VPS 备份/下载转接 → 更新 `latest.json`；无需手填 `buildKey`。私有配置默认用相邻 `daodian-web/.local/pgyer-download.json`（可用 `PGYER_CONFIG` 指定）。中断后保留 `.local/pgyer-releases/`，用相同更新说明重跑；前置检查失败不切换线上版本。

发布成功后提交版本号和发版记录，创建 `v<versionName>` 标签，并将提交和标签推到 `origin`（DESIGN §11.2）。

release 签名靠根目录的 `keystore.properties` + `daodian-release.keystore`（都 gitignored，另有备份）；**签名文件丢了，发出去的包再也覆盖不上**。release 包的模型配置是空的，装上自己填。debug 包不查更新。

`secrets.properties`（gitignored）只是模型配置的种子（只进 debug 包）；里面那个 `gpt-5.6-sol` 旧网关已经 503，app 里要在配置页换成火山方舟（`https://ark.cn-beijing.volces.com/api/plan/v3`，模型 `deepseek-v4.1-flash`）。

云端会话（claude.ai/code）默认拉不到 `dl.google.com`，AGP / androidx 装不上，app 编不了。要在那边编译，得在环境的网络设置里放行 `dl.google.com`。

## 怎么拿证据

**唯一有意义的验证是真机行为**：闹钟真的进了 `dumpsys alarm`、通知真的弹出来、模型真的调了工具。「编译通过」不等于能用；没有真 key 时别猜模型相关的代码「应该没问题」—— `jsonMode` 没接进请求、`json_object` 档模型自己发明字段名，都是真机才发现的。

- **这台 ROM 屏蔽第三方 app 的 logcat**，`Log.i` 一行都看不到。证据看库和界面：
  - 闹钟：`adb shell dumpsys alarm | grep -B2 -A6 daodian`，`origWhen=` 就是排的时刻
  - 响没响、准不准：设置 → 提醒 →「响得准不准」（只有统计）/ 导出诊断里每一次的漂移 / `fire_log` 表
  - 语音：debug 包 `files/quick_trace.txt` 里 `voice ←` 那几行（对话页按住说话那一次开头是 `voice start (hold)`）。按住和上滑按理能用 adb 模拟（还没在这台机器上试过）：`adb shell input swipe x y x y 3000` 是原地按 3 秒，终点的 y 改小就是按着往上滑；按着的那几秒里另开一个 `screencap` 截文字框。说话得真人说
  - 库：`adb exec-out run-as com.abc.daodian.debug cat databases/<名字>.db > <名字>.db`（连 `-wal`、`-shm` 一起拷），再 `sqlite3`
- **内测的人那边**（release 包，不能 run-as、没有 trace 文件）：让对方点设置 →「导出诊断」发过来。里面是版本、机型、体检、响铃模式 / 音量 / 免打扰 / 三个渠道此刻的样子 / 振动设置 / 全屏许可（「只震不响」「只响不震」「全屏没弹」先看这几行，再看「到点那一刻」：每次响时亮没亮屏、全屏页起没起来）、闹钟排期和最近 30 次响的漂移、派活收到的、各 app 抓了几条、整理 / 对账成没成、最近 5 次崩溃的堆栈（崩溃记在 `files/crash/`，`shell/diagnostics/CrashLog`）。只有状态和数，不带对话、标题、金额、通知原文；各模块的那一段在 `ShellEntry.diagnostics`，加的时候守这条。「agent」那段列花名册上的几个：归谁、什么时候醒、各自的提示词版本
- **到点全屏页只能靠肉眼或录屏**：`AlarmActivity` 是 `exported=false`，`am start` 起不来；关屏后隔几十秒截图只拍到黑屏（用户已经关掉了）；`appops` 里那条 `USE_FULL_SCREEN_INTENT rejectTime` 也不能当证据。
- `adb install -r` 在这台机器上**不一定重启 app 进程**，装完用 `ps` 看启动时间，不然跑的还是旧代码。
  10-09 那次覆盖装完进程干脆没起来，`dumpsys alarm` 里旧闹钟原样还在，库要等第一次打开 app（或闹钟响）才被新代码打开、才迁移 —— 验迁移要先打开一次再拉库。
- **演一遍库迁移**（debug 包不在手机上时，全新装不经过迁移）：`git worktree add --detach <临时目录> <旧提交>`，在里面打 debug 包装上，深链 `reminder/edit` 进新建页、`input tap` 点「记下」建几条，拉库看 `PRAGMA user_version`；再 `adb install -r` 新包、打开、再拉库。要一条几分钟内到点的：编辑页「几点」底纸上有 09:00 / 12:00 / 18:00 / 20:00 / 21:30 几颗现成的钟点。
- adb 打不了中文，用 adb 测的句子都是英文。
- 小组件没法用 adb 绑定（这台 ROM 没有 `cmd appwidget`），只能手动长按桌面添加、手动拖尺寸。深链：
  `adb shell am start -n com.abc.daodian.debug/com.abc.daodian.MainActivity --es com.abc.daodian.launch.ROUTE reminder/list`（新建一条是 `reminder/edit`）
- 打真网关的 JVM 测试（环境变量覆盖供应商，不落文件）：
  `DAODIAN_LIVE=1 DAODIAN_BASE_URL=… DAODIAN_KEY=… DAODIAN_MODEL=… ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest*' -i`
- 收到了没有：监听连没连着、谁在听哪些 app：设置 →「通知管家」（深链 `--es com.abc.daodian.launch.ROUTE intake`）；进来的每一条变成了什么：通知管家 →「收到的通知」（深链 `intake/notices`）；通知使用权在设置 →「权限」（深链 `permissions`）。派活的旧路由 `reminder/relay/<id>` 只是壳，跳规则页。
- 记忆、后台整理：`chat.db` 的 `memories`、`tidy_runs`（成没成、看了哪几轮、摘要原文都在这张表）。闲下来那次整理是 WorkManager 10 分钟后跑，荣耀会拖。
- 每次发给模型的是什么（提示词版本、垫没垫背景、摘要盖到哪一轮、原样带了哪几轮、折掉几样、带了几张图片、多少字）：debug 包 `files/context_trace.txt`（助手：对话页和速记），`adb shell "run-as com.abc.daodian.debug cat files/context_trace.txt"`；后台的几个 agent 各一份 `files/trace_<id>.txt`（`trace_organizer.txt`、`trace_relay.txt`、`trace_tidy.txt`）
- 随话发的图片：发出去的在 debug 包 `files/chat_images/`（`adb shell "run-as com.abc.daodian.debug ls -la files/chat_images"`，文件名里带宽高），库里是 `chat_items.images`。模型收不收图不用装机也能先探一下：上面那条打真网关的命令把 `--tests` 换成 `'*LiveGatewayTest*picture*'`，它现画一张写着 4729 的图发过去
- 整理员每批喂了几样（通知、类别、商户记忆、记忆、最近流水各几条，只数个数）：debug 包 `files/organize_trace.txt`，同上的读法
- adb 往 `run-as … sh -c` 里传命令时，整条要再包一层双引号（`adb shell "run-as 包名 sh -c '…'"`），不然引号在 adb 这层被拆开，`rm` 之类的只拿到第一个词
- 不用手点就能在对话页说一句：`adb shell am start -n com.abc.daodian.debug/com.abc.daodian.MainActivity --es com.abc.daodian.launch.SAY "'英文句子'"`（app 开着也行，正忙就先放进输入框）

## 不要碰的假设

- **`reminder/scheduling/` 和 `reminder/data/` 是唯一不允许出错的部分**（DESIGN §05）。AlarmManager 里的排期是易失的，Room 是唯一真相，四个重排触发源缺一个都可能漏提醒。
- **界面整包可丢弃、可重画**（各模块的 `presentation/`、`assistant/conversation`、`shell`），改起来不用犹豫。
- **依赖规则**（DESIGN §2.2 那张表，`ArchitectureTest` 照着扫 import）：`assistant` 不 import 模块、模块之间不互相 import、模块只碰两个插口包（`assistant/plugin`、`shell/entry`）和 `module`、跨模块跳转只用路由；`runtime` 不碰 Android 和 app 里别的包；app 跑 agent 只走 `AgentRuntime`（循环、调模型的客户端是 runtime 的 `internal`，碰不到）。加功能先想它归哪个模块、要不要经接头；加模块 = `XxxModule`（+ 要的话 `XxxPlugin`、`XxxShell`）+ `Modules.kt` 一行，加后台 agent = `XxxAgent` + `XxxPrompt` + `Agents.kt` 一行。
- 换 Kotlin / AGP / Compose 版本前看 README「依赖版本」那条约束链，顺序不能反。
- `minSdk` 是 **33**（10-15 为 Android 13 的内测者从 34 退回来，决策 3.2）：14 才有的接口要写版本分支，再往下不去。

## 容易忘的决定

都是定过的，别再提一遍：

- openai-java 官方 SDK，不手写 HTTP 客户端；包体 35MB 是接受了的代价（决策 3.1）。
- 工具调用，不用 `response_format: json_object`（§6.4）。
- 历史客户端自己带，不用 `previous_response_id`（§6.1）。
- 写操作直接办、留痕，拿不准才出问卡；**授权条删了，不加回来**（§6.6，用户 09-24 又确认过一次）。
- 不用提示词管 Markdown 格式，模型写什么界面认什么（决策 6.4）。
- 画图只做静态 SVG，图上的数是模型抄的（§6.8）。
- 聊天里不震动（§6.5）；时间轴列表不加虚化 / 缩放（§08）。
- 对话里的旧回合不收起、不折叠（10-27 做过「还有 N 段」，用户说太奇怪，删了，§6.5）。长回复看不出重点靠排版分层（§6.7），不限制模型输出。
- 对话页说话是按住说、松手直接发、上滑取消，取消只留一行字；文字框是灰的，不用墨色实心块、不用朱砂（§8.3，用户 10-07 一样样挑过）。
- 随话发图：＋ 在输入条最右、有东西可发时换成发送（设计稿「甲」，**不是** ＋ 和发送并排）；一句最多 4 张；图只跟最近 3 轮发给模型；模型看不了图单独报一句，不挂「手动填一条」、印不变灰；配置页不测图（§6.10，用户 10-12 定的）。
- 每个 agent 的提示词写全在自己目录下，不拼别处的段落；模块只给工具和数据，不往助手的提示词里追加 —— 模块改了工具，回用它的 agent 的提示词里跟着改（§6.3 第 6 条，用户 10-15 定的）。
- 模块给助手的插口叫 `AssistantPlugin`（`XxxPlugin`），**不叫 skill**：agent 那边 skill 指按需读进上下文的一段说明（提示词），这个插口偏偏不给提示词；以后真要按需加载某个模块的规矩，那才叫 skill（§2.2，用户 10-16 定的）。
- 字体用系统泛型，没打包 Noto Serif SC —— 墨宋靠宋体挑大梁，真机上第一眼要看系统衬体的中文好不好看（§8.1）。
- 到点全屏页是独立的 `AlarmActivity`（§5.6）。
- 轻提醒（界面上就叫这个，**不叫「到点只发通知」**；开关底下两行小字解释它是什么，用户 10-11 定的）：只分两档、按条设、默认响铃，不加全局的「默认强度」；模型只认明说的，派活来的一律响铃；渠道单开（`gentle_v2`），不和当天事项共用；声音走通知音量再加一下震动，**不走闹钟音量**（静音时不该响）；不等人确认，送到了就算完，通知上没有「完成」（§5.6，用户 10-09 定的）。
- 记账：模型读懂，代码只收集和校验；流水原样发给网关不脱敏；改账只在对话里（§10）。
- 记忆、模块现状（记账的类别表）、摘要垫在历史最前面一条（user 角色 + 标记），不进 system；后台整理直接记、不问；不记某一条提醒、某一笔账，但认账用得上的说法（哪张卡是谁在用）要记（§6.9）。
- 整理员和对话模型不互相留言，账本就是通道：整理员的猜测写在那笔的 `ask` 上，你的规矩落成商户记忆或记忆；整理员不看对话历史（§10.1）。
- 标签默认只由你打（「整理员自己打标签」默认关，开了也只用已有的）；标签是账本页上唯一能直接改的，别的改账照旧回对话（§10.4、§10.8）。

## 踩过的坑

一行一条，细节在 DESIGN 对应那一节。

- **键盘 inset**：manifest 没写 `windowSoftInputMode` 时默认 adjustPan 把整个窗口往上推，再叠上 Compose 的 `imePadding()`，输入框飞到半空。现在钉 `adjustResize` + `enableEdgeToEdge()`，inset 只留 Compose 一个来源，底部用 `ime.union(navigationBars)`。第二次：键盘弹起时列表从底下被压矮、最新一句压在输入框后面 —— `ConversationScreen` 盯着视口高度，矮了多少往下滚多少。
- **「停」半天没反应**：阻塞 HTTP 打断不了（§6.2 坑 1）。
- **闲聊被当成建提醒**：提示词先分流（§6.3）。
- **同一句中文模型有时只回「明白」不调工具**（英文版才调），是模型 / 提示词的问题，不是界面。
- **`BYMONTHDAY=-1`**（月底）以前会让 `Rrule` 抛异常，已支持负数（§7.2）。
- **一步走了近 3 分钟**：`ResponsesClient` 没设超时（§6.2）。
- **记账漏抓**：实时回调会丢、监听会断而且断了没人知道；别用禁用组件那招重连（§10.2）。
- **转给别人被当成转移作废了**：提示词已改，作废撤不回（§10.3）。
- **别人转来的钱被整理员当成不是账**：放进了 ignore，没进收入。提示词 `organize-v8` 写明转账、红包、收款都是账，转进来的记 `IN` /「转账红包」（§10.3）。
- **储蓄卡的退款是「收入」**：整理员记成收入，对话里挂不上原笔（方向改不了）。现在 `update_expenses` 能改方向（§10.3、§10.6 第 15 条）。
- **记账总览切日月季年闪一下**：一切就扔掉上一份数据，先画成 0 再弹回来。现在新的读回来之前接着画上一份，一帧换掉（`LedgerOverviewScreen`）。
- **清账本要连对话一起清**，不然模型以为清单上的几笔已经处理过（§10.7 第 9 条）。
- **点快了进错页、连退两层**：切页动画那段时间旧页还接得到点击（库默认淡变 700ms，页面最外层的 `background` 不拦点击）。现在切页改成 180ms，当前页没到 `RESUMED` 之前在 NavHost 上面盖一层吞掉点击（§8.1）。
- **键盘收起时整屏消息抖动变形、模型调工具那一下整屏白半秒**：都是 `animateItem` 的挪位动画 —— 库把滚动以外的位移全当挪位，贴底 `scrollBy(1_000_000f)` 碰上内容变矮时还会被当成挪了一百万像素。现在挪位只给条目进出那一帧，贴底按差多少滚多少（§6.5）。录屏逐帧看：`screenrecord` 后用 ffmpeg `signalstats` 扫聊天区的 `YMIN`，整片纸色（> 200）就是白屏帧。
- **点发送那一下列表忽上忽下**：气泡加在输入框后面，贴底先把列表推上去，键盘收起又整页落下来。现在发出去时键盘还开着就等它收完再跟（§6.5）。
- **从外面交话进对话页时整屏叠影抖一下**：`toChat()` 以前连对话页一起弹掉再新建，新旧两页滚动位置不同、叠着淡变 180ms（速记交话、对账通知「现在」、adb 的 `SAY` 都走这里）。现在只弹掉上面的页，对话页还是原来那个。
- **从外面回对话页时叠了第二个对话页**（10-21「拍一张」拍回来的图不进输入框才发现）：已经停在对话页时 `popBackStack(HOME, false)` 一页没弹、返回 false，`toAssistant()` 又 navigate 一个叠上去，相机是从底下那页开的。现在停在对话页就不动。验法：从外面进来后按返回，一下就该回到桌面。
- **切深色 / 转屏后，速记交过来的话又发了一遍**：Activity 重建时 `onCreate` 又读了一遍旧 intent。现在只在 `savedInstanceState == null` 时读。
- **打开 app 先闪一下空对话**：记录读回来之前不画空状态、开屏按住（§6.1）。
- **轻提醒到点弹了但没声音**：不是渠道的事，是那台手机的通知音量为 0（铃声、闹钟音量都开着，免打扰没开）。`adb shell dumpsys audio` 里看 `STREAM_NOTIFICATION` 那段的 `2 (speaker): 0`；免打扰看 `settings get global zen_mode`（§5.6）。
- **发版停在「非预期的安装包下载地址」**：蒲公英把新包的下载跳到了脚本名单以外的主机（0.1.5 那次是 `cos3.pgyer.com`）。这时包已经传上蒲公英，`latest.json` 还没切，装着旧版的人不受影响。先看清跳去的主机确实是蒲公英的，加进 `scripts/pgyer_release.py` 的 `DOWNLOAD_HOSTS`，再用**同一句**更新说明重跑 —— 接着上次的进度走，不会重复上传。
- **09-30 之前的版本升上来，记账先一条都不收**：听哪些 app 改成全由你勾、默认一个不听，装完去设置 →「权限与监听」→ 谁在听 → 记账，把支付宝、招商银行、掌上生活、建设银行勾上（§10.2）。
- **留着等删的壳都记在 [docs/cleanup.md](docs/cleanup.md)**：派活的两个旧路由、`TidyWorker` 空壳、速记的 `activity-alias`，定在 0.2.2 删。加了新的壳在那里记一行。
- **挪包之后装新包要连数据卸载**（09-24 那次）：通知使用权、小组件、闹钟、WorkManager 都按全类名记（§2.2）。内测之后不能再这么干：要挪按全类名记的类，旧名字留下（Activity 留 `activity-alias`、Worker 留空壳子类），10-15 挪速记和记忆整理就是这么做的（`QuickAddActivity` 在 manifest 留了旧名字的 alias，`TidyWorker` 在 `agent/memory/tidy/` 留了空壳子类）。存在手机上的名字也不改：`chat.db`、路由 `"chat"`、`chat-tidy-idle` 这些，代码里改叫 conversation 了也照旧（`ConversationDatabase` 改了类名，schemas 目录跟着改，导出的 json 一样）。
- **09-30 拆出通知监听层**：监听组件改成 `intake/NoticeListenerService`，覆盖安装后要去「权限与监听」重新开一次通知使用权；听哪些 app 挪进了监听层，记账、派活的都要重新勾（旧的勾选不迁移）。别的没挪，不用卸载（§2.3）。
- **10-02 之前的版本升上来**：只听一个人时设的「听谁」「暗号」自动成了派活名单上的第一个人，不用重设；以前「试一句」留下的记录还在那个人的页上，标着「试的」。
