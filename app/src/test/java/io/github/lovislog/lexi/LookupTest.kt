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

    @Test
    fun oldEntriesReceiveLanguageAndSubtypeWhenRead() {
        fun legacy(text: String, answer: String, kind: String) = lexiJson.decodeFromString<LookupResult>(
            """{"input":{"text":"$text"},"explanation":{"intent":"UNDERSTAND","kind":"$kind","answerEn":"$answer","meaningZh":"示例","usageEn":"Example."}}""",
        )
        assertEquals(LibrarySubType.ZH_VOCAB, legacy("进位", "carry over", "PHRASE").subType)
        assertEquals(LibrarySubType.ZH_IDIOM, legacy("半途而废", "give up halfway", "PHRASE").subType)
        assertEquals(LibrarySubType.ZH_SENTENCE, legacy("船已经离港。", "The ship has left.", "SENTENCE").subType)
        assertEquals(LookupFormat.WORD, LookupInput("说曹操，曹操到").format)
        assertEquals(LookupFormat.WORD, LookupInput("我行我素").format)
        assertEquals(LibrarySubType.WORD, legacy("ship", "ship", "WORD").subType)
        assertEquals(LibrarySubType.PHRASE, legacy("carry over", "carry over", "PHRASE").subType)
        assertEquals(LibrarySubType.SENTENCE, legacy("The ship has left.", "The ship has left.", "SENTENCE").subType)
        val json = lexiJson.encodeToString(legacy("进位", "carry over", "PHRASE"))
        assertTrue(json.contains("\"lang_group\":\"zh\""))
        assertTrue(json.contains("\"sub_type\":\"zh_vocab\""))
    }

    @Test
    fun revisitingALinkedResultConsumesTheInterveningTrail() {
        fun location(input: LookupInput, historyOpen: Boolean = false) = AppLocation(
            Page.LOOKUP, historyOpen, false, input.text, input.context,
            LookupState.Ready(
                LookupResult(input, Explanation(LookupIntent.UNDERSTAND, ContentKind.WORD, input.text, "释义", "Usage.")),
            ),
            0,
        )
        val source = location(LookupInput("ship"))
        val first = location(LookupInput("harbor"))
        val differentContext = location(LookupInput("harbor", "A ship entered the harbor."))
        val history = location(LookupInput("harbor"), historyOpen = true)
        val trail = mutableListOf(source, first, differentContext, history)

        assertEquals(first, trail.popThrough { it.showsLookup(LookupInput("harbor")) })
        assertEquals(listOf(source), trail)
        assertNull(trail.popThrough { it.showsLookup(LookupInput("cargo")) })
        assertEquals(listOf(source), trail)
    }
}
