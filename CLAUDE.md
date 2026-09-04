# 到点 (daodian)

用一句话建提醒的 Android app，个人自用、不上架。**先读 [DESIGN.md](DESIGN.md)** —— 那是权威的架构和决策记录，代码注释里的 `§5.3` `§9.2` 之类都指向它。这份文件是给 Claude Code 会话本身看的操作手册，两份不重复的内容互相补。

## 现状（2026-09，下面这行过时了就更新它）

- **M1 调度内核**：代码完成，真机冒烟测试通过（零漂移），**48 小时放置测试没跑过**——这是唯一还没拿到的硬证据。
- **M2 AI 解析**：用的是**工具调用**（`ToolCallParser` + `ReminderTool`），不是让模型输出 JSON。真机测试成功：模型正确调用 `create_reminder`，字段名、时间推算都对。多轮对话（反问后接得上）已实现（`ChatTurn` 客户端拼历史），**还没在新 UI 上做端到端真机验证**。
- **M3 UI**：八块 Compose 屏幕（对话/卡片/到点全屏/列表/编辑/设置/日志）全部写完、编译通过、lint 干净。
- **视觉改版（墨宋）**：整套 UI 按新视觉稿重画完，规范见 DESIGN.md §8.1。真机确认过：空状态、对话、解析骨架、卡片、收起态、设置体检页（截图），到点全屏页（用户肉眼在锁屏上看到并点了「完成」，我没截到图）。
- **"喝水"全链路真机跑通了**（2026-09-04）：点例句 → 模型调 `create_reminder` → 落库排期 → `dumpsys alarm` 有闹钟 → 10:03:49.259 准点响，漂移 259ms，通知 `not_intercepted`。
- **闲聊被当成建提醒**（已修）：`TOOL_SYSTEM` 原来开头就把用户每句话都当建提醒请求，加上 `historyOf()` 把已建卡片整条丢掉、模型看到一串"没人应的请求"，于是每句话都弹卡。现在提示词先分流（闲聊/反问/建提醒），历史里补一句中文回执。真机复验："hello"→ 文字回复，"thanks"→"不客气！"，全程只排了一个闹钟。
- **桌面小组件**（2026-09-04 新增）：RemoteViews 实现，规范和三条改动规矩见 DESIGN.md §8.2。真机上 provider 已注册（`dumpsys appwidget` 里 `min=(46081x28161) updatePeriodMillis=1800000`，字段都解析出来了），深链也验过（`am start --es com.abc.daodian.widget.TARGET new/list` 分别落到新建页和列表页）。**还差最后一步硬证据：把它拖到桌面上看真实渲染** —— 桌面小组件没法用 adb 绑定（`cmd appwidget` 在这台 ROM 上不存在），只能手动长按桌面添加。
- **全屏页在锁屏上确实会弹**（2026-09-04 关屏实测，用户肉眼确认，点「完成」后闹钟正常取消、无残留排期）。
  别被 adb 骗了：`AlarmActivity` 是 `exported=false`，`am start` 起不来；关屏后隔几十秒截图也只会拍到黑屏 ——
  用户已经把它关掉了，`screencap` 拍的是关掉之后的状态。`appops` 里那条 `USE_FULL_SCREEN_INTENT rejectTime`
  同样不能当证据用（它跟这次弹出并存）。**这一屏想拿证据只能靠肉眼或录屏**，别用截图下结论。
- **键盘 inset 踩过一次**（已修）：`MainActivity` 没写 `windowSoftInputMode`，默认 adjustPan 会把整个窗口往上推，再叠上 Compose 的 `imePadding()`，输入框被顶到半空、标题被推出状态栏。现在manifest 钉 `adjustResize` + `enableEdgeToEdge()`，inset 只留 Compose 一个来源；底部用 `ime.union(navigationBars)` 而不是两个 padding 各加一遍。
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

- **`schedule/` 和 `data/` 是唯一不允许出错的部分**（见 DESIGN.md §05）。改这两个包之前先想清楚：AlarmManager 里的排期是易失的，Room 是唯一真相，四个重排触发源缺一个都可能导致漏提醒。
- **`ui/` 整包可丢弃、可重画**，改起来不用犹豫。
- 换 Kotlin/AGP/Compose 版本前看 README「关于依赖版本」那条约束链，顺序不能反。
- `minSdk` 定的是 **34**（不是当初设计文档写的 33），理由和踩坑过程见 DESIGN.md 决策 3.2 —— 已经改过一次，别改回去。

## 这一路上做过的、容易被遗忘的决定

- **openai-java 官方 SDK，不是手写 HTTP 客户端**（DESIGN.md 决策 3.1）。代价是包体：R8 之后从 2.2MB 涨到 35MB（+33MB，全是 Jackson + kotlin-reflect + victools）。这是接受了的权衡，不是 bug。
- **工具调用（Responses API `tools`），不是 `response_format: json_object`**。后者测试时模型会自己发明字段名（`{summary, details:{...}}`），工具调用把 schema 交给服务端强制，稳得多。`STRICT_SCHEMA`/`JSON_OBJECT` 那两档 JSON 输出模式代码留着当 fallback，但实际路径走的是 `ToolCallParser`。
- **多轮对话是客户端拼文本，不用 `previous_response_id`**。第三方 OpenAI 兼容服务大概率没实现服务端会话状态，`ChatTurn` 列表在每次请求里把历史文本拼进 `Prompt.user()`，对任何后端都成立。
- **字体用系统泛型（`FontFamily.Serif`/`Default`/`Monospace`），没打包视觉稿里的 Google Fonts**。理由同样是包体——Noto Serif SC 全字重能再吃掉大几 MB 到十几 MB，personal app 性价比存疑。想要像素级还原字体，需要往 `res/font/` 里塞真实字重文件，这是已知的、故意留下的差距，不是疏漏。「墨宋」这套视觉靠宋体挑大梁，系统衬体的中文 fallback 好不好看直接决定观感 —— 真机上第一眼要盯的就是这个。
- 通知全屏页是独立的 `AlarmActivity`，不是 `MainActivity` 借用 `showWhenLocked`——这样"到点响铃"和"正常打开 app"两件事不会互相污染。

## 常见误区

- 不要以为「编译通过」等于「能用」——这个项目里唯一有意义的验证是真机行为（闹钟真的进了 `dumpsys alarm`、通知真的弹出来、AI 真的调用了工具）。截图 + `adb shell dumpsys alarm` 比看代码可信。
- 不要在没有 `secrets.properties` 真实 key 的情况下猜测 AI 相关代码"应该没问题"——这个项目已经因为想当然的假设错过两次（`jsonMode` 一开始没接进请求、`json_object` 档模型自己发明字段名），都是真机测试才发现的。
