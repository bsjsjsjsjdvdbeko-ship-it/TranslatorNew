package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SttLanguagePolicyTest {
    @Test fun compositeLegacyLanguageUsesFirstSourceLanguage() {
        assertEquals("en", SttLanguagePolicy.forGroq(" en/en/ar "))
        assertEquals("en", SttLanguagePolicy.forDeepgram("en/en/ar"))
    }

    @Test fun automaticLanguageIsOmitted() {
        assertNull(SttLanguagePolicy.forGroq("auto"))
        assertNull(SttLanguagePolicy.forDeepgram("auto"))
        assertNull(SttLanguagePolicy.forGroq("multi"))
    }

    @Test fun regionTagsAreNormalizedForGroqAndPreservedForDeepgram() {
        assertEquals("en", SttLanguagePolicy.forGroq("en-US"))
        assertEquals("en-us", SttLanguagePolicy.forDeepgram("en-US"))
    }
}
