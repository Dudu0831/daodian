# 到点 (daodian)

用一句话办事的 Android app：建提醒、记账、查账。

> 「下周三下午三点提醒我交房租」 → 到点响。
> 「这个月钱花在哪，画个图」 → 一句结论 + 一张图。

个人自用：不上架、不做云同步、只装自己的手机（荣耀 MagicOS / Android 15，没有 Google Play 服务）。

## 能做什么

- **对话**：说一句话，模型直接办（建提醒、记账、改账、查账），对话里留一道「痕」，点了去那条记录；拿不准时出一张「问卡」，先猜好答案让你点。回答按 Markdown 画，要看占比、走势时画成图。
- **提醒**：精确到点的强提醒（响铃 + 锁屏全屏 + 稍后）、重复规则、只说了哪天的「当天事项」。断网、没配模型也照响；不用模型也能手动建。
- **记账**：听支付宝、招行、掌上生活、建行的支付通知，后台让模型整理成流水、归类；拿不准的每晚在对话里问一次。账本页只读：总览 → 类别 → 一笔，还有「抓到的通知」页看原文、手动补抓。
- **桌面小组件**：下一条提醒 + 一枚墨印，点墨印在桌面上说一句就记好（本地语音识别，不进 app）。

## 核心取舍

**AI 只负责把一句话翻译成一条记录，绝不负责在正确的时间叫醒你。**

```
                    ┌─────── 本地可靠区 · 飞行模式下完整工作 ───────┐
                    │                                              │
你说的话 ──► LLM 解析 ──┼─► Room ──► AlarmManager ──► 到点 ──► 通知·响铃│
    │      (允许失败)   │  (唯一真相)                                │
    │                  │     ▲                                      │
    └─► 手动添加/编辑 ───┼─────┘                                      │
        (逃生舱)        └──────────────────────────────────────────┘
```

网络只参与「把一句话变成一条记录」。记录一旦落进 Room，触发全靠 `AlarmManager` 和 `BroadcastReceiver` —— 拔掉网络、删掉 key、供应商倒闭，已排期的提醒照响。

## 文档

| 文件 | 写什么 |
|---|---|
| [DESIGN.md](DESIGN.md) | 为什么是这样、改的时候要守的规矩。代码注释里的 `§5.3`、`决策 8.4` 都指它 |
| [CLAUDE.md](CLAUDE.md) | 给 Claude Code 会话的操作手册：现状（哪些真机验过）、怎么验证、踩过的坑 |
| [TEST_PLAN.md](TEST_PLAN.md) | `restructure-modules` 分支装机后要测的清单（测完合并就删） |

界面视觉稿（墨宋，九块画板）：<https://claude.ai/code/artifact/c7073888-2022-4a7f-bb22-4ae61961d0a1>

## 目录结构

一个 Gradle 模块，包层面分三个模块 + 地基。依赖规则见 DESIGN.md §2.2。

```text
app/src/main/java/com/abc/daodian/
├── DaodianApp.kt / MainActivity.kt / Features.kt   装配：模块清单、唯一的宿主 Activity
├── agent/            智能交互。只认识 Feature / FeatureUi 接头，不知道提醒和账是什么
│   ├── engine/         ReAct 循环、Session、工具接口、ask_user、上下文裁剪、后台 agent 与锁
│   ├── model/          调一次模型（流式 / 回退 / 停）；provider/ 供应商配置、「测一下」
│   ├── feature/        接头：Feature、痕、体检项、注册表
│   ├── prompt/         基础提示词
│   ├── conversation/   对话页（回合、问卡、痕、Markdown、画图）、ChatAgent、chat.db
│   ├── entry/quick/    桌面速记
│   ├── voice/          本地语音识别
│   └── shell/          导航、抽屉、设置页、模型配置页的框
├── reminder/         提醒。断网、不经过模型也要能用
│   ├── scheduling/     调度内核 —— 唯一不允许出错的部分：排期、到点、重排、巡检、当天事项
│   ├── data/           reminder.db：提醒 + 投递日志
│   ├── domain/         计划、校验闸门、RRULE 子集
│   ├── application/    Reminders：所有写操作的唯一入口
│   ├── delivery/       通知、通知按钮、权限体检
│   ├── tools/          create_reminder、提醒那段提示词、痕
│   ├── widget/         桌面小组件
│   └── presentation/   列表、编辑（逃生舱）、到点全屏页、投递日志
├── ledger/           记账
│   ├── capture/        通知监听（PaySampler）、听哪几家、监听状态、手动补抓
│   ├── organize/       后台整理 agent
│   ├── reconciliation/ 每晚对账
│   ├── domain/         流水、类别、护栏
│   ├── data/           ledger.db
│   ├── tools/          查账 / 记账 / 改账 / 加类别、记账提示词、痕
│   └── presentation/   总览、类别、一笔、抓取页
└── shared/           地基：墨宋色板与字体、动效、手绘图标、通用组件、人话时间、Launch 路由
```

## 构建

| | 版本 | 备注 |
|---|---|---|
| JDK | **21** | AGP 8.7 不支持 JDK 8，也不支持 24+ |
| Gradle | 用仓库自带的 `./gradlew` | 别用系统装的 |
| Android SDK | platform 35 + build-tools 35.0.0 + platform-tools | |

```bash
./gradlew :app:testDebugUnitTest   # JVM 单测
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

## 手机上要设的（重要）

国产 ROM 的后台限制是这个项目**最大的技术风险**，代码解决不了，只能手动配一次（MagicOS 菜单名各版本有出入）：

- **应用启动管理** → 本 app → 关掉「自动管理」→ 三个开关全开（自启动 / 关联启动 / 后台活动）。**最关键，而且没有公开 API 能检测。**
- **电池** → 取消对本 app 的省电策略
- **最近任务** → 下拉本 app 的卡片 → 加锁
- 设置页的体检会查另外五项有 API 可查的（精确闹钟、通知、渠道重要性、电池优化白名单、全屏通知），缺的直接给「去开」；记账要的通知使用权在设置页记账那一组。

**验收是放置测试，不是「点一下能响」**：排 20 条覆盖未来 48 小时（含凌晨）的提醒，手机正常揣兜里用、别刻意打开 app，48 小时后看投递日志 —— 漂移全部 < 30 秒、来源全是 `ALARM` 才算过。细节见 DESIGN.md §9.3。

> 荣耀 / 华为 ROM 默认屏蔽第三方 app 的 logcat，看不到日志不代表代码没跑。关键证据写在库里：app 内的「投递日志」页、`fire_log` 表。

## 许可

[Apache License 2.0](LICENSE)
