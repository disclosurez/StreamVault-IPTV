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
import java.util.zip.GZIPInputStream

/**
 * Channel metadata parsed from an XMLTV `<channel>` element.
 */
data class XmltvChannel(
    val id: String,
    val displayName: String,
    val iconUrl: String? = null
)

data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val lang: String = "",
    val rating: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val genre: String? = null,
    val episodeInfo: String? = null
)

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

    private fun newPullParser(inputStream: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        runCatching {
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        }.onFailure {
            logger.log(Level.FINE, "XML pull parser does not support relaxed mode", it)
        }
        // Pass the raw stream with null encoding so the parser auto-detects charset
        // from the BOM or the XML declaration (e.g. encoding="iso-8859-1").
        // Relaxed mode handles bare & characters in text content.
        parser.setInput(inputStream, null)
        return parser
    }

    @Deprecated(
        message = "Loads all programs into memory. Use parseStreaming() for large EPG files.",
        replaceWith = ReplaceWith("parseStreaming(inputStream, onProgram)")
    )
    fun parse(inputStream: InputStream): List<Program> {
        val programs = mutableListOf<Program>()

        try {
            val parser = newPullParser(inputStream)

            var eventType = parser.eventType
            var currentChannelId: String? = null
            var currentTitle: String? = null
            var currentDescription: String? = null
            var currentStart: Long = 0
            var currentEnd: Long = 0
            var currentLang: String = ""
            var currentImageUrl: String? = null
            val currentCategories = mutableListOf<String>()
            var currentRating: String? = null
            var inRating = false
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
                                currentImageUrl = null
                                currentCategories.clear()
                                currentRating = null
                                inRating = false
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
                            "icon" -> {
                                if (inProgramme) {
                                    currentImageUrl = parser.getAttributeValue(null, "src")
                                }
                            }
                            "category" -> {
                                if (inProgramme) currentTag = "category"
                            }
                            "rating" -> {
                                if (inProgramme) inRating = true
                            }
                            "value" -> {
                                if (inProgramme && inRating) currentTag = "rating"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> currentTitle = parser.text
                                "desc" -> currentDescription = parser.text
                                "category" -> parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let(currentCategories::add)
                                "rating" -> currentRating = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                            }
                            currentTag = null
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "rating") {
                            inRating = false
                        }
                        if (parser.name == "programme" && inProgramme) {
                            if (currentChannelId != null && currentTitle != null) {
                                programs.add(
                                    Program(
                                        channelId = currentChannelId,
                                        title = currentTitle,
                                        description = currentDescription ?: "",
                                        startTime = currentStart,
                                        endTime = currentEnd,
                                        lang = currentLang,
                                        rating = currentRating,
                                        imageUrl = currentImageUrl,
                                        genre = currentCategories.distinct().joinToString(" / ").takeIf { it.isNotBlank() },
                                        category = currentCategories.firstOrNull()
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
        val parser = newPullParser(inputStream)

        var eventType = parser.eventType
        var currentChannelId: String? = null
        var currentTitle: String? = null
        var currentDescription: String? = null
        var currentStart: Long = 0
        var currentEnd: Long = 0
        var currentLang: String = ""
        var currentImageUrl: String? = null
        val currentCategories = mutableListOf<String>()
        var currentRating: String? = null
        var inRating = false
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
                                currentImageUrl = null
                                currentCategories.clear()
                                currentRating = null
                                inRating = false
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
                            "icon" -> {
                                if (inProgramme) {
                                    currentImageUrl = parser.getAttributeValue(null, "src")
                                }
                            }
                            "category" -> {
                                if (inProgramme) currentTag = "category"
                            }
                            "rating" -> {
                                if (inProgramme) inRating = true
                            }
                            "value" -> {
                                if (inProgramme && inRating) currentTag = "rating"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> currentTitle = parser.text
                                "desc" -> currentDescription = parser.text
                                "category" -> parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let(currentCategories::add)
                                "rating" -> currentRating = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                            }
                            currentTag = null
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "rating") {
                            inRating = false
                        }
                        if (parser.name == "programme" && inProgramme) {
                            if (currentChannelId != null && currentTitle != null) {
                                onProgram(
                                    Program(
                                        channelId = currentChannelId,
                                        title = currentTitle,
                                        description = currentDescription ?: "",
                                        startTime = currentStart,
                                        endTime = currentEnd,
                                        lang = currentLang,
                                        rating = currentRating,
                                        imageUrl = currentImageUrl,
                                        genre = currentCategories.distinct().joinToString(" / ").takeIf { it.isNotBlank() },
                                        category = currentCategories.firstOrNull()
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

    /**
     * Streaming parser that yields both `<channel>` and `<programme>` elements.
     * Channels are emitted first (they appear before programmes in standard XMLTV).
     */
    suspend fun parseStreamingWithChannels(
        inputStream: InputStream,
        onChannel: suspend (XmltvChannel) -> Unit,
        onProgramme: suspend (XmltvProgramme) -> Unit
    ) {
        val parser = newPullParser(inputStream)

        var eventType = parser.eventType
        // Channel state
        var inChannel = false
        var channelId: String? = null
        var channelDisplayName: String? = null
        var channelIconUrl: String? = null
        var channelTag: String? = null
        // Programme state
        var inProgramme = false
        var currentChannelId: String? = null
        var currentTitle: String? = null
        var currentDescription: String? = null
        var currentStart: Long = 0
        var currentEnd: Long = 0
        var currentLang: String = ""
        var currentImageUrl: String? = null
        val currentCategories = mutableListOf<String>()
        var currentSubtitle: String? = null
        var currentEpisodeNum: String? = null
        var currentRating: String? = null
        var inRating = false
        var currentTag: String? = null
        var channelCount = 0
        var programmeCount = 0

        try {
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                inChannel = true
                                channelId = parser.getAttributeValue(null, "id")
                                channelDisplayName = null
                                channelIconUrl = null
                            }
                            "display-name" -> {
                                if (inChannel) channelTag = "display-name"
                            }
                            "icon" -> {
                                if (inChannel) {
                                    channelIconUrl = parser.getAttributeValue(null, "src")
                                } else if (inProgramme) {
                                    currentImageUrl = parser.getAttributeValue(null, "src")
                                }
                            }
                            "programme" -> {
                                inProgramme = true
                                currentChannelId = parser.getAttributeValue(null, "channel")
                                currentStart = parseDate(parser.getAttributeValue(null, "start"))
                                currentEnd = parseDate(parser.getAttributeValue(null, "stop"))
                                currentTitle = null
                                currentDescription = null
                                currentLang = ""
                                currentImageUrl = null
                                currentCategories.clear()
                                currentRating = null
                                inRating = false
                            }
                            "title" -> {
                                if (inProgramme) {
                                    currentLang = parser.getAttributeValue(null, "lang") ?: ""
                                    currentTag = "title"
                                }
                            }
                            "sub-title" -> {
                                if (inProgramme) currentTag = "sub-title"
                            }
                            "desc" -> {
                                if (inProgramme) currentTag = "desc"
                            }
                            "episode-num" -> {
                                if (inProgramme) currentTag = "episode-num"
                            }
                            "category" -> {
                                if (inProgramme) currentTag = "category"
                            }
                            "rating" -> {
                                if (inProgramme) inRating = true
                            }
                            "value" -> {
                                if (inProgramme && inRating) currentTag = "rating"
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inChannel) {
                            when (channelTag) {
                                "display-name" -> {
                                    if (channelDisplayName == null) {
                                        channelDisplayName = parser.text?.trim()
                                    }
                                }
                            }
                            channelTag = null
                        }
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> currentTitle = parser.text
                                "sub-title" -> currentSubtitle = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                                "desc" -> currentDescription = parser.text
                                "episode-num" -> currentEpisodeNum = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                                "category" -> parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let(currentCategories::add)
                                "rating" -> currentRating = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                            }
                            currentTag = null
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "channel" && inChannel) {
                            if (channelId != null && channelDisplayName != null) {
                                onChannel(
                                    XmltvChannel(
                                        id = channelId,
                                        displayName = channelDisplayName!!,
                                        iconUrl = channelIconUrl
                                    )
                                )
                                channelCount++
                            }
                            inChannel = false
                        }
                        if (parser.name == "rating") {
                            inRating = false
                        }
                        if (parser.name == "programme" && inProgramme) {
                            if (currentChannelId != null && currentTitle != null) {
                                onProgramme(
                                    XmltvProgramme(
                                        channelId = currentChannelId,
                                        title = currentTitle,
                                        subtitle = currentSubtitle,
                                        description = currentDescription ?: "",
                                        startTime = currentStart,
                                        endTime = currentEnd,
                                        lang = currentLang,
                                        rating = currentRating,
                                        imageUrl = currentImageUrl,
                                        genre = currentCategories.distinct().joinToString(" / ").takeIf { it.isNotBlank() },
                                        category = currentCategories.firstOrNull(),
                                        episodeInfo = currentEpisodeNum
                                    )
                                )
                                programmeCount++
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
                "XMLTV streaming parse failed after $channelCount channel(s) and $programmeCount programme(s)",
                e
            )
            throw e
        }
    }

    /**
     * Wraps the input stream with GZIPInputStream if the URL ends in `.gz`.
     */
    fun maybeDecompressGzip(url: String, inputStream: InputStream): InputStream {
        return if (url.lowercase(Locale.ROOT).let { it.endsWith(".gz") || it.endsWith(".gzip") }) {
            GZIPInputStream(inputStream)
        } else {
            inputStream
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
