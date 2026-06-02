package com.gao.chatbox.view.util

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogManager {

    private const val LOG_DIR = "debug_logs"
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun getLogDir(context: Context): File {
        return File(context.cacheDir, LOG_DIR).apply { mkdirs() }
    }

    fun getLogFile(context: Context, conversationId: Long): File {
        return File(getLogDir(context), "conv_$conversationId.json")
    }

    fun listLogFiles(context: Context): List<File> {
        return getLogDir(context).listFiles()
            ?.filter { it.name.startsWith("conv_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun readLogFile(context: Context, conversationId: Long): String {
        val file = getLogFile(context, conversationId)
        if (!file.exists()) return "[]"
        val raw = file.readText()
        return try {
            val jsonElement = JsonParser.parseString(raw)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            raw
        }
    }

    fun readLogFileRaw(context: Context, conversationId: Long): List<LogEntry> {
        val file = getLogFile(context, conversationId)
        if (!file.exists()) return emptyList()
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, LogEntry::class.java).type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun appendLog(
        context: Context,
        conversationId: Long,
        type: String,
        url: String,
        requestBody: String?,
        responseBody: String?,
        isError: Boolean = false
    ) {
        val file = getLogFile(context, conversationId)
        val entries = readLogFileRaw(context, conversationId).toMutableList()

        entries.add(
            LogEntry(
                timestamp = dateFormat.format(Date()),
                type = type,
                url = url,
                requestBody = formatJsonOrNull(requestBody),
                responseBody = formatJsonOrNull(responseBody),
                isError = isError
            )
        )

        file.writeText(gson.toJson(entries))
    }

    fun deleteLogFile(context: Context, conversationId: Long) {
        getLogFile(context, conversationId).delete()
    }

    private fun formatJsonOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val element = JsonParser.parseString(raw)
            gson.toJson(element)
        } catch (e: Exception) {
            raw
        }
    }

    data class LogEntry(
        val timestamp: String,
        val type: String,
        val url: String,
        val requestBody: String?,
        val responseBody: String?,
        val isError: Boolean = false
    )
}
