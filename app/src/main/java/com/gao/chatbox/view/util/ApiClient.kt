package com.gao.chatbox.view.util

import com.gao.chatbox.view.data.remote.AnthropicApi
import com.gao.chatbox.view.data.remote.ModelsResponse
import com.gao.chatbox.view.data.remote.OpenAiApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    private val streamingOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    fun buildOpenAiApi(baseUrl: String): OpenAiApi {
        val normalized = normalizeOpenAiUrl(baseUrl)
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }

    fun buildOpenAiApiStreaming(baseUrl: String): OpenAiApi {
        val normalized = normalizeOpenAiUrl(baseUrl)
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(streamingOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }

    fun buildAnthropicApi(baseUrl: String): AnthropicApi {
        val normalized = normalizeAnthropicUrl(baseUrl)
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnthropicApi::class.java)
    }

    fun buildAnthropicApiStreaming(baseUrl: String): AnthropicApi {
        val normalized = normalizeAnthropicUrl(baseUrl)
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(streamingOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnthropicApi::class.java)
    }

    private fun normalizeOpenAiUrl(url: String): String {
        var normalized = url.trimEnd('/')
        val suffixes = listOf("/chat/completions", "/completions", "/embeddings", "/models")
        for (suffix in suffixes) {
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                normalized = normalized.removeSuffix(suffix)
                break
            }
        }
        if (!normalized.endsWith("/v1") && !normalized.endsWith("/v1/")) {
            normalized = "$normalized/v1"
        }
        return "$normalized/"
    }

    private fun normalizeAnthropicUrl(url: String): String {
        var normalized = url.trimEnd('/')
        val suffixes = listOf("/messages", "/complete")
        for (suffix in suffixes) {
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                normalized = normalized.removeSuffix(suffix)
                break
            }
        }
        if (!normalized.endsWith("/v1") && !normalized.endsWith("/v1/")) {
            normalized = "$normalized/v1"
        }
        return "$normalized/"
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): ModelsResponse {
        val api = buildOpenAiApi(baseUrl)
        return api.getModels("Bearer $apiKey")
    }
}
