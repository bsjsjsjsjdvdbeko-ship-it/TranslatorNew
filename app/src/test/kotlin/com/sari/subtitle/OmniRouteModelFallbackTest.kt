package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class OmniRouteModelFallbackTest {
    @Test
    fun primaryModelIsTriedFirstAndDuplicatesAreRemoved() {
        assertEquals(
            listOf("provider/primary", "provider/fallback", "provider/last"),
            omniRouteTranslationCandidates(" provider/primary ", "provider/primary, provider/fallback,,provider/last")
        )
    }

    @Test
    fun blankConfigurationUsesSafeDefaults() {
        assertEquals(
            listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b"),
            omniRouteTranslationCandidates(" ", " , ")
        )
    }
}
