package com.abc.daodian.ai

import com.abc.daodian.BuildConfig

/** 接口风格。绝大多数第三方供应商只认 CHAT_COMPLETIONS */
enum class ApiStyle {
    /** POST {baseUrl}/chat/completions —— 事实标准 */
    CHAT_COMPLETIONS,
    /** POST {baseUrl}/responses —— OpenAI 较新的接口，第三方基本不支持 */
    RESPONSES;

    companion object {
        fun parse(v: String?) =
            entries.firstOrNull { it.name.equals(v?.trim(), ignoreCase = true) } ?: CHAT_COMPLETIONS
    }
}

/** 结构化输出档位。见 DESIGN.md §6.2 —— 各家支持程度不一致，别赌 */
enum class JsonMode {
    STRICT_SCHEMA, JSON_OBJECT, PROMPT_ONLY;

    companion object {
        fun parse(v: String?) =
            entries.firstOrNull { it.name.equals(v?.trim(), ignoreCase = true) } ?: JSON_OBJECT
    }
}

/**
 * 换供应商 = 改这几个字段。
 *
 * 默认值来自根目录的 `secrets.properties`（不进 git，见 .gitignore），
 * 构建时注入 BuildConfig。设置页以后可以在运行时覆盖它。
 */
data class ProviderProfile(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val apiStyle: ApiStyle,
    val jsonMode: JsonMode
) {
    /** 没填 key 或 baseUrl 就别去打网络了，直接走手动录入那条逃生舱路径 */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 日志/界面上展示用，绝不能打印完整 key */
    fun redacted(): String =
        "ProviderProfile(baseUrl=$baseUrl, model=$model, style=$apiStyle, json=$jsonMode, " +
            "key=${if (apiKey.isBlank()) "<未填>" else "***" + apiKey.takeLast(4)})"

    companion object {
        fun fromBuildConfig() = ProviderProfile(
            baseUrl = BuildConfig.LLM_BASE_URL.trim().removeSuffix("/"),
            apiKey = BuildConfig.LLM_API_KEY.trim(),
            model = BuildConfig.LLM_MODEL.trim(),
            apiStyle = ApiStyle.parse(BuildConfig.LLM_API_STYLE),
            jsonMode = JsonMode.parse(BuildConfig.LLM_JSON_MODE)
        )
    }
}
