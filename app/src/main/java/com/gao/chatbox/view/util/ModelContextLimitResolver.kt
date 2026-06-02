package com.gao.chatbox.view.util

import com.gao.chatbox.view.data.model.ModelConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelContextLimitResolver @Inject constructor(
    private val modelConfigManager: ModelConfigManager
) {

    suspend fun resolve(
        apiType: String,
        apiUrl: String,
        apiKey: String,
        modelName: String
    ): Int {
        if (modelName.isBlank()) return DEFAULT_CONTEXT_LIMIT

        val cacheKey = buildCacheKey(apiType, apiUrl, modelName)
        modelConfigManager.getCachedContextLimit(cacheKey)?.let { return it }

        val resolved = discoverRemote(apiType, apiUrl, apiKey, modelName)
            ?: resolveStatic(apiType, modelName)
            ?: DEFAULT_CONTEXT_LIMIT

        modelConfigManager.cacheContextLimit(cacheKey, resolved)
        return resolved
    }

    suspend fun resolve(config: ModelConfig, modelName: String = config.defaultModel): Int {
        return resolve(
            apiType = config.apiType,
            apiUrl = config.apiUrl,
            apiKey = config.apiKey,
            modelName = modelName
        )
    }

    private suspend fun discoverRemote(
        apiType: String,
        apiUrl: String,
        apiKey: String,
        modelName: String
    ): Int? {
        if (apiUrl.isBlank() || apiKey.isBlank()) return null
        return runCatching {
            when (apiType) {
                ModelConfig.API_TYPE_ANTHROPIC -> discoverAnthropic(apiUrl, apiKey, modelName)
                else -> discoverOpenAiCompatible(apiUrl, apiKey, modelName)
            }
        }.getOrNull()
    }

    private suspend fun discoverOpenAiCompatible(
        apiUrl: String,
        apiKey: String,
        modelName: String
    ): Int? {
        val response = ApiClient.fetchModels(apiUrl, apiKey)
        return response.data.firstOrNull { it.id == modelName }?.contextLength
    }

    private suspend fun discoverAnthropic(
        apiUrl: String,
        apiKey: String,
        modelName: String
    ): Int? {
        val api = ApiClient.buildAnthropicApi(apiUrl)
        val single = runCatching { api.getModel(modelName, apiKey) }.getOrNull()
        if (single?.maxInputTokens != null && single.maxInputTokens > 0) {
            return single.maxInputTokens
        }
        val list = api.getModels(apiKey)
        return list.data.firstOrNull { it.id == modelName }?.maxInputTokens
    }

    fun resolveStatic(apiType: String, modelName: String): Int? {
        val normalized = modelName.trim().lowercase()
        return when (apiType) {
            ModelConfig.API_TYPE_ANTHROPIC -> resolveAnthropicStatic(normalized)
            else -> resolveOpenAiStatic(normalized)
        }
    }

    private fun resolveOpenAiStatic(modelName: String): Int? {
        return when {
            modelName.startsWith("gpt-5.5") -> 1_050_000
            modelName.startsWith("gpt-5.4") -> when {
                modelName.contains("mini") || modelName.contains("nano") -> 400_000
                else -> 1_050_000
            }
            modelName.startsWith("gpt-5.3-codex") -> 400_000
            modelName.startsWith("gpt-5.2") -> 400_000
            modelName.startsWith("gpt-5.1") -> 400_000
            modelName == "gpt-5" || modelName.startsWith("gpt-5-") -> 400_000
            modelName.startsWith("gpt-4.1") -> 1_047_576
            modelName.startsWith("gpt-4o") || modelName.startsWith("chatgpt-4o") -> 128_000
            modelName == "o1" || modelName.startsWith("o1-") -> 200_000
            modelName == "o3" || modelName.startsWith("o3-") -> 200_000
            modelName == "o4-mini" || modelName.startsWith("o4-mini") -> 200_000
            modelName.startsWith("gpt-oss-") -> 131_072
            // DeepSeek 系列
            modelName.startsWith("deepseek-v4") -> 1_000_000
            modelName.startsWith("deepseek-v3") || modelName.startsWith("deepseek-r1") -> 128_000
            modelName.startsWith("deepseek-v2") || modelName.startsWith("deepseek-coder") -> 128_000
            modelName.startsWith("deepseek") -> 128_000
            // MiniMax 系列
            modelName.startsWith("minimax-m3") || modelName.startsWith("minimax-m2") -> 1_000_000
            modelName.startsWith("minimax-text") -> 1_000_000
            modelName.startsWith("minimax") -> 1_000_000
            // 小米 MiMo 系列
            modelName.startsWith("mimo-v2.5") || modelName.startsWith("mimo-v2-pro") -> 1_000_000
            modelName.startsWith("mimo-v2") -> 1_000_000
            modelName.startsWith("mimo") -> 32_768
            else -> null
        }
    }

    private fun resolveAnthropicStatic(modelName: String): Int? {
        return when {
            modelName.startsWith("claude-opus-4-8") -> 1_000_000
            modelName.startsWith("claude-opus-4-7") -> 1_000_000
            modelName.startsWith("claude-opus-4-6") -> 1_000_000
            modelName.startsWith("claude-sonnet-4-6") -> 1_000_000
            modelName.contains("haiku") -> 200_000
            modelName.contains("sonnet") -> 200_000
            modelName.contains("opus") -> 200_000
            else -> null
        }
    }

    private fun buildCacheKey(apiType: String, apiUrl: String, modelName: String): String {
        val normalizedUrl = apiUrl.trim().trimEnd('/').lowercase()
        return "${apiType.trim().lowercase()}|$normalizedUrl|${modelName.trim().lowercase()}"
    }

    companion object {
        const val DEFAULT_CONTEXT_LIMIT = 65_536
    }
}
