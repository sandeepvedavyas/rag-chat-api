package com.sandeepvedavyas.rag.ingest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TextChunkerTest {
    @Test
    fun `empty input yields no chunks`() {
        assertThat(TextChunker(100, 10).chunk("  \n\t")).isEmpty()
    }

    @Test
    fun `short input stays one chunk`() {
        assertThat(TextChunker(100, 10).chunk("hello world")).containsExactly("hello world")
    }

    @Test
    fun `windows a long token with the configured overlap`() {
        val chunks = TextChunker(10, 4).chunk("abcdefghijKLMNOPQRST")

        assertThat(chunks).containsExactly(
            "abcdefghij",
            "ghijKLMNOP",
            "MNOPQRST",
        )
        assertThat(chunks[1]).startsWith(chunks[0].takeLast(4))
        assertThat(chunks[2]).startsWith(chunks[1].takeLast(4))
    }

    @Test
    fun `single character windows still terminate`() {
        assertThat(TextChunker(1, 0).chunk("abc")).containsExactly("a", "b", "c")
    }

    @Test
    fun `keeps a markdown heading with the section that follows it when they fit`() {
        val text = "# Alpha\n\nalpha body stays with the heading"
        assertThat(TextChunker(200, 20).chunk(text)).containsExactly(text)
    }

    @Test
    fun `splits before a later heading once the window fills`() {
        val text = buildString {
            append("# Alpha\n\n")
            append("alpha ".repeat(30))
            append("\n\n# Beta\n\n")
            append("beta ".repeat(30))
        }
        val chunks = TextChunker(120, 20).chunk(text)

        assertThat(chunks.first()).contains("# Alpha")
        assertThat(chunks.any { it.contains("# Beta") }).isTrue()
        assertThat(chunks).allSatisfy { chunk -> assertThat(chunk.length).isLessThanOrEqualTo(120) }
    }

    @Test
    fun `rejects an overlap that is not smaller than the chunk`() {
        assertThatThrownBy { TextChunker(10, 10) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
