package com.gao.chatbox.view.util

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object WebSearchTool {

    private const val TAG = "WebSearchTool"
    private const val MAX_DIRECT_CONTENT_LENGTH = 6000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun searchQuery(query: String): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "searchQuery start, queryLength=${query.length}, query=${query.take(120)}")
        return try {
            if (query.isBlank()) return "搜索关键词为空"
            val results = search(query)
            Log.d(TAG, "searchQuery success, resultCount=${results.size}, elapsedMs=${System.currentTimeMillis() - startTime}")
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
            Log.e(TAG, "searchQuery failed, elapsedMs=${System.currentTimeMillis() - startTime}, query=$query", e)
            "搜索失败: 网络超时或搜索服务暂时不可用，请稍后重试"
        }
    }

    fun fetchContent(inputUrl: String): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "fetchContent start, url=$inputUrl")
        return try {
            val url = normalizeUrl(inputUrl)
            val content = fetchUrl(url)
            Log.d(TAG, "fetchContent success, elapsedMs=${System.currentTimeMillis() - startTime}, url=$url")
            content
        } catch (e: Exception) {
            Log.e(TAG, "fetchContent failed, elapsedMs=${System.currentTimeMillis() - startTime}, url=$inputUrl", e)
            "网页内容获取失败: ${e.message ?: "未知错误"}"
        }
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    private fun search(query: String): List<SearchResult> {
        Log.d(TAG, "search start, engines=[bing,duckduckgo]")
        return runCatching { searchBing(query) }
            .onFailure { Log.w(TAG, "bing failed, fallback to duckduckgo", it) }
            .getOrElse {
                runCatching { searchDuckDuckGo(query) }
                    .onFailure { error -> Log.e(TAG, "duckduckgo failed after bing fallback", error) }
                    .getOrThrow()
            }
    }

    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        Log.d(TAG, "searchDuckDuckGo start, url=$url")
        val html = executeRequest(url)
        val results = parseDuckDuckGoHtml(html)
        Log.d(TAG, "searchDuckDuckGo parsed, resultCount=${results.size}")
        return results
    }

    private fun searchBing(query: String): List<SearchResult> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encodedQuery&setlang=zh-Hans"
        Log.d(TAG, "searchBing start, url=$url")
        val html = executeRequest(url)
        val results = parseBingHtml(html)
        Log.d(TAG, "searchBing parsed, resultCount=${results.size}")
        return results
    }

    private fun fetchUrl(url: String): String {
        Log.d(TAG, "fetchUrl start, url=$url")
        val body = executeRequest(url)
        val title = extractTitle(body)
        val content = extractReadableContent(body)
        Log.d(TAG, "fetchUrl parsed, title=${title.take(80)}, contentLength=${content.length}")
        return buildString {
            appendLine("以下是 $url 的网页内容：")
            if (title.isNotBlank()) {
                appendLine("标题: $title")
            }
            appendLine()
            append(content)
        }.trim()
    }

    private fun executeRequest(url: String): String {
        val referer = try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            "https://www.google.com/"
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .header("sec-ch-ua", "\"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\", \"Google Chrome\";v=\"125\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "max-age=0")
            .build()

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "http request start, url=$url")
        client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.d(
                TAG,
                "http response, url=$url, code=${response.code}, successful=${response.isSuccessful}, elapsedMs=$elapsedMs"
            )
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}, url=$url")
            }
            val body = response.body?.string().orEmpty()
            Log.d(
                TAG,
                "http body read, url=$url, bodyLength=${body.length}, bodyPreview=${body.take(200).replace('\n', ' ')}"
            )
            return body
        }
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
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

        Log.d(
            TAG,
            "parseDuckDuckGoHtml done, htmlLength=${html.length}, resultCount=${results.size}, firstTitle=${results.firstOrNull()?.title.orEmpty().take(80)}"
        )
        return results
    }

    private fun parseBingHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val blockPattern = Pattern.compile(
            """<li[^>]+class="[^"]*\bb_algo\b[^"]*"[^>]*>(.*?)</li>""",
            Pattern.DOTALL
        )
        val titleInsideAnchorPattern = Pattern.compile(
            """<a[^>]*href="([^"]+)"[^>]*>\s*<h2[^>]*>(.*?)</h2>\s*</a>""",
            Pattern.DOTALL
        )
        val titleOutsideAnchorPattern = Pattern.compile(
            """<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>\s*</h2>""",
            Pattern.DOTALL
        )
        val snippetPattern = Pattern.compile(
            """<p[^>]*>(.*?)</p>""",
            Pattern.DOTALL
        )

        val blockMatcher = blockPattern.matcher(html)
        while (blockMatcher.find() && results.size < 10) {
            val block = blockMatcher.group(1) ?: continue
            val titleMatch = findBingTitleMatch(
                block,
                titleInsideAnchorPattern,
                titleOutsideAnchorPattern
            ) ?: continue

            val url = stripHtml(titleMatch.first)
            val title = stripHtml(titleMatch.second)
            val snippet = snippetPattern.matcher(block).run {
                if (find()) stripHtml(group(1) ?: "") else ""
            }

            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title, url, snippet))
            }
        }
        Log.d(
            TAG,
            "parseBingHtml done, htmlLength=${html.length}, resultCount=${results.size}, firstTitle=${results.firstOrNull()?.title.orEmpty().take(80)}"
        )
        return results
    }

    private fun findBingTitleMatch(
        block: String,
        titleInsideAnchorPattern: Pattern,
        titleOutsideAnchorPattern: Pattern
    ): Pair<String, String>? {
        val insideAnchorMatcher = titleInsideAnchorPattern.matcher(block)
        if (insideAnchorMatcher.find()) {
            return (insideAnchorMatcher.group(1) ?: "") to (insideAnchorMatcher.group(2) ?: "")
        }

        val outsideAnchorMatcher = titleOutsideAnchorPattern.matcher(block)
        if (outsideAnchorMatcher.find()) {
            return (outsideAnchorMatcher.group(1) ?: "") to (outsideAnchorMatcher.group(2) ?: "")
        }
        return null
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

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun extractTitle(html: String): String {
        val matcher = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
            .matcher(html)
        return if (matcher.find()) stripHtml(matcher.group(1) ?: "") else ""
    }

    private fun extractReadableContent(body: String): String {
        val normalized = body.trim()
        if (normalized.isBlank()) {
            return "网页内容为空"
        }
        if (!normalized.contains("<html", ignoreCase = true) && !normalized.contains("<body", ignoreCase = true)) {
            return normalized.take(MAX_DIRECT_CONTENT_LENGTH)
        }

        val withoutScripts = normalized
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        val text = stripHtml(withoutScripts)
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(MAX_DIRECT_CONTENT_LENGTH).ifBlank { "未提取到可读正文" }
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
