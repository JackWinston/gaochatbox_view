package com.gao.chatbox.view.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object WebSearchTool {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun execute(query: String): String {
        return try {
            val results = searchDuckDuckGo(query)
            if (results.isEmpty()) {
                "未找到与\"$query\"相关的搜索结果"
            } else {
                buildString {
                    appendLine("以下是\"$query\"的搜索结果：")
                    appendLine()
                    results.forEachIndexed { index, result ->
                        appendLine("${index + 1}. ${result.title}")
                        appendLine("   链接: ${result.url}")
                        appendLine("   摘要: ${result.snippet}")
                        appendLine()
                    }
                }
            }
        } catch (e: Exception) {
            "搜索失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        val html = response.body?.string() ?: return emptyList()

        return parseHtml(html)
    }

    private fun parseHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Parse DuckDuckGo HTML results
        val resultPattern = Pattern.compile(
            """<a[^>]+class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            Pattern.DOTALL
        )
        val snippetPattern = Pattern.compile(
            """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
            Pattern.DOTALL
        )

        val resultMatcher = resultPattern.matcher(html)
        val snippetMatcher = snippetPattern.matcher(html)

        while (resultMatcher.find() && results.size < 10) {
            val rawUrl = resultMatcher.group(1) ?: ""
            val rawTitle = resultMatcher.group(2) ?: ""

            // Extract actual URL from DuckDuckGo redirect
            val url = extractUrl(rawUrl)
            val title = stripHtml(rawTitle)

            // Try to find matching snippet
            val snippet = if (snippetMatcher.find()) {
                stripHtml(snippetMatcher.group(1) ?: "")
            } else {
                ""
            }

            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
    }

    private fun extractUrl(rawUrl: String): String {
        // DuckDuckGo wraps URLs in redirect links
        val uddgPattern = Pattern.compile("uddg=([^&]+)")
        val matcher = uddgPattern.matcher(rawUrl)
        return if (matcher.find()) {
            java.net.URLDecoder.decode(matcher.group(1)!!, "UTF-8")
        } else {
            rawUrl
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
