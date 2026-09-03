# 到点 (daodian)

用一句话建提醒的 Android app。

> 「下周三下午三点提醒我交房租」 → 到点响。

个人自用项目：不上架、不做云同步、只装自己的手机。目标设备是荣耀 MagicOS / Android 15。

---

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

网络只参与「把一句话变成一条记录」这一步。记录一旦落进 Room，触发链路完全由 `AlarmManager` 和
`BroadcastReceiver` 承担 —— 拔掉网络、删掉 API key、供应商倒闭，已排期的提醒照响。

完整设计与决策记录见 **[DESIGN.md](DESIGN.md)**。代码注释里的 `§5.3`、`§9.2` 之类引用都指向那份文档。
面向 Claude Code 会话本身的操作手册在 **[CLAUDE.md](CLAUDE.md)**。

界面视觉稿（Claude Design 画布，八块对话/卡片/到点全屏状态）：
<https://claude.ai/code/artifact/327af1d8-4987-4f13-bc25-536e9b5f10d9>

---

## 进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M1** | 调度内核，完全不接 AI | 代码完成，真机冒烟测试通过（零漂移），**48 小时放置测试未做** |
| **M2** | 接 AI 解析 | 代码完成，真机测试通过 —— 走**工具调用**而不是 JSON 输出模式，见下 |
| **M3** | 正式界面 + 重复规则/时区 | Compose 界面已实现（对话/卡片/列表/编辑/设置/到点全屏），**端到端真机验证未完成**；RRULE 子集求值见 §7.2，COUNT/UNTIL 的持久化计数还没补 |
| M4 | 打磨 | 未开始 |

M1 的出口条件不是「点一下能响」，是 48 小时放置测试 —— 见下方[验收](#m1-验收放置测试)。

---

## AI 解析：工具调用，不是 JSON 输出

设计文档 §6 写的是「让模型输出 JSON」，实测下来换成了**工具调用**（`create_reminder` 函数）：

- `response_format: json_object` 那档测试时模型会自己发明字段名（`{summary, details:{...}}`），对不上我们要的 schema
- 工具调用把参数 schema 交给服务端强制，模型只能按 `ReminderTool` 定义的字段回来，不会跑偏
- 反问之后的多轮对话是**客户端拼文本**（`ai/ChatTurn.kt`），不依赖 `previous_response_id` 这类服务端会话状态 —— 第三方 OpenAI 兼容接口大概率没实现那个

`ai/OpenAiCompatParser.kt`（JSON 输出模式的实现）和 `ai/PlanSchema.kt` 已经删除，`ToolCallParser` 是唯一在用的解析器。

---

## 目录结构

```
app/src/main/java/com/abc/daodian/
├── data/          Room：Reminder + FireLog + DAO
├── schedule/      调度内核 —— 项目里唯一不允许出错的部分
│   ├── Rescheduler.kt              排期 / 取消 / 全量重建 / 时区重算
│   ├── AlarmReceiver.kt            到点广播入口
│   ├── FireHandler.kt              「响了之后做什么」，正常触发和补发共用
│   ├── RescheduleReceiver.kt       开机 / 应用更新 / 时区变更
│   ├── NotificationActionReceiver  完成 / 稍后 10 分钟
│   └── SweepWorker.kt              6h 兜底巡检（第二道网，不是保险）
├── notify/        通知渠道与构建（到点全屏页从这里的 fullScreenIntent 拉起）
├── recur/         RRULE 子集求值
├── ai/            解析层 —— ToolCallParser + ReminderTool（schema）+ ChatTurn（多轮历史）
└── ui/
    ├── theme/       色板 + 字体角色（DaodianColors / DaodianType）
    ├── chat/        对话页 —— app 主屏，含五种状态的组件
    ├── alarm/       到点全屏页（独立 AlarmActivity，不是 MainActivity）
    ├── list/        提醒列表
    ├── edit/        手动建 / 改一条提醒 —— 逃生舱，必须能脱离 AI 用
    ├── settings/     设置 + 权限体检 + 投递日志
    └── common/      图标（手绘 Canvas，没引入 material-icons-extended）、格式化、顶栏
```

`data/` 和 `schedule/` 是唯一不允许出错的部分。`ui/` 可以整包重画。

---

## 构建

### 需要什么

| | 版本 | 备注 |
|---|---|---|
| JDK | **21** | AGP 8.7 不支持 JDK 8，也不支持 24+ |
| Gradle | 8.11.1 | 用仓库自带的 `./gradlew`，别用系统装的 |
| Android SDK | platform 35 + build-tools 35.0.0 + platform-tools | |

```bash
./gradlew :app:assembleDebug   # 打包
./gradlew :app:installDebug    # 装到连着的手机
```

看运行日志（关键路径都打了 tag）：

```bash
adb logcat -s Daodian/Alarm Daodian/Resched Daodian/Sweep Daodian/Fire
```

> **荣耀/华为 ROM 默认屏蔽第三方 app 的 logcat 输出**，上面这条命令在这类设备上会是空的，
> 即使代码确实执行了。需要用拨号盘工程菜单打开日志开关才能看到。
>
> 这也是为什么关键证据写在 Room 的 `fire_log` 表里而不是 logcat —— app 内的「投递日志」页
> 在任何 ROM 上都能用。

### 本机配置

`local.properties`（已 gitignore）里写 SDK 路径：

```properties
sdk.dir=/你的/android-sdk/路径
```

`gradle.properties` 里有一行 **硬编码的 JDK 21 绝对路径**：

```properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

这是为了防止 Gradle 挑到机器上其它版本的 JDK。**换机器要改这一行。** 更干净的做法是把它挪到用户级的
`~/.gradle/gradle.properties`（不进版本库），或者改用 Gradle toolchain 自动解析。

### AI 功能需要的配置

`secrets.properties`（项目根目录，已 gitignore，模板见 `secrets.properties.example`）：

```properties
LLM_BASE_URL=https://api.openai.com/v1     # 写到 /v1 为止
LLM_API_KEY=
LLM_MODEL=gpt-4o-mini
LLM_API_STYLE=RESPONSES                     # 目前 ToolCallParser 只实现了 RESPONSES 这档
LLM_JSON_MODE=JSON_OBJECT                   # 工具调用路径下这个字段目前不生效，留着给 fallback 用
```

缺这个文件也能正常编译安装 —— 缺省是空字符串，AI 相关功能会在界面上报「还没配置供应商」，
不影响手动建提醒那条路。**如果服务地址是 `http://` 明文**（不是 `https://`），已经在 Manifest 里加了
`usesCleartextTraffic="true"`，否则 Android 9+ 会直接拦掉这个连接。

### 几个踩过的坑

用 Homebrew 从零配 Android 工具链时遇到的，都不是显然的：

1. **`brew install gradle` 会顺带装一个新版 openjdk**，Gradle 可能挑中它，而 AGP 8.7 不支持。必须显式指定 JDK 21。
2. **系统的 `gradle` 和仓库 wrapper 是两回事。** Homebrew 装的可能是 9.x，跟 AGP 8.7.3 不兼容。永远用 `./gradlew`。
3. **必须显式钉 `buildToolsVersion`。** AGP 8.7.3 默认去找 build-tools 34.0.0，而它内置的下载器读不懂新版
   cmdline-tools 的 v4 仓库 XML，会以 `Failed to download package` 挂掉。指到已安装的版本就绕过了整个自动下载路径。
4. 构建时的 `SDK XML version 4` 警告是上面第 3 条的同源症状，不影响构建结果。

### IDEA / Android Studio

IDEA 需要装 Android 插件才能打开本工程，且 Gradle JVM 要手动指到 JDK 21
（Settings → Build Tools → Gradle → Gradle JVM），Use Gradle from 选 `gradle-wrapper.properties file`。

IDEA 的 Android 插件通常比 Android Studio 落后一截、对 AGP 的支持范围更窄。sync 一直不顺的话装个
Android Studio 会省事，两者可以共用同一个 SDK 目录。

### 关于依赖版本

钉的是一批已知互相兼容的版本（AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01）。**先要一个绿的构建，
再谈升级** —— 猜版本号换来的是解析不到的坐标和排查不完的兼容问题。

升级时注意这条约束链，顺序不能反：

```
IDE 的 Android 插件支持的 AGP 上限
  → AGP → 最低 Gradle 版本 → Kotlin → KSP（必须与 Kotlin 版本前缀精确匹配）
                                          → Compose 编译器（Kotlin 2.0 起 = Kotlin 版本本身）
```

---

## 设备配置（重要）

国产 ROM 的后台限制是这个项目**最大的技术风险**，代码解决不了，只能手动配一次：

- **应用启动管理** → 找到本 app → 关掉「自动管理」→ 三个开关全开
  （允许自启动 / 允许关联启动 / 允许后台活动）——  **最关键，且没有公开 API 可以检测**
- **电池** → 取消对本 app 的省电策略
- **最近任务**界面 → 下拉本 app 的卡片 → 加锁

app 内的「体检」页会检测另外五项有 API 可查的（精确闹钟、通知权限、渠道重要性、电池优化白名单、全屏 intent），
不合格的直接给跳转按钮。

---

## 已验证

真机冒烟测试（荣耀 LGE-AN10 / Android 15 / API 35）：

| 项 | 结果 |
|---|---|
| `USE_EXACT_ALARM` | 安装即授予，无任何运行时提示 ✓ |
| `setAlarmClock()` 进入系统 | `dumpsys alarm` 里有 `Alarm clock:` 段、`window=0`、`exactAllowReason=policy_permission` ✓ |
| Doze 唤醒特权 | 系统把它列为 `Next wake from idle` ✓ |
| 端到端触发 | 应响 → 实响 **漂移 +0s**，source = `ALARM` ✓ |
| 通知 | `category=alarm`、full-screen intent、两个 action 全部到位 ✓ |

同一份 `dumpsys` 里这台机器的全局统计是 `delayed alarms: 903, max delay +4m1s` ——
ROM 确实在大规模延迟别的 app 的闹钟，我们的没进那个队列。

**已知 ROM 行为**：MagicOS 会把通知渠道从请求的 `IMPORTANCE_HIGH`(4) 静默降到 `DEFAULT`(3)
（`mUserLockedFields=0`，不是用户改的）。后果是有声音但不弹横幅，目前靠 full-screen intent 兜着。
「体检」页会把这种情况标红。

冒烟测试只证明链路是通的，**不能替代下面的放置测试** —— 前者在插着 USB、屏幕亮着的情况下跑，
后者才检验深度 Doze 下的表现。

**M2（AI 解析）真机测试**：`ToolCallParser` 在真机上验证过 —— openai-java 编译、dex、运行时、
工具调用全部走通（`java/net/http` 引用为 0，Android 上不会因为缺 `HttpClient` 而崩），模型正确调用
`create_reminder`，字段名和时间推算都对，反问/失败两条路径也各自触发过一次。

**M3（正式界面）**：八块 Compose 屏幕编译通过、lint 干净，装机冷启动截图确认深色主题跟随系统、
对话页空状态渲染正常、无崩溃。**"点一条示例句 → 解析中 → 卡片"这条完整链路的真机验证还没跑完**，
是下一次接手时要做的第一件事。

---

## M1 验收：放置测试

1. 装好，进「体检」页把五项全弄绿
2. 按上面手动设好 ROM 的应用启动管理
3. 「提醒」页点「放置测试 20 条 / 48h」
4. **手机揣兜里正常用，别刻意打开这个 app**
5. 48 小时后看「投递日志」

**通过标准：20 条漂移全部 < 30s，且 source 全是 `ALARM`。**

出现任何一条 `SWEEP`，说明主闹钟路径正在被 ROM 掐掉、兜底巡检在替它干活 —— 回第 2 步重新检查配置。

---

## 许可

[Apache License 2.0](LICENSE)
