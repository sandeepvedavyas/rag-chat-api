package com.sandeepvedavyas.rag.ingest

/**
 * Character window with soft breaks on paragraphs, markdown headings, lines, then spaces.
 * Overlap is measured in characters so the same inputs always produce the same chunks.
 */
class TextChunker(
    private val chunkSize: Int,
    private val overlap: Int,
) {
    init {
        require(chunkSize >= 1) { "chunk size must be positive" }
        require(overlap in 0 until chunkSize) { "overlap must be >= 0 and < chunk size" }
    }

    fun chunk(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        if (normalized.length <= chunkSize) {
            return listOf(normalized)
        }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = findEnd(normalized, start)
            if (end <= start) {
                end = minOf(start + chunkSize, normalized.length)
            }
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotEmpty()) {
                chunks += piece
            }
            if (end >= normalized.length) {
                break
            }
            val next = maxOf(end - overlap, start + 1)
            if (next <= start) {
                break
            }
            start = next
        }
        return chunks
    }

    private fun findEnd(text: String, start: Int): Int {
        val hard = minOf(start + chunkSize, text.length)
        if (hard == text.length) {
            return hard
        }
        val earliestBreak = start + chunkSize / 2
        val paragraph = text.lastIndexOf("\n\n", hard - 1)
        if (paragraph >= earliestBreak) {
            return paragraph
        }
        val heading = text.lastIndexOf("\n#", hard - 1)
        if (heading >= earliestBreak) {
            return heading
        }
        val line = text.lastIndexOf('\n', hard - 1)
        if (line >= earliestBreak) {
            return line
        }
        val space = text.lastIndexOf(' ', hard - 1)
        if (space >= earliestBreak) {
            return space
        }
        return hard
    }
}
