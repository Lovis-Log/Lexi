package io.github.lovislog.lexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTest {
    @Test
    fun whitespaceNormalizesButContextAndCaseStayDistinct() {
        val bare = LookupInput("charge").normalized()
        assertEquals(bare.identity, LookupInput("  charge\n ").normalized().identity)
        assertNotEquals(bare.identity, LookupInput("charge", "They charge for delivery.").normalized().identity)
        assertNotEquals(LookupInput("US").identity, LookupInput("us").identity)
    }

    @Test
    fun endpointPreservesProviderPathAndRejectsEmbeddedCredentials() {
        assertEquals("https://api.deepseek.com/chat/completions", endpoint("https://api.deepseek.com/").toString())
        assertEquals("https://example.com/v1/chat/completions", endpoint("https://example.com/v1").toString())
        assertEquals("https://example.com/v1/chat/completions", endpoint("https://example.com/v1/chat/completions/").toString())
        assertNull(endpoint("http://example.com"))
        assertNull(endpoint("https://user:pass@example.com"))
        assertNull(endpoint("https://example.com?token=example"))
    }

    @Test
    fun aMultiwordPhraseCanBeSavedWithoutWordSenses() {
        val explanation = Explanation(
            LookupIntent.UNDERSTAND, ContentKind.PHRASE,
            "get away with", "未受惩罚", "Use this when someone avoids a consequence.",
        )
        assertTrue(explanation.isValid())
        assertFalse(explanation.copy(kind = ContentKind.WORD).isValid())
        assertFalse(explanation.copy(meaningZh = "").isValid())
    }
}
