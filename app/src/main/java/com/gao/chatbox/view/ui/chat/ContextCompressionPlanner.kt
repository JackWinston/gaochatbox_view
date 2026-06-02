package com.gao.chatbox.view.ui.chat

import com.gao.chatbox.view.data.remote.OpenAiChatMessage
import com.gao.chatbox.view.data.repository.MessageContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ContextCompressionPlanner {

    data class CompressionReport(
        val contextLimit: Int,
        val inputBudget: Int,
        val estimatedInputTokens: Int,
        val rawHistoryMessages: Int,
        val keptHistoryMessages: Int,
        val summarizedHistoryMessages: Int,
        val attachmentStrategy: String,
        val hasImageAttachment: Boolean,
        val notes: List<String>
    )

    data class PlannedInitialRequest(
        val userMessage: String,
        val history: List<MessageContext>,
        val report: CompressionReport
    )

    data class PlannedToolRequest(
        val history: List<OpenAiChatMessage>,
        val report: CompressionReport
    )

    private data class ConversationMessage(
        val role: String,
        val content: String
    )

    private data class HistoryPlan(
        val messages: List<ConversationMessage>,
        val rawMessageCount: Int,
        val keptMessageCount: Int,
        val summarizedMessageCount: Int
    )

    private data class AttachmentPlan(
        val message: String,
        val strategy: String
    )

    fun planInitialRequest(
        previousItems: List<ChatItem>,
        text: String,
        attachmentName: String?,
        fileContent: String?,
        systemPrompt: String,
        contextLimit: Int,
        enableWebSearch: Boolean,
        hasImageAttachment: Boolean
    ): PlannedInitialRequest {
        val normalizedContextLimit = contextLimit.coerceAtLeast(8_192)
        val historyMessages = extractConversationMessages(previousItems)
        val baseBudget = calculateInputBudget(
            contextLimit = normalizedContextLimit,
            enableWebSearch = enableWebSearch,
            hasImageAttachment = hasImageAttachment,
            reservedExtraTokens = 0
        )
        val userBudget = max(768, min(baseBudget / 2, 24_576))
        val attachmentPlan = buildUserMessage(
            text = text,
            attachmentName = attachmentName,
            fileContent = fileContent,
            budgetTokens = userBudget
        )
        val systemTokens = estimateTokens(systemPrompt)
        val userTokens = estimateTokens(attachmentPlan.message)
        val historyBudget = max(512, baseBudget - systemTokens - userTokens)
        val historyPlan = compressHistory(historyMessages, historyBudget)
        val estimatedInputTokens = systemTokens + userTokens + estimateTokens(historyPlan.messages)
        val notes = mutableListOf<String>()
        if (historyPlan.summarizedMessageCount > 0) {
            notes += "较早历史已压缩为摘要"
        }
        if (attachmentPlan.strategy != "full" && attachmentPlan.strategy != "none") {
            notes += "文本附件已按预算压缩"
        }
        return PlannedInitialRequest(
            userMessage = attachmentPlan.message,
            history = historyPlan.messages.map { MessageContext(role = it.role, content = it.content) },
            report = CompressionReport(
                contextLimit = normalizedContextLimit,
                inputBudget = baseBudget,
                estimatedInputTokens = estimatedInputTokens,
                rawHistoryMessages = historyPlan.rawMessageCount,
                keptHistoryMessages = historyPlan.keptMessageCount,
                summarizedHistoryMessages = historyPlan.summarizedMessageCount,
                attachmentStrategy = attachmentPlan.strategy,
                hasImageAttachment = hasImageAttachment,
                notes = notes
            )
        )
    }

    fun planToolFollowUp(
        historyItems: List<ChatItem>,
        systemPrompt: String,
        contextLimit: Int,
        enableWebSearch: Boolean,
        reservedTexts: List<String>
    ): PlannedToolRequest {
        val normalizedContextLimit = contextLimit.coerceAtLeast(8_192)
        val historyMessages = extractConversationMessages(historyItems)
        val reservedExtraTokens = reservedTexts.sumOf { estimateTokens(it) }
        val baseBudget = calculateInputBudget(
            contextLimit = normalizedContextLimit,
            enableWebSearch = enableWebSearch,
            hasImageAttachment = false,
            reservedExtraTokens = reservedExtraTokens
        )
        val systemTokens = estimateTokens(systemPrompt)
        val historyBudget = max(512, baseBudget - systemTokens)
        val historyPlan = compressHistory(historyMessages, historyBudget)
        val messages = mutableListOf<OpenAiChatMessage>()
        if (systemPrompt.isNotBlank()) {
            messages += OpenAiChatMessage(role = "system", content = systemPrompt)
        }
        historyPlan.messages.forEach { message ->
            messages += OpenAiChatMessage(role = message.role, content = message.content)
        }
        val estimatedInputTokens = systemTokens + estimateTokens(historyPlan.messages) + reservedExtraTokens
        val notes = mutableListOf<String>()
        if (historyPlan.summarizedMessageCount > 0) {
            notes += "工具续轮前已压缩较早历史"
        }
        return PlannedToolRequest(
            history = messages,
            report = CompressionReport(
                contextLimit = normalizedContextLimit,
                inputBudget = baseBudget,
                estimatedInputTokens = estimatedInputTokens,
                rawHistoryMessages = historyPlan.rawMessageCount,
                keptHistoryMessages = historyPlan.keptMessageCount,
                summarizedHistoryMessages = historyPlan.summarizedMessageCount,
                attachmentStrategy = "none",
                hasImageAttachment = false,
                notes = notes
            )
        )
    }

    private fun calculateInputBudget(
        contextLimit: Int,
        enableWebSearch: Boolean,
        hasImageAttachment: Boolean,
        reservedExtraTokens: Int
    ): Int {
        val outputReserve = max(4_096, min(contextLimit / 8, 16_384))
        val toolReserve = if (enableWebSearch) 4_096 else 1_024
        val imageReserve = if (hasImageAttachment) 3_072 else 0
        val safetyReserve = max(1_024, contextLimit / 20)
        val inputBudget = contextLimit - outputReserve - toolReserve - imageReserve - safetyReserve - reservedExtraTokens
        return inputBudget.coerceAtLeast(1_024)
    }

    private fun extractConversationMessages(items: List<ChatItem>): List<ConversationMessage> {
        val messages = mutableListOf<ConversationMessage>()
        items.forEach { item ->
            when (item) {
                is ChatItem.UserMessage -> messages += ConversationMessage("user", item.requestContent)
                is ChatItem.AssistantMessage -> {
                    if (item.content.isNotBlank()) {
                        messages += ConversationMessage("assistant", item.content)
                    }
                }
                else -> Unit
            }
        }
        return messages
    }

    private fun buildUserMessage(
        text: String,
        attachmentName: String?,
        fileContent: String?,
        budgetTokens: Int
    ): AttachmentPlan {
        if (fileContent == null) {
            val message = if (text.isNotBlank()) {
                truncateToBudget(text, budgetTokens)
            } else {
                "请查看附件内容。"
            }
            return AttachmentPlan(message = message, strategy = "none")
        }

        val prompt = text.ifBlank { "请结合附件文件内容进行处理。" }
        val fileHeader = "[附件文件: ${attachmentName ?: "attachment.txt"}]"
        val fullMessage = buildString {
            append(prompt)
            append("\n\n")
            append(fileHeader)
            append("\n")
            append(fileContent)
        }
        if (estimateTokens(fullMessage) <= budgetTokens) {
            return AttachmentPlan(message = fullMessage, strategy = "full")
        }

        val compressed = compressFileContent(
            prompt = prompt,
            attachmentName = attachmentName ?: "attachment.txt",
            fileContent = fileContent,
            budgetTokens = budgetTokens
        )
        return AttachmentPlan(message = compressed, strategy = "excerpt")
    }

    private fun compressHistory(history: List<ConversationMessage>, budgetTokens: Int): HistoryPlan {
        if (history.isEmpty() || budgetTokens <= 0) {
            return HistoryPlan(
                messages = emptyList(),
                rawMessageCount = history.size,
                keptMessageCount = 0,
                summarizedMessageCount = 0
            )
        }

        val rawTokens = estimateTokens(history)
        if (rawTokens <= budgetTokens) {
            return HistoryPlan(
                messages = history,
                rawMessageCount = history.size,
                keptMessageCount = history.size,
                summarizedMessageCount = 0
            )
        }

        val reserveForSummary = min(2_048, max(256, budgetTokens / 6))
        val keptReversed = mutableListOf<ConversationMessage>()
        var usedTokens = 0
        history.asReversed().forEachIndexed { index, message ->
            val remainingOldMessages = history.size - keptReversed.size - 1
            val reserve = if (remainingOldMessages > 0) reserveForSummary else 0
            val cost = estimateTokensWithRole(message)
            if (index > 0 && usedTokens + cost + reserve > budgetTokens) {
                return@forEachIndexed
            }
            if (usedTokens + cost > budgetTokens) {
                return@forEachIndexed
            }
            keptReversed += message
            usedTokens += cost
        }

        val keptMessages = keptReversed.asReversed()
        val summarizedCount = history.size - keptMessages.size
        if (summarizedCount <= 0) {
            return HistoryPlan(
                messages = keptMessages,
                rawMessageCount = history.size,
                keptMessageCount = keptMessages.size,
                summarizedMessageCount = 0
            )
        }

        val olderMessages = history.take(summarizedCount)
        val summaryBudget = max(192, budgetTokens - estimateTokens(keptMessages))
        val summary = buildHistorySummary(olderMessages, summaryBudget)
        val plannedMessages = mutableListOf<ConversationMessage>()
        if (summary.isNotBlank()) {
            plannedMessages += ConversationMessage(role = "assistant", content = summary)
        }
        plannedMessages += keptMessages

        while (plannedMessages.isNotEmpty() &&
            estimateTokens(plannedMessages) > budgetTokens &&
            plannedMessages.size > 1
        ) {
            val removableIndex = plannedMessages.indexOfFirst {
                it.role != "assistant" || !it.content.startsWith("[较早历史摘要]")
            }
            if (removableIndex <= 0) {
                break
            }
            plannedMessages.removeAt(removableIndex)
        }

        if (plannedMessages.isNotEmpty() && estimateTokens(plannedMessages) > budgetTokens) {
            val summaryMessage = plannedMessages.first()
            if (summaryMessage.role == "assistant" && summaryMessage.content.startsWith("[较早历史摘要]")) {
                plannedMessages[0] = summaryMessage.copy(
                    content = truncateToBudget(summaryMessage.content, max(160, budgetTokens / 2))
                )
            }
        }

        return HistoryPlan(
            messages = plannedMessages,
            rawMessageCount = history.size,
            keptMessageCount = plannedMessages.count { !it.content.startsWith("[较早历史摘要]") },
            summarizedMessageCount = summarizedCount
        )
    }

    private fun buildHistorySummary(
        messages: List<ConversationMessage>,
        budgetTokens: Int
    ): String {
        if (messages.isEmpty() || budgetTokens <= 0) return ""

        val userSnippets = messages
            .filter { it.role == "user" }
            .takeLast(3)
            .map { condenseMessage(it.content, 220) }

        val assistantSnippets = messages
            .filter { it.role == "assistant" }
            .takeLast(3)
            .map { condenseMessage(it.content, 220) }

        val summary = buildString {
            append("[较早历史摘要]\n")
            append("- 已压缩消息数: ${messages.size}\n")
            if (userSnippets.isNotEmpty()) {
                append("- 较早用户诉求:\n")
                userSnippets.forEach { append("  - ").append(it).append('\n') }
            }
            if (assistantSnippets.isNotEmpty()) {
                append("- 较早助手结论:\n")
                assistantSnippets.forEach { append("  - ").append(it).append('\n') }
            }
        }.trim()

        return truncateToBudget(summary, budgetTokens)
    }

    private fun compressFileContent(
        prompt: String,
        attachmentName: String,
        fileContent: String,
        budgetTokens: Int
    ): String {
        val lines = fileContent.lines()
        val keywords = extractKeywords(prompt)
        val headLines = lines.take(80)
        val tailLines = lines.takeLast(40)
        val matchedIndexes = lines.mapIndexedNotNull { index, line ->
            val matched = keywords.any { keyword ->
                keyword.isNotBlank() && line.contains(keyword, ignoreCase = true)
            }
            if (matched) index else null
        }
        val excerptIndexes = linkedSetOf<Int>()
        matchedIndexes.take(24).forEach { index ->
            val start = max(0, index - 2)
            val end = min(lines.lastIndex, index + 2)
            for (lineIndex in start..end) {
                excerptIndexes += lineIndex
            }
        }
        val excerptLines = excerptIndexes.toList().sorted().take(120).map { index ->
            "${index + 1}: ${lines[index]}"
        }

        val summary = buildString {
            append(prompt)
            append("\n\n")
            append("[附件文件: ").append(attachmentName).append("]\n")
            append("[文件摘要]\n")
            append("- 总行数: ").append(lines.size).append('\n')
            append("- 总字符数: ").append(fileContent.length).append('\n')
            append("- 已按上下文预算截取关键片段，优先保留头部、尾部和与当前问题相关的行。\n")
            if (keywords.isNotEmpty()) {
                append("- 关键词: ").append(keywords.joinToString(", ")).append('\n')
            }
            append('\n')
            append("[头部片段]\n")
            append(headLines.joinToString("\n"))
            if (excerptLines.isNotEmpty()) {
                append("\n\n[命中片段]\n")
                append(excerptLines.joinToString("\n"))
            }
            append("\n\n[尾部片段]\n")
            append(tailLines.joinToString("\n"))
        }

        return truncateToBudget(summary, budgetTokens)
    }

    private fun extractKeywords(prompt: String): List<String> {
        val cleaned = prompt
            .replace("\n", " ")
            .split(Regex("[\\s,.;:!?()\\[\\]{}<>\"'`/\\\\|]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

        val prioritized = cleaned.filter { token ->
            token.any { ch -> ch.code > 127 } || token.length >= 4
        }
        return prioritized.take(8)
    }

    private fun condenseMessage(text: String, maxChars: Int): String {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .replace("```", "")
            .trim()
        return if (normalized.length <= maxChars) normalized else {
            normalized.take(maxChars - 24) + "...(已截断)"
        }
    }

    private fun truncateToBudget(text: String, budgetTokens: Int): String {
        if (text.isBlank()) return text
        if (estimateTokens(text) <= budgetTokens) return text

        val approxChars = max(160, budgetTokens * 3)
        if (text.length <= approxChars) return text

        val headSize = max(96, approxChars * 2 / 3)
        val tailSize = max(48, approxChars / 3)
        val head = text.take(min(headSize, text.length))
        val tail = text.takeLast(min(tailSize, text.length - head.length))
        return buildString {
            append(head.trimEnd())
            append("\n\n[内容已按上下文预算截断]\n\n")
            append(tail.trimStart())
        }
    }

    private fun estimateTokens(messages: List<ConversationMessage>): Int {
        return messages.sumOf { estimateTokensWithRole(it) }
    }

    private fun estimateTokensWithRole(message: ConversationMessage): Int {
        return estimateTokens(message.content) + 6
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var score = 0.0
        text.forEach { ch ->
            score += when {
                ch == '\n' -> 0.25
                ch.code <= 127 -> 0.25
                else -> 1.0
            }
        }
        return ceil(score).toInt()
    }
}
