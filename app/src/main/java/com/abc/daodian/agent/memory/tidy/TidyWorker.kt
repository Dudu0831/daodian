package com.abc.daodian.agent.memory.tidy

import android.content.Context
import androidx.work.WorkerParameters
import com.abc.daodian.assistant.memory.tidy.TidyWorker as MovedTidyWorker

/**
 * 旧全类名的空壳（10-15 记忆挪进 `assistant/` 时留的）：升级前排着的那一次「闲下来整理」，WorkManager 记的是这个类名，
 * 没有它那一次就找不到类、丢了。新排的都是 [MovedTidyWorker]。
 * 那一次最多排 10 分钟后就跑完，过两个版本删掉这个文件（连同 `agent/` 这个目录）。
 */
class TidyWorker(context: Context, params: WorkerParameters) : MovedTidyWorker(context, params)
