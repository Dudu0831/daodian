# 到点 (daodian)

给 Claude Code 会话的操作手册。是什么、怎么构建看 [README.md](README.md)；**改代码前读 [DESIGN.md](DESIGN.md) 对应那一节**（为什么这样、要守的规矩，代码注释里的 `§5.3` 都指它）。这份只记三样：现状、怎么拿证据、坑。

## 现状（2026-09-24，过时了就更新）

**分支 `restructure-modules` 还没合进 `main`，也还没上过真机。** 它把包重排成 agent / reminder / ledger / shared（DESIGN.md §2.2），全类名都变了：装它之前要连数据卸载（`adb uninstall com.abc.daodian.debug`，清空提醒、账本、对话、配置、权限、小组件），装完从头配。要测什么在 [TEST_PLAN.md](TEST_PLAN.md)。

分支后半段（记账提示词、Markdown、画图、抓取页、打开不闪）是在云端会话里写的，那个环境拉不到 `dl.google.com`，**app 没编译过**：只在单独的 JVM 工程里跑过单测、对着桌面版 Compose 和 Android 15 框架类库做过类型检查。本地第一件事：`./gradlew :app:testDebugUnitTest`，再 `assembleDebug`。

下表「真机验过」都是在重排之前的 main 上验的（荣耀 LGE-AN10，火山方舟 `deepseek-v4.1-flash`，句子多是英文）。

| 功能 | 真机验过 | 还没验 |
|---|---|---|
| 调度内核 | 冒烟零漂移（`dumpsys alarm` 里有 `Alarm clock:` 段、`exactAllowReason=policy_permission`、列为 `Next wake from idle`）；「喝水」全链路漂移 259ms；锁屏全屏页会弹（肉眼确认），点「完成」无残留排期 | **48 小时放置测试**（DESIGN §9.3，唯一还缺的硬证据） |
| 对话 agent | 流式逐字、工具参数边流边出；骨架阶段点「停」几百毫秒内撤回；「hello」只回话；重装后对话还在 | 断网 / 坏 key 的回退；推理模型的思考块（这家供应商不发）；思考开关网关收不收；「↓ 新内容」 |
| 问卡与痕 | 直接建提醒只留痕、闹钟排上；一题 / 两题问卡点选 +「其他…」→ 建成；历史重启后按新样子画回来；痕点进编辑页 | 不点直接说；问卡等着时点「停」、杀进程后读回「没答」；速记交给对话页；编辑页的「依据」；多笔的对账问卡；深色 |
| 当天事项 | 收尾时刻准点响（漂移 23ms）、不弹全屏、顺延到第二天、通知上「完成」、两条合成一组、改收尾时刻全部跟着挪 | 列表的「拖 N 天」；小组件上的当天事项；重复的当天事项；中文句子 |
| 提醒列表（时间轴） | 排版；点圈完成 → 闹钟消失 → 撤销 → 闹钟回来；左滑删除无残留 | 告警态（过点没响、没排上）；深色；重复提醒的完成 / 撤销 |
| 设置页、编辑页 | 排版、漂移写人话；编辑页新建 → 滚轮 → 存 → 闹钟；页内删除 → 闹钟消失 | 权限缺项的红字和「去开」；深色；「当天之内」切换；CUSTOM 规则保留 |
| 模型服务（印章、纸签、配置页） | —— | 印章三态、纸签排版、改完 key 下一句就用新的、「测一下」、飞行模式下变墨灰再盖回朱砂 |
| 桌面小组件、速记 | 3×2 两条的版式；纸从小组件长出来（和截图差 4px 以内）；本地语音（`dumpsys audio` 里是 `VOICE_RECOGNITION`、没有 YOYO，模型首次加载 1.9s） | 4×2 / 4×3 / 4×4（只能手动拖） |
| 记账 | 样本导入 → 整理（15 秒 20 条）→ 抽屉 → 三层 → 一笔「去对话里说」→ 对账一轮；21:30 闹钟在 `dumpsys alarm` 里 | 真付一笔 → 实时进库 → 3 小时后自动整理；21:30 通知的「晚点」「今天算了」 |
| 分支上新加的（没编译） | —— | 记账提示词（转给别人算支出）、Markdown、画图、抓取页、打开不闪，见 TEST_PLAN.md |

## 环境速查

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug   # 打包（JDK 21 在 gradle.properties 里钉死，换机器要改那行）
./gradlew :app:installDebug    # 装机；MagicOS 会弹安装确认框，装不上重试一次通常就过
```

真机是荣耀 LGE-AN10（Android 15），一直连着 USB。`adb devices` 偶尔连不上，`adb kill-server && adb start-server` 能救。

`secrets.properties`（gitignored）只是模型配置的种子；里面那个 `gpt-5.6-sol` 旧网关已经 503，app 里要在配置页换成火山方舟（`https://ark.cn-beijing.volces.com/api/plan/v3`，模型 `deepseek-v4.1-flash`）。

云端会话（claude.ai/code）默认拉不到 `dl.google.com`，AGP / androidx 装不上，app 编不了。要在那边编译，得在环境的网络设置里放行 `dl.google.com`。

## 怎么拿证据

**唯一有意义的验证是真机行为**：闹钟真的进了 `dumpsys alarm`、通知真的弹出来、模型真的调了工具。「编译通过」不等于能用；没有真 key 时别猜模型相关的代码「应该没问题」—— `jsonMode` 没接进请求、`json_object` 档模型自己发明字段名，都是真机才发现的。

- **这台 ROM 屏蔽第三方 app 的 logcat**，`Log.i` 一行都看不到。证据看库和界面：
  - 闹钟：`adb shell dumpsys alarm | grep -B2 -A6 daodian`，`origWhen=` 就是排的时刻
  - 响没响、准不准：app 内「投递日志」页 / `fire_log` 表
  - 语音：debug 包 `files/quick_trace.txt` 里 `voice ←` 那几行
  - 库：`adb exec-out run-as com.abc.daodian.debug cat databases/<名字>.db > <名字>.db`（连 `-wal`、`-shm` 一起拷），再 `sqlite3`
- **到点全屏页只能靠肉眼或录屏**：`AlarmActivity` 是 `exported=false`，`am start` 起不来；关屏后隔几十秒截图只拍到黑屏（用户已经关掉了）；`appops` 里那条 `USE_FULL_SCREEN_INTENT rejectTime` 也不能当证据。
- `adb install -r` 在这台机器上**不一定重启 app 进程**，装完用 `ps` 看启动时间，不然跑的还是旧代码。
- adb 打不了中文，用 adb 测的句子都是英文。
- 小组件没法用 adb 绑定（这台 ROM 没有 `cmd appwidget`），只能手动长按桌面添加、手动拖尺寸。深链：
  `adb shell am start -n com.abc.daodian.debug/com.abc.daodian.MainActivity --es com.abc.daodian.launch.ROUTE reminder/list`（新建一条是 `reminder/edit`）
- 打真网关的 JVM 测试（环境变量覆盖供应商，不落文件）：
  `DAODIAN_LIVE=1 DAODIAN_BASE_URL=… DAODIAN_KEY=… DAODIAN_MODEL=… ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest*' -i`
- 记账的原始通知、监听连没连着：设置 → 记账 →「抓到的通知」。

## 不要碰的假设

- **`reminder/scheduling/` 和 `reminder/data/` 是唯一不允许出错的部分**（DESIGN §05）。AlarmManager 里的排期是易失的，Room 是唯一真相，四个重排触发源缺一个都可能漏提醒。
- **界面整包可丢弃、可重画**（各模块的 `presentation/`、`agent/conversation`、`agent/shell`），改起来不用犹豫。
- **依赖规则**（DESIGN §2.2）：`agent` 不 import 模块、模块之间不互相 import、跨模块跳转只用路由。加功能先想它归哪个模块、要不要经接头。
- 换 Kotlin / AGP / Compose 版本前看 README「依赖版本」那条约束链，顺序不能反。
- `minSdk` 是 **34**，别改回 33（决策 3.2）。

## 容易忘的决定

都是定过的，别再提一遍：

- openai-java 官方 SDK，不手写 HTTP 客户端；包体 35MB 是接受了的代价（决策 3.1）。
- 工具调用，不用 `response_format: json_object`（§6.4）。
- 历史客户端自己带，不用 `previous_response_id`（§6.1）。
- 写操作直接办、留痕，拿不准才出问卡；**授权条删了，不加回来**（§6.6，用户 09-24 又确认过一次）。
- 不用提示词管 Markdown 格式，模型写什么界面认什么（决策 6.4）。
- 画图只做静态 SVG，图上的数是模型抄的（§6.8）。
- 聊天里不震动（§6.5）；时间轴列表不加虚化 / 缩放（§08）。
- 字体用系统泛型，没打包 Noto Serif SC —— 墨宋靠宋体挑大梁，真机上第一眼要看系统衬体的中文好不好看（§8.1）。
- 到点全屏页是独立的 `AlarmActivity`（§5.6）。
- 记账：模型读懂，代码只收集和校验；流水原样发给网关不脱敏；改账只在对话里（§10）。

## 踩过的坑

一行一条，细节在 DESIGN 对应那一节。

- **键盘 inset**：manifest 没写 `windowSoftInputMode` 时默认 adjustPan 把整个窗口往上推，再叠上 Compose 的 `imePadding()`，输入框飞到半空。现在钉 `adjustResize` + `enableEdgeToEdge()`，inset 只留 Compose 一个来源，底部用 `ime.union(navigationBars)`。第二次：键盘弹起时列表从底下被压矮、最新一句压在输入框后面 —— `ChatScreen` 盯着视口高度，矮了多少往下滚多少。
- **「停」半天没反应**：阻塞 HTTP 打断不了（§6.2 坑 1）。
- **闲聊被当成建提醒**：提示词先分流（§6.3）。
- **同一句中文模型有时只回「明白」不调工具**（英文版才调），是模型 / 提示词的问题，不是界面。
- **`BYMONTHDAY=-1`**（月底）以前会让 `Rrule` 抛异常，已支持负数（§7.2）。
- **一步走了近 3 分钟**：`ResponsesClient` 没设超时（§6.2）。
- **记账漏抓**：实时回调会丢、监听会断而且断了没人知道；别用禁用组件那招重连（§10.2）。
- **转给别人被当成转移作废了**：提示词已改，作废撤不回（§10.3）。
- **清账本要连对话一起清**，不然模型以为清单上的几笔已经处理过（§10.7 第 9 条）。
- **打开 app 先闪一下空对话**：记录读回来之前不画空状态、开屏按住（§6.1）。
- **挪包之后装新包要连数据卸载**：通知使用权、小组件、闹钟、WorkManager 都按全类名记（§2.2）。
