package com.wessamk.naatiq.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun `splits english sentences`() {
        val text = "Hello there. How are you? Fine!"
        val segments = TextSegmenter.segment(text)
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), segments.map { it.text })
        assertTrue(segments.all { it.script == TextScript.LATIN })
    }

    @Test
    fun `keeps decimals and domains together`() {
        val segments = TextSegmenter.segment("Version 3.5 is on example.com now. Done.")
        assertEquals(listOf("Version 3.5 is on example.com now.", "Done."), segments.map { it.text })
    }

    @Test
    fun `offsets point back into the original text`() {
        val text = "First one. Second one."
        val segments = TextSegmenter.segment(text)
        segments.forEach { segment ->
            assertEquals(segment.text, text.substring(segment.start, segment.end))
        }
    }

    @Test
    fun `splits arabic sentences on arabic punctuation`() {
        val text = "مرحبا بك في التطبيق. كيف حالك؟ بخير."
        val segments = TextSegmenter.segment(text)
        assertEquals(3, segments.size)
        assertTrue(segments.all { it.script == TextScript.ARABIC })
    }

    @Test
    fun `separates arabic and latin runs inside one sentence`() {
        val text = "هذا تطبيق Android جديد."
        val segments = TextSegmenter.segment(text)
        assertEquals(listOf(TextScript.ARABIC, TextScript.LATIN, TextScript.ARABIC), segments.map { it.script })
        assertEquals(listOf("هذا تطبيق", "Android", "جديد."), segments.map { it.text })
    }

    @Test
    fun `honours a forced script`() {
        val text = "هذا تطبيق Android جديد."
        val segments = TextSegmenter.segment(text, forcedScript = TextScript.ARABIC)
        assertEquals(1, segments.size)
        assertEquals(TextScript.ARABIC, segments.first().script)
    }

    @Test
    fun `reads only the requested slice`() {
        val text = "Skip this. Read this."
        val segments = TextSegmenter.segment(text, from = 11, to = text.length)
        assertEquals(listOf("Read this."), segments.map { it.text })
        assertEquals(11, segments.first().start)
    }

    @Test
    fun `splits very long sentences into speakable chunks`() {
        val text = "word ".repeat(200).trim() + "."
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 300 })
        assertEquals(text.replace(" ", ""), segments.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun `drops fragments with nothing to say`() {
        val segments = TextSegmenter.segment("Hi.\n\n---\n\nBye.")
        assertEquals(listOf("Hi.", "Bye."), segments.map { it.text })
    }

    @Test
    fun `line breaks end a sentence`() {
        val segments = TextSegmenter.segment("Line one\nLine two")
        assertEquals(listOf("Line one", "Line two"), segments.map { it.text })
    }

    @Test
    fun `dominant script wins for mixed text`() {
        assertEquals(TextScript.ARABIC, TextSegmenter.dominantScript("مرحبا بك يا صديقي Android"))
        assertEquals(TextScript.LATIN, TextSegmenter.dominantScript("Hello مرحبا everyone here"))
    }

    @Test
    fun `keeps short abbreviations with their sentence`() {
        val segments = TextSegmenter.segment("Dr. Smith went home. He slept.")
        assertEquals(listOf("Dr. Smith went home.", "He slept."), segments.map { it.text })
    }

    @Test
    fun `keeps an arabic colon with what follows it`() {
        val text = "قال: «أهلاً وسهلاً». ثم رحل."
        val segments = TextSegmenter.segment(text)
        assertEquals(listOf("قال: «أهلاً وسهلاً».", "ثم رحل."), segments.map { it.text })
        segments.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
    }

    @Test
    fun `finds the segment holding an offset`() {
        val text = "First one. Second one. Third one."
        val segments = TextSegmenter.segment(text)
        assertEquals(0, TextSegmenter.indexOfOffset(segments, 0))
        assertEquals(1, TextSegmenter.indexOfOffset(segments, 12))
        assertEquals(2, TextSegmenter.indexOfOffset(segments, text.length - 1))
    }
}
