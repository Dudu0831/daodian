# 到点 (daodian)

用一句话办事的 Android app：建提醒、记账、查账。

> 「下周三下午三点提醒我交房租」 → 到点响。
> 「这个月钱花在哪，画个图」 → 一句结论 + 一张图。

个人自用：不上架、不做云同步、只装自己的手机（荣耀 MagicOS / Android 15，没有 Google Play 服务）。

## 能做什么

- **对话**：说一句话，模型直接办（建提醒、记账、改账、查账），对话里留一道「痕」，点了去那条记录；拿不准时出一张「问卡」，先猜好答案让你点。回答按 Markdown 画，要看占比、走势时画成图。
- **提醒**：精确到点的强提醒（响铃 + 锁屏全屏 + 稍后）、重复规则、只说了哪天的「当天事项」。断网、没配模型也照响；不用模型也能手动建。
- **记账**：听支付宝、招行、掌上生活、建行的支付通知，后台让模型整理成流水、归类；拿不准的每晚在对话里问一次。账本页只读：总览 → 类别 → 一笔，还有「抓到的通知」页看原文。
- **桌面小组件**：下一条提醒 + 一枚墨印，点墨印在桌面上说一句就记好（本地语音识别，不进 app）。

## 核心取舍

**模型只负责读懂，不负责可靠。** 把一句话变成一条提醒、把一条付款通知读成一笔账，是模型的活，允许失败；到点叫醒你、把通知一条不漏地存下来、算合计，是代码的活，不能失败。

```text
提醒
  「下周三下午三点提醒我交房租」
    ┆  模型读懂，调 create_reminder
    ▼
  校验闸门：解析不了、在过去的拦下，回去问你
    │
    ▼
  reminder.db：唯一真相 ◄── 手动建 / 改（逃生舱，不经过模型）
    │  AlarmManager.setAlarmClock；排期会掉，重启、更新后按库重排
    ▼
  到点：响铃 · 通知 · 锁屏全屏页

记账
  支付宝、银行的付款通知
    │  原样存下；实时回调会漏，连上、解锁、整理前再扫通知栏
    ▼
  raw_notification：原文，永不删
    ┆  每 3 小时，模型整理成流水：合并同一笔的几条、归类
    ▼
  护栏：金额要在原文里找得到……不过的那笔退回待整理
    │
    ▼
  ledger.db：流水 ──► 账本页；对话里问「花了多少」，数是 SQL 算的，模型只抄
    └─ 没把握的 ──► 每晚 21:30 在对话里问你

  ┆ 要网络，允许失败：当场看得见，或者下一轮再整理
  │ 全在本地：断网、删 key、供应商倒闭都照常
```

网络只参与「读懂」这一步。提醒落进 `reminder.db` 之后，触发全靠 `AlarmManager` 和 `BroadcastReceiver`，已排期的照响；付款通知先原样落库再交给模型，模型挂了原文还在，恢复后下一轮补上。模型的产出落库前都过一道代码写的闸（提醒是校验闸门，账是护栏），把「静默写错」变成「当场问你」或「退回重来」。

## 文档

| 文件 | 写什么 |
|---|---|
| [DESIGN.md](DESIGN.md) | 为什么是这样、改的时候要守的规矩。代码注释里的 `§5.3`、`决策 8.4` 都指它 |
| [CLAUDE.md](CLAUDE.md) | 给 Claude Code 会话的操作手册：现状（哪些真机验过）、怎么验证、踩过的坑 |

界面视觉稿（墨宋，九块画板）：<https://claude.ai/code/artifact/c7073888-2022-4a7f-bb22-4ae61961d0a1>

## 目录结构

两个 Gradle 模块：`:runtime`（跑 agent 的那一层，纯 Kotlin）和 `:app`（其余全部）。`:app` 里最上面是壳和助手，中间三个模块（提醒、记账、通知监听），大家跑模型都用 `runtime`，最底下是地基。依赖规则见 DESIGN.md §2.2（`runtime` 由 Gradle 模块边界管，其余由 `ArchitectureTest` 守着）。

```text
runtime/src/main/java/com/abc/daodian/runtime/    :runtime —— 跑 agent 的那一层，纯 Kotlin，不认识 Android 和 :app
├── engine/           ReAct 循环、Session、Item / Turn、事件
├── tool/             工具接口、注册表
├── ask/              问卡协议、ask_user
├── context/          每步喂哪些轮、折叠、压缩、垫在前面的那段
├── model/            调一次模型（流式 / 回退 / 停）、供应商配置的样子、健康状态、「测一下」
├── memory/           记忆的数据和规矩、垫在前面那段怎么拼
├── Agent.kt          AgentInfo（花名册上的一行）、Prompt、Agent、Input、RunResult
└── AgentRuntime.kt   跑一个 agent（run / runOnce）、后台谁在跑、按名字的锁

app/src/main/java/com/abc/daodian/                :app
├── DaodianApp.kt / MainActivity.kt   装配、唯一的宿主 Activity
├── Modules.kt        模块清单（提醒、记账、通知监听）和要听通知的订阅者
├── Agents.kt         花名册：系统里一共这几个 agent（助手、记账整理员、派活、记忆整理员）
├── assistant/        助手：它的 agent 和提示词、对话页、桌面速记、记忆。代码不认识提醒和账，经插口拿
│   ├── AssistantAgent.kt / AssistantPrompt.kt / AssistantRoutes.kt
│   ├── skill/          插口：AssistantSkill（模块给助手的工具、现状、痕、app 发起的一轮）、痕、CallHistory
│   ├── conversation/   对话页（回合、问卡、痕、Markdown、画图、随话发的图片）、chat.db
│   ├── quick/          桌面速记
│   ├── memory/         记忆、垫在历史前面的那一段、记忆整理员（DESIGN.md §6.9）
│   ├── provider/       模型配置存在哪（DataStore）
│   └── voice/          本地语音识别
├── shell/            宿主壳：导航、抽屉、设置页、权限页、模型配置页的框
│   ├── entry/          插口：ShellEntry（模块给壳的页面、抽屉卡、设置行、体检、诊断）、AppNav
│   └── diagnostics/    崩溃记录、导出诊断（DESIGN.md §11.3）
├── module/           模块接头 Module：给助手的、给壳的、冷启动
├── reminder/         提醒。断网、不经过模型也要能用。ReminderModule.kt 是接头
│   ├── scheduling/     调度内核 —— 唯一不允许出错的部分：排期、到点、重排、巡检、当天事项
│   ├── data/           reminder.db：提醒 + 投递日志
│   ├── domain/         计划、校验闸门、RRULE 子集
│   ├── application/    Reminders：所有写操作的唯一入口
│   ├── delivery/       通知、通知按钮、权限体检
│   ├── assistant/      给助手的（ReminderSkill）
│   ├── tools/          create_reminder、痕
│   ├── widget/         桌面小组件
│   ├── relay/          派活：订阅通知，名单上的人在微信里说的话交给派活 agent 建成提醒
│   └── presentation/   给壳的（ReminderShell）、列表、编辑（逃生舱）、到点全屏页、投递日志、提醒设置页（含派活名单）、派活某个人的那一页
├── ledger/           记账。LedgerModule.kt 是接头
│   ├── capture/        订阅通知：勾了的 app 的通知原样存进 raw_notification
│   ├── organize/       记账整理员（后台 agent）和它的提示词
│   ├── reconciliation/ 每晚对账
│   ├── domain/         流水、类别、护栏
│   ├── data/           ledger.db
│   ├── assistant/      给助手的（LedgerSkill）
│   ├── tools/          查账 / 记账 / 改账 / 加类别、痕
│   └── presentation/   给壳的（LedgerShell）、总览、类别、一笔、抓取页
├── intake/           通知监听。全 app 唯一的监听，按各模块勾的 app 分通知；只抓、只分，不存、不读懂（DESIGN.md §2.3）
│   ├── data/           每个订阅者听哪些 app
│   └── presentation/   给壳的（IntakeShell）、「权限与监听」页里通知监听那几组、通用的勾 app 页
├── update/           检查更新（内测）：查新版、下载、交给系统安装器。不依赖助手和任何模块（DESIGN.md §11.2）
├── shared/           地基：墨宋色板与字体、动效、手绘图标、通用组件、人话时间、装了哪些 app、Launch 路由
└── agent/            只剩一个旧全类名的空壳（TidyWorker），过两个版本删
```

## 构建

| | 版本 | 备注 |
|---|---|---|
| JDK | **21** | AGP 8.7 不支持 JDK 8，也不支持 24+ |
| Gradle | 用仓库自带的 `./gradlew` | 别用系统装的 |
| Android SDK | platform 35 + build-tools 35.0.0 + platform-tools | |

```bash
./gradlew :runtime:test :app:testDebugUnitTest   # JVM 单测（两个模块各一份）
./gradlew :app:assembleDebug       # 打包
./gradlew :app:installDebug        # 装到连着的手机
```

**本机配置**

- `local.properties`（gitignored）：`sdk.dir=/你的/android-sdk/路径`
- `gradle.properties` 里钉了一行 JDK 21 的绝对路径 `org.gradle.java.home=…`，防止 Gradle 挑到别的 JDK。**换机器要改这一行**（或挪到 `~/.gradle/gradle.properties`）。

**模型服务**：在 app 的配置页填（网关地址、key、模型，网关要支持 Responses API `POST /responses`）。
`secrets.properties`（根目录，gitignored，模板 `secrets.properties.example`）只是种子：app 里一个字段都没存过时才用它。缺这个文件也能编译安装，AI 功能在界面上报「还没配置」，不影响手动建提醒。`http://` 明文网关也能连（Manifest 里开了 `usesCleartextTraffic`）。

**踩过的坑**（用 Homebrew 从零配工具链时）

1. `brew install gradle` 会顺带装一个新版 openjdk，Gradle 可能挑中它，而 AGP 8.7 不支持。必须显式指定 JDK 21。
2. 系统的 `gradle` 和仓库 wrapper 是两回事，Homebrew 装的 9.x 跟 AGP 8.7.3 不兼容。永远用 `./gradlew`。
3. 必须显式钉 `buildToolsVersion`：AGP 8.7.3 默认去找 build-tools 34.0.0，它的下载器读不懂新版 cmdline-tools 的 v4 仓库 XML，会以 `Failed to download package` 挂掉。构建时的 `SDK XML version 4` 警告是同源症状，不影响结果。
4. IDEA 要装 Android 插件、Gradle JVM 手动指到 JDK 21；它的 Android 插件常比 Android Studio 落后，sync 不顺就装 Android Studio（可以共用 SDK 目录）。

**依赖版本**：钉的是一批已知互相兼容的版本（AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01）。先要一个绿的构建，再谈升级。升级顺着这条约束链走，顺序不能反：

```
IDE 的 Android 插件支持的 AGP 上限
  → AGP → 最低 Gradle 版本 → Kotlin → KSP（必须与 Kotlin 版本前缀精确匹配）
                                          → Compose 编译器（Kotlin 2.0 起 = Kotlin 版本本身）
```

`:runtime` 用的 Kotlin JVM 插件和 `:app` 的是同一个插件包、同一个 `kotlin` 版本，跟着这条链一起升。`libs.versions.toml` 里的协程、Jackson 钉的是拆模块那天 `:app` 实际解析出来的版本（拆前拆后包里的东西一样），升 openai-java 时一起看。

## 手机上要设的（重要）

国产 ROM 的后台限制是这个项目**最大的技术风险**，代码解决不了，只能手动配一次（MagicOS 菜单名各版本有出入）：

- **应用启动管理** → 本 app → 关掉「自动管理」→ 三个开关全开（自启动 / 关联启动 / 后台活动）。**最关键，而且没有公开 API 能检测。**
- **电池** → 取消对本 app 的省电策略
- **最近任务** → 下拉本 app 的卡片 → 加锁
- 设置 →「权限与监听」的体检会查另外五项有 API 可查的（精确闹钟、通知、渠道重要性、电池优化白名单、全屏通知），缺的直接给「去开」；记账、派活要的通知使用权也在这一页。

**验收是放置测试，不是「点一下能响」**：排 20 条覆盖未来 48 小时（含凌晨）的提醒，手机正常揣兜里用、别刻意打开 app，48 小时后看投递日志 —— 漂移全部 < 30 秒、来源全是 `ALARM` 才算过。细节见 DESIGN.md §9.3。

> 荣耀 / 华为 ROM 默认屏蔽第三方 app 的 logcat，看不到日志不代表代码没跑。关键证据写在库里：app 内的「投递日志」页、`fire_log` 表。

## 许可

[Apache License 2.0](LICENSE)
