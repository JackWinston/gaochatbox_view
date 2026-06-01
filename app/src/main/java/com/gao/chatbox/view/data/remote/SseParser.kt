package com.gao.chatbox.view.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody

sealed class StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent()
    data object StreamEnd : StreamEvent()
    data class Error(val message: String, val cause: Throwable? = null) : StreamEvent()
}

object SseParser {

    fun parseOpenAiStream(body: ResponseBody): Flow<StreamEvent> = flow {
        val gson = Gson()
        val reader = body.charStream().buffered()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        emit(StreamEvent.StreamEnd)
                        break
                    }
                    try {
                        val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            emit(StreamEvent.ContentDelta(content))
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
                            "message_stop" -> {
                                emit(StreamEvent.StreamEnd)
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
