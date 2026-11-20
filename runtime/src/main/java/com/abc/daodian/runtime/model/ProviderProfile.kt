package com.abc.daodian.runtime.model

/**
 * 换供应商 = 改这几个字段。协议两种都有（[api]），各家网关认哪种不一样。
 *
 * 默认值来自根目录的 `secrets.properties`（不进 git，见 .gitignore），构建时注入 BuildConfig，
 * 由 app 那边的 `ProviderStore.seed` 读；运行时以 `ProviderStore` 里存的为准。
 */
data class ProviderProfile(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 想多久（[Effort]），对话页按住印往下拉选。Responses 上不是 none 时还要 `summary=auto`（要了摘要才会发思考流）。
     * 打开的工具包可以要求更深（[LlmRequest.effortFloor]），取深的那个。
     */
    val effort: Effort = Effort.DEFAULT,
    /** 走哪种协议。默认 Responses：以前只有它，存过的配置没这个字段时照旧 */
    val api: Api = Api.RESPONSES
) {

    enum class Api {
        /** `POST {baseUrl}/responses` */
        RESPONSES,

        /** `POST {baseUrl}/chat/completions`：只认这个的网关不少 */
        CHAT
    }

    /** 没填 key 或 baseUrl 就别去打网络了，直接走手动录入那条逃生舱路径 */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 日志/界面上展示用，绝不能打印完整 key */
    fun redacted(): String =
        "ProviderProfile(baseUrl=$baseUrl, api=$api, model=$model, effort=${effort.wire}, " +
            "key=${if (apiKey.isBlank()) "<未填>" else "***" + apiKey.takeLast(4)})"
}
