package com.abc.daodian.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.providerDataStore by preferencesDataStore("provider")

/**
 * 供应商配置存在机器上，改完不用重新打包。见 DESIGN.md 决策 8.4
 *
 * `secrets.properties` 降级成**种子**：这里一个字段都没存过时用它，
 * 存过一次之后一律以这里为准。好处是卸载重装 / 换新机不用在手机上重敲 key。
 *
 * 不加密：`allowBackup="false"`，文件在 app 私有目录里，个人自用的取舍。
 * [ApiStyle] / [JsonMode] 不落盘 —— 实际路径只走 [ToolCallParser] 的工具调用，
 * 那两档现在不起作用，放出来只会让人以为能调。
 */
object ProviderStore {

    private val BASE_URL = stringPreferencesKey("base_url")
    private val API_KEY = stringPreferencesKey("api_key")
    private val MODEL = stringPreferencesKey("model")

    /** 打包时那份。设置页的「恢复成打包时的配置」把三格填回它 */
    val seed: ProviderProfile get() = ProviderProfile.fromBuildConfig()

    fun flow(context: Context): Flow<ProviderProfile> =
        context.applicationContext.providerDataStore.data.map { p ->
            if (p.contains(BASE_URL) || p.contains(API_KEY) || p.contains(MODEL)) {
                seed.copy(
                    baseUrl = p[BASE_URL].orEmpty(),
                    apiKey = p[API_KEY].orEmpty(),
                    model = p[MODEL].orEmpty()
                )
            } else {
                seed
            }
        }

    suspend fun save(context: Context, baseUrl: String, apiKey: String, model: String) {
        context.applicationContext.providerDataStore.edit { p ->
            p[BASE_URL] = normalizeBaseUrl(baseUrl)
            p[API_KEY] = apiKey.trim()
            p[MODEL] = model.trim()
        }
    }

    /** 把存下来的全删掉，下次读到的就是打包时那份 */
    suspend fun clear(context: Context) {
        context.applicationContext.providerDataStore.edit { it.clear() }
    }

    /**
     * 粘过来的地址十有八九带着尾巴。SDK 自己会拼 `/responses`，
     * 这里只留到 `/v1` 为止，多出来的路径原样留着会拼成 `/v1/responses/responses`。
     */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        listOf("/chat/completions", "/responses", "/completions").forEach { tail ->
            if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length).trimEnd('/')
        }
        return s
    }
}
