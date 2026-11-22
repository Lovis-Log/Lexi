package io.github.lovislog.lexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTest {
    @Test
    fun ipaFormatsLegacyStressLengthAndAlternativeTranscriptions() {
        assertEquals("/ˌpɝː ˈseɪ/", formatIpa("／ ,pɝ:  ’seɪ ／"))
        assertEquals("/ˌpɜː ˈseɪ/", formatIpa("//ˌpɜː ˈseɪ//"))
        assertEquals("[n̥] · /n/", formatIpa(" [n̥] /n/ "))
        assertEquals("", formatIpa("/ — /"))
        assertEquals("", formatIpa("N/A"))
    }

    @Test
    fun whitespaceNormalizesButContextAndCaseStayDistinct() {
        val bare = LookupInput("charge").normalized()
        assertEquals(bare.identity, LookupInput("  charge\n ").normalized().identity)
        assertNotEquals(bare.identity, LookupInput("charge", "They charge for delivery.").normalized().identity)
        assertNotEquals(LookupInput("US").identity, LookupInput("us").identity)
        val source = "They argued  but soon made up."
        assertEquals(source, LookupInput("make up", source).normalized().context)
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

    @Test
    fun legacyLookupRemainsReadableWhileDetailedResultsSupportSeveralExamples() {
        val legacy = lexiJson.decodeFromString<Explanation>(
            """{"intent":"UNDERSTAND","kind":"WORD","answerEn":"charge","meaningZh":"收费","usageEn":"Ask for payment.","ipa":"/tʃɑːrdʒ/","senses":[{"pos":"v.","en":"Ask for payment.","zh":"收费"}],"examples":[{"en":"They charge a fee.","zh":"他们收费。"}]}""",
        )
        assertTrue(legacy.isValid())
        assertEquals("", legacy.ipaUk)
        assertEquals("", legacy.examples.single().scene)
        val detailed = legacy.copy(
            ipaUs = "/tʃɑrdʒ/",
            ipaUk = "/tʃɑːdʒ/",
            collocations = listOf(Alternative("charge a fee", "Require a payment.")),
            examples = List(4) { Example("They charge a fee.", "他们收费。", "Scenario $it") },
        )
        assertTrue(detailed.isValid())
        assertEquals("Scenario 3", detailed.examples.last().scene)
    }
}
