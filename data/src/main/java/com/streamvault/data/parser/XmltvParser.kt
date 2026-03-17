package com.streamvault.data.parser

import com.streamvault.domain.model.Program
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/**
 * XMLTV EPG parser using XmlPullParser for memory-efficient streaming parsing.
 * Handles large EPG files (100MB+) without loading into memory.
 *
 * Supports:
 * - Standard XMLTV format
 * - Multiple date formats
 * - Timezone offsets
 * - Missing/malformed data (graceful skip)
 */
class XmltvParser {

    private val logger = Logger.getLogger(XmltvParser::class.java.name)

    private val offsetDateFormats = listOf(
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("yyyyMMddHHmmss xx")
            .toFormatter(Locale.US),
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .toFormatter(Locale.US)
    )

    private val localDateTimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US)
    )

    private val localDateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    )

    @Deprecated(
        message = "Loads all programs into memory. Use parseStreaming() for large EPG files.",
        replaceWith = ReplaceWith("parseStreaming(inputStream, onProgram)")
    )
    fun parse(inputStream: InputStream): List<Program> {
        val programs = mutableListOf<Program>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var currentChannelId: String? = null
            var currentTitle: String? = null
            var currentDescription: String? = null
            var currentStart: Long = 0
            var currentEnd: Long = 0
            var currentLang: String = ""
            var inProgramme = false
            var currentTag: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "programme" -> {
                                inProgramme = true
                                currentChannelId = parser.getAttributeValue(null, "channel")
                                currentStart = parseDate(parser.getAttributeValue(null, "start"))
                                currentEnd = parseDate(parser.getAttributeValue(null, "stop"))
                                currentTitle = null
                                currentDescription = null
                                currentLang = ""
                            }
                            "title" -> {
                                if (inProgramme) {
                                    currentLang = parser.getAttributeValue(null, "lang") ?: ""
                                    currentTag = "title"
                                }
                            }
                            "desc" -> {
                                if (inProgramme) currentTag = "desc"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> currentTitle = parser.text
                                "desc" -> currentDescription = parser.text
                            }
                            currentTag = null
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && inProgramme) {
                            if (currentChannelId != null && currentTitle != null) {
                                programs.add(
                                    Program(
                                        channelId = currentChannelId,
                                        title = currentTitle,
                                        description = currentDescription ?: "",
                                        startTime = currentStart,
                                        endTime = currentEnd,
                                        lang = currentLang
                                    )
                                )
                            }
                            inProgramme = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "XMLTV parse failed after ${programs.size} programme(s); returning partial results",
                e
            )
        }

        return programs
    }

    suspend fun parseStreaming(
        inputStream: InputStream,
        onProgram: suspend (Program) -> Unit
    ) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var currentChannelId: String? = null
        var currentTitle: String? = null
        var currentDescription: String? = null
        var currentStart: Long = 0
        var currentEnd: Long = 0
        var currentLang: String = ""
        var inProgramme = false
        var currentTag: String? = null
        var parsedCount = 0

        try {
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "programme" -> {
                                inProgramme = true
                                currentChannelId = parser.getAttributeValue(null, "channel")
                                currentStart = parseDate(parser.getAttributeValue(null, "start"))
                                currentEnd = parseDate(parser.getAttributeValue(null, "stop"))
                                currentTitle = null
                                currentDescription = null
                                currentLang = ""
                            }
                            "title" -> {
                                if (inProgramme) {
                                    currentLang = parser.getAttributeValue(null, "lang") ?: ""
                                    currentTag = "title"
                                }
                            }
                            "desc" -> {
                                if (inProgramme) currentTag = "desc"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> currentTitle = parser.text
                                "desc" -> currentDescription = parser.text
                            }
                            currentTag = null
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && inProgramme) {
                            if (currentChannelId != null && currentTitle != null) {
                                onProgram(
                                    Program(
                                        channelId = currentChannelId,
                                        title = currentTitle,
                                        description = currentDescription ?: "",
                                        startTime = currentStart,
                                        endTime = currentEnd,
                                        lang = currentLang
                                    )
                                )
                                parsedCount++
                            }
                            inProgramme = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "XMLTV streaming parse failed after $parsedCount programme(s)",
                e
            )
            throw e
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0

        offsetDateFormats.firstNotNullOfOrNull { formatter ->
            parseOffsetDateTime(dateStr, formatter)
        }?.let { return it }

        localDateTimeFormats.firstNotNullOfOrNull { formatter ->
            parseLocalDateTime(dateStr, formatter)
        }?.let { return it }

        localDateFormats.firstNotNullOfOrNull { formatter ->
            parseLocalDate(dateStr, formatter)
        }?.let { return it }

        // Last resort: try to extract just the timestamp portion
        try {
            val cleaned = dateStr.replace("""[^\d]""".toRegex(), "")
            if (cleaned.length >= 14) {
                return parseLocalDateTime(
                    cleaned.substring(0, 14),
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
                ) ?: 0
            }
        } catch (_: Exception) {
            // Give up
        }

        logger.warning("Unparseable XMLTV date: $dateStr")
        return 0
    }

    private fun parseOffsetDateTime(dateStr: String, formatter: DateTimeFormatter): Long? =
        runCatching {
            OffsetDateTime.parse(dateStr, formatter).toInstant().toEpochMilli()
        }.getOrNull()

    private fun parseLocalDateTime(dateStr: String, formatter: DateTimeFormatter): Long? =
        runCatching {
            LocalDateTime.parse(dateStr, formatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()

    private fun parseLocalDate(dateStr: String, formatter: DateTimeFormatter): Long? =
        runCatching {
            LocalDate.parse(dateStr, formatter)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
}
