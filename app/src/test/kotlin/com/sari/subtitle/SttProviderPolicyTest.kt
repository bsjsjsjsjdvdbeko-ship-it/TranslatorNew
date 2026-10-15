package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class SttProviderPolicyTest {
    @Test fun aliasesResolveToTheIntendedProvider() {
        assertEquals("deepgram-nova3", SttProviderPolicy.normalize("deepgram-nova3"))
        assertEquals("deepgram-nova3", SttProviderPolicy.normalize(" Deepgram_Nova3 "))
        assertEquals("deepgram-nova2", SttProviderPolicy.normalize("deepgram"))
        assertEquals("groq", SttProviderPolicy.normalize("Groq Whisper"))
    }

    @Test fun unknownProviderDoesNotSilentlyFallBackToGroq() {
        assertEquals("custom-stt", SttProviderPolicy.normalize("custom-stt"))
    }
}
