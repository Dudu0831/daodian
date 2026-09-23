package com.abc.daodian.agent.model.provider

import com.abc.daodian.BuildConfig

/**
 * 换供应商 = 改这几个字段。走的是 Responses API（`POST {baseUrl}/responses`）。
 *
 * 默认值来自根目录的 `secrets.properties`（不进 git，见 .gitignore），
 * 构建时注入 BuildConfig；运行时以 [ProviderStore] 里存的为准。
 */
data class ProviderProfile(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 让模型先想再答。开：`reasoning.effort=medium` + `summary=auto`（要了摘要才会发思考流）；
     * 关：`effort=none`。默认关 —— 建提醒这种短句，多想一轮换来的是多等几秒。
     */
    val thinking: Boolean = false
) {
    /** 没填 key 或 baseUrl 就别去打网络了，直接走手动录入那条逃生舱路径 */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 日志/界面上展示用，绝不能打印完整 key */
    fun redacted(): String =
        "ProviderProfile(baseUrl=$baseUrl, model=$model, thinking=$thinking, " +
            "key=${if (apiKey.isBlank()) "<未填>" else "***" + apiKey.takeLast(4)})"

    companion object {
        fun fromBuildConfig() = ProviderProfile(
            baseUrl = BuildConfig.LLM_BASE_URL.trim().removeSuffix("/"),
            apiKey = BuildConfig.LLM_API_KEY.trim(),
            model = BuildConfig.LLM_MODEL.trim()
        )
    }
}
