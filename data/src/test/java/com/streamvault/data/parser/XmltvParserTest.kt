package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [XmltvParser].
 *
 * All tests operate on in-memory XML strings.
 *
 * On the JVM test runner there is no Android XmlPullParser implementation registered
 * by default. We register kxml2 (a JVM-compatible XmlPull implementation) in setUp()
 * via the standard XmlPullParserFactory system property.
 *
 * Time reference: 2025-01-01 12:00:00 UTC = 1_735_732_800_000 ms epoch
 *                 2025-01-01 09:00:00 UTC = 1_735_722_000_000 ms epoch
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XmltvParserTest {

    private lateinit var parser: XmltvParser

    @Before
    fun setUp() {
        // Register kxml2 so XmlPullParserFactory.newInstance() works on the JVM.
        System.setProperty(
            "org.xmlpull.v1.XmlPullParserFactory",
            "org.kxml2.io.KXmlParser"
        )
        parser = XmltvParser()
    }

    // ── Happy path ────────────────────────────────────────────────

    @Test
    fun `parse_validXmltv_returnsPrograms`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Morning News</title>
                <desc>Today's news</desc>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Tech Hour</title>
              </programme>
              <programme start="20250101140000 +0000" stop="20250101150000 +0000" channel="ch2">
                <title>Weather Report</title>
                <desc>Forecast</desc>
              </programme>
              <programme start="20250101150000 +0000" stop="20250101160000 +0000" channel="ch2">
                <title>Top Stories</title>
              </programme>
              <programme start="20250101160000 +0000" stop="20250101170000 +0000" channel="ch3">
                <title>Documentary</title>
                <desc>Natural World</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(5)

        val first = programs[0]
        assertThat(first.channelId).isEqualTo("ch1")
        assertThat(first.title).isEqualTo("Morning News")
        assertThat(first.description).isEqualTo("Today's news")
        assertThat(first.startTime).isGreaterThan(0L)
        assertThat(first.endTime).isGreaterThan(first.startTime)
    }

    @Test
    fun `parse_multipleDateFormats_allParsed`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Format One</title>
              </programme>
              <programme start="20250101140000" stop="20250101150000" channel="ch1">
                <title>Format Two</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(2)
        // Both programs should have non-zero timestamps
        programs.forEach { prog ->
            assertThat(prog.startTime).isGreaterThan(0L)
            assertThat(prog.endTime).isGreaterThan(prog.startTime)
        }
    }

    @Test
    fun `parse_timezoneOffset_correctUtc`() {
        // 2025-01-01 12:00:00 +03:00 == 2025-01-01 09:00:00 UTC
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0300" stop="20250101130000 +0300" channel="ch1">
                <title>Timezone Test</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).hasSize(1)
        val prog = programs[0]

        // Expected: 2025-01-01 09:00:00 UTC
        // = 2025-01-01 00:00:00 UTC (1_735_689_600_000) + 9h (32_400_000) = 1_735_722_000_000
        val expected09UTC = 1_735_722_000_000L
        assertThat(prog.startTime).isEqualTo(expected09UTC)
    }

    @Test
    fun `parse_emptyStream_returnsEmpty`() {
        val xml = """<?xml version="1.0"?><tv></tv>"""

        val programs = parser.parse(xml.byteInputStream())

        assertThat(programs).isEmpty()
    }

    // ── Defensive / error handling ────────────────────────────────

    @Test
    fun `parse_missingTitle_skipsProgram`() {
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <desc>No title here at all</desc>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Valid Program</title>
              </programme>
            </tv>
        """.trimIndent()

        val programs = parser.parse(xml.byteInputStream())

        // Only the program with a title should be returned
        assertThat(programs).hasSize(1)
        assertThat(programs[0].title).isEqualTo("Valid Program")
    }

    @Test
    fun `parse_malformedXml_returnsPartialResults`() {
        // Valid programs first, then broken XML at the end
        val xml = """
            <?xml version="1.0"?>
            <tv>
              <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="ch1">
                <title>Good Program 1</title>
              </programme>
              <programme start="20250101130000 +0000" stop="20250101140000 +0000" channel="ch1">
                <title>Good Program 2</title>
              </programme>
              <programme start="20250101140000 +0000" stop="BROKEN_XML_FOLLOWS
        """.trimIndent() // deliberately truncated / broken

        val programs = parser.parse(xml.byteInputStream())

        // XmltvParser has a try/catch that returns partial results
        // At minimum, no exception should propagate
        assertThat(programs).isNotNull()
        // Depending on where the parser breaks, we expect 0–2 programs (not a crash)
        assertThat(programs.size).isAtMost(2)
    }

      @Test
      fun `parse_sameInstance_isStableAcrossConcurrentCalls`() = runTest {
        val xml = """
          <?xml version="1.0"?>
          <tv>
            <programme start="20250101120000 +0300" stop="20250101130000 +0300" channel="ch1">
            <title>Concurrent Parse</title>
            </programme>
          </tv>
        """.trimIndent()

        val timestamps = (1..64).map {
          async(Dispatchers.Default) {
            parser.parse(xml.byteInputStream()).single().startTime
          }
        }.awaitAll()

        assertThat(timestamps.distinct()).containsExactly(1_735_722_000_000L)
      }
}
