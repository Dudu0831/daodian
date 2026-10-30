# 留着等删的旧代码

为了已经装在手机上的旧版本留的壳：手机上按名字记着的东西（通知里的 PendingIntent、WorkManager 排着的类名、桌面小组件指着的 Activity）还指着旧名字，删早了点进去会崩、排着的活会丢。每样写清楚为什么留、在哪、哪一版删。

**规矩**：留壳只留手机上按名字记着的；纯代码不留，换掉那一版直接删（DESIGN.md §2.2「挪包 = 改全类名」）。加了新的壳就在这里记一行。删掉之后把那一行挪到最底下「删过的」。

## 0.2.1 删

| 壳 | 在哪 | 为什么留 | 删的时候连带 |
|---|---|---|---|
| 通知管家页顶上「以前设置里勾的」那一组（0.1.x 的记账听哪些 app、派活名单和暗号，只读） | `intake/legacy/OldSetup.kt` 整个文件（连 `test/…/intake/legacy/OldSetupTest.kt`）；`intake/presentation/StewardScreen.kt` 的 `OldSetupGroup` 和调它的两行、头上注释里那句；`IntakeViewModel` 的 `oldSetup`、`appName`、`init`；`IntakeShell` 里传给 `StewardScreen` 的 `say` | 0.2.0 旧配置不搬，升上来的人照着这一组在对话里重新说一次。看过一版就够了，不是手机上按名字记着的东西，0.2.1 直接删 | DESIGN §12 和 CLAUDE.md 里提到这一组的地方；删了之后「待定：手机上留着的旧文件」里那两个 `.preferences_pb` 就真没人读了 |

删完：`./gradlew :app:testDebugUnitTest`；打开设置 → 通知管家，最上面是「监听」。

## 0.2.2 删

0.2.0 是通知管家切换那一版（派活、订阅者删掉，只剩「听通知」），隔一个版本到 0.2.2 再删下面这些。

| 壳 | 在哪 | 为什么留 | 删的时候连带 |
|---|---|---|---|
| 派活的两个路由 `reminder/relay/{id}`、`reminder/relay` | `reminder/ReminderRoutes.kt` 的 `RELAY`、`RELAY_OLD`；`reminder/presentation/ReminderShell.kt` 的两个 `composable` 和 `RelayShim`、`RULES_ROUTE` | 0.1.x 弹的「派的事没接住」通知还挂在通知栏里，PendingIntent 里存着这两个路由；NavHost 找不到会崩。现在进来先退掉自己、再开规则页 | `ReminderShell` 头上注释里的「派活两个旧路由留壳」 |
| 记忆整理的旧类名 `agent.memory.tidy.TidyWorker` | `app/src/main/java/com/abc/daodian/agent/memory/tidy/TidyWorker.kt`（整个 `agent/` 目录只剩它） | 10-15 记忆挪进 `assistant/` 之前排下的「闲下来整理」，WorkManager 记的是旧类名。那一次最多 10 分钟后就跑完，早该删了 | 删整个 `agent/` 目录 |
| 速记的旧类名 `.agent.entry.quick.QuickAddActivity` | `app/src/main/AndroidManifest.xml` 里那个 `<activity-alias>` | 10-15 速记挪包之前就摆在桌面上的小组件，墨印的 PendingIntent 指着旧名字；小组件重画一次就换成新名字 | manifest 里 alias 上面那段注释 |

删完：`./gradlew :runtime:test :app:testDebugUnitTest`；装 debug 包从桌面小组件点一下墨印进速记、看一眼 `dumpsys alarm` 里提醒都还在。

## 待定：手机上留着的旧文件

0.2.0 照「旧数据不删」（NOTICE_PLAN 第 6 节）没碰；两个 `.preferences_pb` 只有 0.2.0 通知管家页顶上那一组读（只读，0.2.1 删），别的代码不再读写：

- `databases/relay.db`（派活收到的每一句）
- `files/datastore/intake.preferences_pb`（以前每个模块勾了哪些 app）、`relay.preferences_pb`（派活名单、暗号）
- debug 包还有 `notice_routing.preferences_pb`（第 6 步的开关）、`files/trace_relay.txt`

要清就在 0.2.2 加几行冷启动时删（`deleteDatabase("relay.db")`、删 `datastore/` 下那几个文件），再过两个版本把这几行也删掉。不清也没坏处，只占一点空间。

## 一直留着，不是壳

- 通知渠道 id `relay_v1`（`reminder/notice/ReminderNoticeTarget.kt`）：系统按 id 记你对这个渠道的设置（关没关、响不响），改 id 就丢了。显示名 0.2.0 改成了「通知建的提醒」。
- 监听组件全类名 `com.abc.daodian.intake.NoticeListenerService`：通知使用权按它授，改了要重新开。
- `ReminderNoticeTarget` 的通知编号从 800000 起，和以前派活的 700000 起错开：防的是还挂着的旧通知被盖掉，留着不碍事。

## 删过的

（还没有）
