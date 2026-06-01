package com.gao.chatbox.view.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface OpenAiApi {

    @GET("models")
    suspend fun getModels(
        @Header("Authorization") authorization: String
    ): ModelsResponse

    @POST("chat/completions")
    @Streaming
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiChatRequest
    ): ResponseBody
}
