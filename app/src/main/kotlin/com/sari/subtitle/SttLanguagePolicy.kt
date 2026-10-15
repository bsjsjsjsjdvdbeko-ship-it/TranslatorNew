package com.sari.subtitle

/** Converts legacy UI values such as "en/en/ar" into a valid single STT language code.
 * The first token is the source speech language; later tokens are not STT parameters.
 */
internal object SttLanguagePolicy {
    private val valid = Regex("(?i)^[a-z]{2,3}([_-][a-z]{2,8})?$")

    fun forGroq(value: String): String? {
        val token = firstToken(value) ?: return null
        if (token.equals("multi", true)) return null
        if (!valid.matches(token)) return null
        // Groq documents ISO-639-1 language codes; regional tags should be reduced to the base.
        return token.substringBefore('-').substringBefore('_').lowercase().takeIf { it.length == 2 }
    }

    fun forDeepgram(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.equals("auto", true)) return null
        if (trimmed.equals("multi", true)) return "multi"
        val token = firstToken(trimmed) ?: return null
        if (!valid.matches(token)) return null
        // Nova-3 supports multilingual code-switching via language=multi, but only for its
        // published language set. For composite legacy values preserve the first/source code.
        return token.replace('_', '-').lowercase()
    }

    private fun firstToken(value: String): String? = value.trim()
        .split('/', ',', ';', '|')
        .firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !it.equals("auto", true) }
}
