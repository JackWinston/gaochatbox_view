package com.gao.chatbox.view.data.remote

import retrofit2.http.GET
import retrofit2.http.Header

interface OpenAiApi {

    @GET("models")
    suspend fun getModels(
        @Header("Authorization") authorization: String
    ): ModelsResponse
}
