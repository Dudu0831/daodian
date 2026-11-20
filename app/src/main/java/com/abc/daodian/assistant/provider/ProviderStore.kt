package com.abc.daodian.assistant.provider

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abc.daodian.BuildConfig
import com.abc.daodian.runtime.ModelSource
import com.abc.daodian.runtime.model.Effort
import com.abc.daodian.runtime.model.ProviderProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.providerDataStore by preferencesDataStore("provider")

/**
 * 供应商配置存在机器上，改完不用重新打包。见 DESIGN.md 决策 8.4
 *
 * `secrets.properties` 降级成**种子**：这里一个字段都没存过时用它，
 * 存过一次之后一律以这里为准。好处是卸载重装 / 换新机不用在手机上重敲 key。
 *
 * 不加密：`allowBackup="false"`，文件在 app 私有目录里，个人自用的取舍。
 */
object ProviderStore {

    private val BASE_URL = stringPreferencesKey("base_url")
    private val API_KEY = stringPreferencesKey("api_key")
    private val MODEL = stringPreferencesKey("model")
    /** 想多久，存 [Effort.wire]。以前的「先想一想再答」开关（`thinking`）不读了：没选过一律从默认「想一下」起 */
    private val EFFORT = stringPreferencesKey("effort")
    private val API = stringPreferencesKey("api")

    /** 打包时那份。设置页的「恢复成打包时的配置」把三格填回它 */
    val seed: ProviderProfile
        get() = ProviderProfile(
            baseUrl = BuildConfig.LLM_BASE_URL.trim().removeSuffix("/"),
            apiKey = BuildConfig.LLM_API_KEY.trim(),
            model = BuildConfig.LLM_MODEL.trim()
        )

    /** 交给 runtime 的：跑 agent 时读当时的配置（`AgentRuntime.install`） */
    fun source(context: Context): ModelSource {
        val app = context.applicationContext
        return ModelSource { flow(app).first() }
    }

    fun flow(context: Context): Flow<ProviderProfile> =
        context.applicationContext.providerDataStore.data.map { p ->
            // 想多久不算「存过配置」：只拉过印时，三格照样用种子
            val base = if (p.contains(BASE_URL) || p.contains(API_KEY) || p.contains(MODEL)) {
                seed.copy(
                    baseUrl = normalizeBaseUrl(p[BASE_URL].orEmpty()),
                    apiKey = firstLine(p[API_KEY].orEmpty()),
                    model = firstLine(p[MODEL].orEmpty())
                )
            } else {
                seed
            }
            base.copy(
                effort = Effort.parse(p[EFFORT]),
                // 存的是枚举名；没存过（这个字段是后加的）或认不出来都当 Responses，以前只有它
                api = p[API]?.let { name -> ProviderProfile.Api.entries.firstOrNull { it.name == name } } ?: seed.api
            )
        }

    suspend fun save(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        api: ProviderProfile.Api
    ) {
        context.applicationContext.providerDataStore.edit { p ->
            p[BASE_URL] = normalizeBaseUrl(baseUrl)
            p[API_KEY] = firstLine(apiKey)
            p[MODEL] = firstLine(model)
            p[API] = api.name
        }
    }

    /** 对话页按住印往下拉选的那一档（§8.4）。下一步请求起就用它 */
    suspend fun setEffort(context: Context, effort: Effort) {
        context.applicationContext.providerDataStore.edit { it[EFFORT] = effort.wire }
    }

    /** 把配置的三格和协议删掉，下次读到的就是打包时那份。想多久留着：那是对话页上选的，不是配置 */
    suspend fun clear(context: Context) {
        context.applicationContext.providerDataStore.edit { p ->
            val effort = p[EFFORT]
            p.clear()
            effort?.let { p[EFFORT] = it }
        }
    }

    /**
     * 粘过来的地址十有八九带着尾巴。SDK 自己会拼 `/responses` 或 `/chat/completions`，
     * 这里只留到 `/v1` 为止，多出来的路径原样留着会拼成 `/v1/responses/responses`。
     */
    fun normalizeBaseUrl(raw: String): String {
        var s = firstLine(raw).trimEnd('/')
        listOf("/chat/completions", "/responses", "/completions").forEach { tail ->
            if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length).trimEnd('/')
        }
        return s
    }

    /** 单行配置项（地址 / key / 模型）粘进多行时只留第一条非空行，免得换行后面的字被单行框裁掉看不见 */
    fun firstLine(raw: String): String =
        raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
