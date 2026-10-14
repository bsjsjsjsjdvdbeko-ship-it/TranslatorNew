package com.sari.subtitle

/** Ordered, de-duplicated model candidates used when simple OmniRoute routing is selected. */
internal fun omniRouteTranslationCandidates(primaryModel: String, configuredModels: String): List<String> {
    val candidates = buildList {
        primaryModel.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        configuredModels.split(',').map { it.trim() }
            .filter { it.isNotBlank() }.forEach { add(it) }
    }.distinct()
    return candidates.ifEmpty { listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b") }
}
