package com.wessamk.naatiq.speech

/** The two writing systems the reader distinguishes between. */
enum class TextScript { ARABIC, LATIN }

/**
 * A stretch of text that is spoken as one utterance.
 *
 * [start] and [end] are offsets into the *original* text, so the reader can highlight the
 * spoken words in place even when only a selection is being read.
 */
data class Segment(
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val script: TextScript,
)

/**
 * Splits text into sentence-sized utterances, and splits every sentence further whenever the
 * writing system changes, so an Arabic sentence with an English term in it is read by both the
 * Arabic and the English voice.
 */
object TextSegmenter {

    /** Utterances longer than this are split again; keeps highlighting responsive. */
    private const val MAX_SEGMENT_CHARS = 300

    private const val SENTENCE_ENDS = ".!?;:؟؛۔…。！？"
    private const val CLAUSE_ENDS = ",،؛-–—)]}»”"
    private const val TRAILING_MARKS = "\"'»”’)]}ـ"

    fun segment(
        source: CharSequence,
        from: Int = 0,
        to: Int = source.length,
        forcedScript: TextScript? = null,
    ): List<Segment> {
        val start = from.coerceIn(0, source.length)
        val end = to.coerceIn(start, source.length)
        if (start == end) return emptyList()

        val fallback = forcedScript ?: dominantScript(source, start, end)
        val out = ArrayList<Segment>()
        var cursor = start
        while (cursor < end) {
            val sentenceEnd = findSentenceEnd(source, cursor, end)
            appendScriptRuns(source, cursor, sentenceEnd, forcedScript, fallback, out)
            cursor = sentenceEnd
        }
        return mergeAbbreviations(source, out)
    }

    /**
     * Rejoins pieces that a full stop cut off mid-sentence — "Dr.", "e.g.", "قال:" — so the voice
     * does not pause after them.
     */
    private fun mergeAbbreviations(source: CharSequence, segments: List<Segment>): List<Segment> {
        if (segments.size < 2) return segments
        val merged = ArrayList<Segment>(segments.size)
        var pending: Segment? = null
        for (segment in segments) {
            val previous = pending
            pending = if (previous != null && canMerge(source, previous, segment)) {
                previous.copy(
                    text = source.subSequence(previous.start, segment.end).toString(),
                    end = segment.end,
                )
            } else {
                if (previous != null) merged.add(previous.copy(index = merged.size))
                segment
            }
        }
        pending?.let { merged.add(it.copy(index = merged.size)) }
        return merged
    }

    private fun canMerge(source: CharSequence, first: Segment, second: Segment): Boolean {
        if (first.script != second.script) return false
        if (first.end > second.start) return false
        if (first.text.length > 4) return false
        if (!first.text.endsWith('.') && !first.text.endsWith(':')) return false
        if (second.end - first.start > MAX_SEGMENT_CHARS) return false
        for (i in first.end until second.start) {
            if (source[i] == '\n' || !source[i].isWhitespace()) return false
        }
        return true
    }

    /** The writing system most of the letters in the window belong to. */
    fun dominantScript(source: CharSequence, from: Int = 0, to: Int = source.length): TextScript {
        var arabic = 0
        var latin = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(source.length)) {
            when (scriptOf(source[i])) {
                TextScript.ARABIC -> arabic++
                TextScript.LATIN -> latin++
                null -> Unit
            }
        }
        return if (arabic > latin) TextScript.ARABIC else TextScript.LATIN
    }

    /** `null` for characters that carry no writing system of their own, such as spaces or digits. */
    fun scriptOf(c: Char): TextScript? {
        val code = c.code
        val arabic = code in 0x0600..0x06FF || // Arabic
            code in 0x0750..0x077F || // Arabic Supplement
            code in 0x08A0..0x08FF || // Arabic Extended-A
            code in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
            code in 0xFE70..0xFEFF // Arabic Presentation Forms-B
        return when {
            arabic -> TextScript.ARABIC
            c.isLetter() -> TextScript.LATIN
            else -> null
        }
    }

    /** Index of the segment that contains [offset], or the closest one before it. */
    fun indexOfOffset(segments: List<Segment>, offset: Int): Int {
        if (segments.isEmpty()) return -1
        segments.forEachIndexed { index, segment ->
            if (offset < segment.end) return index
        }
        return segments.lastIndex
    }

    /**
     * Returns the exclusive end of the sentence starting at [from]. A line break always ends a
     * sentence; a full stop only does when followed by whitespace, so "3.5" or "google.com" stay
     * in one piece.
     */
    private fun findSentenceEnd(source: CharSequence, from: Int, limit: Int): Int {
        var i = from
        while (i < limit) {
            val c = source[i]
            if (c == '\n') return i + 1
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                var after = i + 1
                while (after < limit &&
                    (SENTENCE_ENDS.indexOf(source[after]) >= 0 || TRAILING_MARKS.indexOf(source[after]) >= 0)
                ) {
                    after++
                }
                if (after >= limit || source[after].isWhitespace()) return after
                i = after
                continue
            }
            i++
        }
        return limit
    }

    private fun appendScriptRuns(
        source: CharSequence,
        from: Int,
        to: Int,
        forcedScript: TextScript?,
        fallback: TextScript,
        out: MutableList<Segment>,
    ) {
        if (forcedScript != null) {
            appendChunks(source, from, to, forcedScript, out)
            return
        }
        var runStart = from
        var runScript: TextScript? = null
        var i = from
        while (i < to) {
            val script = scriptOf(source[i])
            if (script != null) {
                if (runScript == null) {
                    runScript = script
                } else if (script != runScript) {
                    appendChunks(source, runStart, i, runScript, out)
                    runStart = i
                    runScript = script
                }
            }
            i++
        }
        appendChunks(source, runStart, to, runScript ?: fallback, out)
    }

    /** Emits [from, to) as one or more utterances of at most [MAX_SEGMENT_CHARS] characters. */
    private fun appendChunks(
        source: CharSequence,
        from: Int,
        to: Int,
        script: TextScript,
        out: MutableList<Segment>,
    ) {
        var chunkStart = from
        while (chunkStart < to) {
            var chunkEnd = to
            if (to - chunkStart > MAX_SEGMENT_CHARS) {
                val hardLimit = chunkStart + MAX_SEGMENT_CHARS
                chunkEnd = lastBreakBefore(source, chunkStart, hardLimit) ?: hardLimit
            }
            emit(source, chunkStart, chunkEnd, script, out)
            chunkStart = chunkEnd
        }
    }

    /** Prefers a clause boundary, then a space, when a long run has to be cut. */
    private fun lastBreakBefore(source: CharSequence, from: Int, limit: Int): Int? {
        for (i in limit - 1 downTo from + 1) {
            if (CLAUSE_ENDS.indexOf(source[i]) >= 0) return i + 1
        }
        for (i in limit - 1 downTo from + 1) {
            if (source[i].isWhitespace()) return i + 1
        }
        return null
    }

    private fun emit(
        source: CharSequence,
        from: Int,
        to: Int,
        script: TextScript,
        out: MutableList<Segment>,
    ) {
        var start = from
        var end = to
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        if (start >= end) return
        val text = source.subSequence(start, end).toString()
        if (text.none { it.isLetterOrDigit() }) return // nothing speakable, e.g. a lone dash
        out.add(Segment(index = out.size, text = text, start = start, end = end, script = script))
    }
}
