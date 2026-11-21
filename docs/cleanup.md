# 留着等删的旧代码

为了已经装在手机上的旧版本留的壳：手机上按名字记着的东西（通知里的 PendingIntent、WorkManager 排着的类名、桌面小组件指着的 Activity）还指着旧名字，删早了点进去会崩、排着的活会丢。每样写清楚为什么留、在哪、哪一版删。

**规矩**：留壳只留手机上按名字记着的；纯代码不留，换掉那一版直接删（DESIGN.md §2.2「挪包 = 改全类名」）。加了新的壳就在这里记一行。删掉之后把那一行挪到最底下「删过的」。

## 0.2.4 删

| 壳 | 在哪 | 为什么留 |
|---|---|---|
| 冷启动删旧文件那几行 | `intake/LegacyFiles.kt` 和 `IntakeModule.onAppStart` 里调它那一行 | 0.2.2 起删 0.2.0 之前留下的 `relay.db`、`datastore/` 下的 `intake` / `relay` / `notice_routing.preferences_pb`、`trace_relay.txt`；隔两个版本，手机上的都删过了 |

## 一直留着，不是壳

- 通知渠道 id `relay_v1`（`reminder/notice/ReminderNoticeTarget.kt`）：系统按 id 记你对这个渠道的设置（关没关、响不响），改 id 就丢了。显示名 0.2.0 改成了「通知建的提醒」。
- 监听组件全类名 `com.abc.daodian.intake.NoticeListenerService`：通知使用权按它授，改了要重新开。
- `ReminderNoticeTarget` 的通知编号从 800000 起，和以前派活的 700000 起错开：防的是还挂着的旧通知被盖掉，留着不碍事。

## 删过的

| 壳 | 哪一版删的 |
|---|---|
| 通知管家页顶上「以前设置里勾的」那一组（`intake/legacy/OldSetup.kt` 连测试、`StewardScreen.OldSetupGroup`、`IntakeViewModel.oldSetup` / `appName`、传给 `StewardScreen` 的 `say`） | 0.2.1（11-13） |
| 派活的两个旧路由 `reminder/relay/{id}`、`reminder/relay`（`ReminderRoutes.RELAY` / `RELAY_OLD`、`ReminderShell` 的 `RelayShim`） | 0.2.2（11-20） |
| 记忆整理的旧类名 `agent.memory.tidy.TidyWorker`（连整个 `agent/` 目录） | 0.2.2（11-20） |
| 速记的旧类名 `.agent.entry.quick.QuickAddActivity` 的 `activity-alias` | 0.2.2（11-20） |
