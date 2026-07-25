package com.streamvault.app.util

import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import kotlin.jvm.JvmName

val ALLOWED_LANGUAGE_CODES: Set<String> = setOf("IE", "GB", "EN", "US", "CA")

val LANGUAGE_TAG_REGEX: Regex = Regex("""[\[\(]([A-Za-z]{2,3})[\]\)]""")

fun hasNonEnglishLanguageTag(name: String): Boolean {
    val tags = LANGUAGE_TAG_REGEX.findAll(name).map { it.groupValues[1].uppercase() }.toList()
    if (tags.isEmpty()) return false
    return tags.any { it !in ALLOWED_LANGUAGE_CODES }
}

@JvmName("filterNonEnglishMovies")
fun List<Movie>.filterNonEnglish(): List<Movie> = filter { !hasNonEnglishLanguageTag(it.name) }

@JvmName("filterNonEnglishSeries")
fun List<Series>.filterNonEnglish(): List<Series> = filter { !hasNonEnglishLanguageTag(it.name) }
