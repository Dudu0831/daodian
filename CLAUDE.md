# 到点 (daodian)

用一句话建提醒的 Android app，个人自用、不上架。**先读 [DESIGN.md](DESIGN.md)** —— 那是权威的架构和决策记录，代码注释里的 `§5.3` `§9.2` 之类都指向它。这份文件是给 Claude Code 会话本身看的操作手册，两份不重复的内容互相补。

## 现状（2026-09，下面这行过时了就更新它）

- **M1 调度内核**：代码完成，真机冒烟测试通过（零漂移），**48 小时放置测试没跑过**——这是唯一还没拿到的硬证据。
- **M2 AI 解析**：用的是**工具调用**（`ToolCallParser` + `ReminderTool`），不是让模型输出 JSON。真机测试成功：模型正确调用 `create_reminder`，字段名、时间推算都对。多轮对话（反问后接得上）已实现（`ChatTurn` 客户端拼历史），**还没在新 UI 上做端到端真机验证**。
- **M3 UI**：八块 Compose 屏幕（对话/卡片/到点全屏/列表/编辑/设置/日志）全部写完、编译通过、lint 干净，装机冷启动截图确认深色主题和空状态渲染正常。**"喝水"这类完整链路的真机点击测试被用户打断，没跑完** —— 下次接手时这是要做的第一件事。
- 视觉设计稿（Claude Design 画布）：<https://claude.ai/code/artifact/327af1d8-4987-4f13-bc25-536e9b5f10d9>

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
- **字体用系统泛型（`FontFamily.Serif`/`Default`/`Monospace`），没打包视觉稿里的 Google Fonts**。理由同样是包体——Noto Serif SC 全字重能再吃掉大几 MB 到十几 MB，personal app 性价比存疑。想要像素级还原字体，需要往 `res/font/` 里塞真实字重文件，这是已知的、故意留下的差距，不是疏漏。
- 通知全屏页是独立的 `AlarmActivity`，不是 `MainActivity` 借用 `showWhenLocked`——这样"到点响铃"和"正常打开 app"两件事不会互相污染。

## 常见误区

- 不要以为「编译通过」等于「能用」——这个项目里唯一有意义的验证是真机行为（闹钟真的进了 `dumpsys alarm`、通知真的弹出来、AI 真的调用了工具）。截图 + `adb shell dumpsys alarm` 比看代码可信。
- 不要在没有 `secrets.properties` 真实 key 的情况下猜测 AI 相关代码"应该没问题"——这个项目已经因为想当然的假设错过两次（`jsonMode` 一开始没接进请求、`json_object` 档模型自己发明字段名），都是真机测试才发现的。
