package com.cpaphone.core.disguise

/**
 * 敏感词零宽字符插入混淆器 (Cloak Obfuscator)
 * 对齐 CLIProxyAPI internal/runtime/executor/helps/cloak_obfuscate.go
 * 核心逻辑：
 * 1. 过滤词长小于 2 的单字符，按词长降序排序 (Longest First) 防止子词提前截断；
 * 2. 编译不区分大小写的单条复合正则；
 * 3. 在敏感词第一个字符（First Rune）之后插入 \u200B (Unicode Zero-Width Space)；
 * 4. 保持人类阅读与大模型语义理解无损，打碎字符串精确向量特征规避审核。
 */
class CloakObfuscator(
    sensitiveWords: List<String>
) {
    companion object {
        const val ZERO_WIDTH_SPACE = "\u200B"
    }

    private val matcherRegex: Regex?

    init {
        // 1. 过滤非法词汇并长度降序排序
        val validWords = sensitiveWords
            .map { it.trim() }
            .filter { it.length >= 2 && !it.contains(ZERO_WIDTH_SPACE) }
            .distinct()
            .sortedByDescending { it.length }

        if (validWords.isNotEmpty()) {
            val pattern = validWords.joinToString("|") { Regex.escape(it) }
            matcherRegex = Regex("(?i)($pattern)")
        } else {
            matcherRegex = null
        }
    }

    /**
     * 对普通纯文本进行敏感词混淆
     */
    fun obfuscateText(text: String): String {
        val regex = matcherRegex ?: return text
        if (text.isBlank()) return text

        return regex.replace(text) { matchResult ->
            obfuscateWord(matchResult.value)
        }
    }

    /**
     * 单词混淆：仅在首个字形之后插入 \u200B
     */
    fun obfuscateWord(word: String): String {
        if (word.length < 2 || word.contains(ZERO_WIDTH_SPACE)) {
            return word
        }
        val firstRune = word.take(1)
        val rest = word.substring(1)
        return firstRune + ZERO_WIDTH_SPACE + rest
    }
}
