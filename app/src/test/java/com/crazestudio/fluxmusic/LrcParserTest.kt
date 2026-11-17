package com.crazestudio.fluxmusic

import com.crazestudio.fluxmusic.data.model.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesSyncedTimestampsAndSortsMultiTagLines() {
        val sample = """
            [00:12.50] First line
            [00:05.10][01:02.00] Repeated chorus
            [00:25.123] Millis precision
        """.trimIndent()

        val lines = LrcParser.parse(sample)
        assertEquals(4, lines.size)
        assertEquals(5_100L, lines[0].timeMs)
        assertEquals("Repeated chorus", lines[0].text)
        assertEquals(12_500L, lines[1].timeMs)
        assertEquals("First line", lines[1].text)
        assertEquals(25_123L, lines[2].timeMs)
        assertEquals("Millis precision", lines[2].text)
        assertEquals(62_000L, lines[3].timeMs)
        assertEquals("Repeated chorus", lines[3].text)
    }

    @Test
    fun findsActiveLineForPlaybackPosition() {
        val lines = LrcParser.parse(
            """
            [00:10.00] Line one
            [00:20.00] Line two
            [00:35.50] Line three
            """.trimIndent()
        )
        assertEquals(-1, LrcParser.activeLineIndex(lines, 2_000L))
        assertEquals(0, LrcParser.activeLineIndex(lines, 10_000L))
        assertEquals(1, LrcParser.activeLineIndex(lines, 24_500L))
        assertEquals(2, LrcParser.activeLineIndex(lines, 40_000L))
    }
}
