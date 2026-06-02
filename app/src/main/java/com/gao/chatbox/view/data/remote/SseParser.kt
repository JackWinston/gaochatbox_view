package com.gao.chatbox.view.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody

sealed class StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val functionName: String?,
        val arguments: String?
    ) : StreamEvent()
    data class StreamEnd(val usage: TokenUsage? = null, val finishReason: String? = null) : StreamEvent()
    data class Error(val message: String, val cause: Throwable? = null) : StreamEvent()
}

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

object SseParser {

    fun parseOpenAiStream(body: ResponseBody): Flow<StreamEvent> = flow {
        val gson = Gson()
        val reader = body.charStream().buffered()
        var lastUsage: TokenUsage? = null
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        emit(StreamEvent.StreamEnd(lastUsage))
                        break
                    }
                    try {
                        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
                        val choice = chunk.choices.firstOrNull()
                        val content = choice?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            emit(StreamEvent.ContentDelta(content))
                        }
                        // Handle tool_calls in delta
                        choice?.delta?.toolCalls?.forEach { toolCall ->
                            emit(StreamEvent.ToolCallDelta(
                                index = toolCall.index,
                                id = toolCall.id,
                                functionName = toolCall.function?.name,
                                arguments = toolCall.function?.arguments
                            ))
                        }
                        chunk.usage?.let {
                            lastUsage = TokenUsage(it.promptTokens, it.completionTokens)
                        }
                        // Emit StreamEnd with finish_reason if it's tool_calls
                        val finishReason = choice?.finishReason
                        if (finishReason != null && finishReason != "stop") {
                            emit(StreamEvent.StreamEnd(lastUsage, finishReason))
                            break
                        }
                    } catch (_: Exception) {
                        // Skip malformed JSON lines
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Stream error: ${e.message}", e))
        } finally {
            reader.close()
            body.close()
        }
    }.flowOn(Dispatchers.IO)

    fun parseAnthropicStream(body: ResponseBody): Flow<StreamEvent> = flow {
        val gson = Gson()
        val reader = body.charStream().buffered()
        var lastUsage: TokenUsage? = null
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    try {
                        val event = gson.fromJson(data, AnthropicStreamEvent::class.java)
                        when (event.type) {
                            "content_block_delta" -> {
                                val text = event.delta.text
                                if (text.isNotEmpty()) {
                                    emit(StreamEvent.ContentDelta(text))
                                }
                            }
                            "message_delta" -> {
                                event.usage?.let {
                                    lastUsage = TokenUsage(it.inputTokens, it.outputTokens)
                                }
                            }
                            "message_stop" -> {
                                emit(StreamEvent.StreamEnd(lastUsage))
                                break
                            }
                        }
                    } catch (_: Exception) {
                        // Skip malformed JSON lines
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Stream error: ${e.message}", e))
        } finally {
            reader.close()
            body.close()
        }
    }.flowOn(Dispatchers.IO)
}
