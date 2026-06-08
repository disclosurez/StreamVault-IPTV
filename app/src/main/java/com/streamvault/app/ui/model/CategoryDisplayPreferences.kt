package com.streamvault.app.ui.model

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import java.text.Normalizer
import java.util.Locale

private val TRAILING_BRACKETED_TAG_REGEX = Regex("""\s*[\[(][^\])\]]*[\])]\s*$""")
private val TRAILING_SEPARATOR_REGEX = Regex("""[\s\p{Punct}]+$""")
private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")
private val COLLAPSIBLE_SUFFIX_TOKENS = setOf(
    "hd",
    "fhd",
    "uhd",
    "sd",
    "4k",
    "tv",
    "live",
    "vod",
    "channel",
    "channels"
)

fun applyProviderCategoryDisplayPreferences(
    categories: List<Category>,
    hiddenCategoryIds: Set<Long>,
    sortMode: CategorySortMode,
    mergeSimilarNames: Boolean = false
): List<Category> {
    val visible = categories.filterNot { it.id in hiddenCategoryIds }
    val prepared = if (mergeSimilarNames) {
        mergeSimilarCategories(visible)
    } else {
        visible
    }
    return when (sortMode) {
        CategorySortMode.DEFAULT -> prepared
        CategorySortMode.TITLE_ASC -> prepared.sortedBy { it.name.lowercase() }
        CategorySortMode.TITLE_DESC -> prepared.sortedByDescending { it.name.lowercase() }
        CategorySortMode.COUNT_DESC -> prepared.sortedWith(
            compareByDescending<Category> { it.count }.thenBy { it.name.lowercase() }
        )
        CategorySortMode.COUNT_ASC -> prepared.sortedWith(
            compareBy<Category> { it.count }.thenBy { it.name.lowercase() }
        )
    }
}

fun mergeSimilarCategories(categories: List<Category>): List<Category> {
    if (categories.isEmpty()) return emptyList()

    val grouped = linkedMapOf<String, MutableList<Category>>()
    categories.forEach { category ->
        grouped.getOrPut(categoryDisplayGroupKey(category.name)) { mutableListOf() }.add(category)
    }

    return grouped.values.map(::mergeCategoryGroup)
}

fun categoryDisplayGroupKey(name: String): String =
    NON_ALPHANUMERIC_REGEX.replace(
        Normalizer.normalize(canonicalCategoryDisplayName(name), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT),
        ""
    )

fun canonicalCategoryDisplayName(name: String): String {
    var value = name.trim()
    value = stripProviderPrefix(value)
    value = stripTrailingBracketedTags(value)
    value = stripTrailingSuffixTokens(value)
    value = value.replace(Regex("""\s+"""), " ").trim()
    return value.ifBlank { name.trim() }
}

private fun mergeCategoryGroup(group: List<Category>): Category {
    val representative = group.minWithOrNull(
        compareBy<Category> { categoryDisplayPriority(it.name) }
            .thenBy { canonicalCategoryDisplayName(it.name).length }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.id }
    ) ?: group.first()

    return representative.copy(
        count = group.sumOf(Category::count)
    )
}

private fun categoryDisplayPriority(name: String): Int {
    val trimmed = name.trim()
    val canonical = canonicalCategoryDisplayName(name)
    return when {
        canonical.equals(trimmed, ignoreCase = true) -> 0
        canonical.length < trimmed.length -> 1
        else -> 2
    }
}

private fun stripProviderPrefix(value: String): String {
    val separatorIndex = value.indexOf(" - ")
    if (separatorIndex !in 1..8) return value

    val prefix = value.substring(0, separatorIndex).trim()
    val prefixLooksLikeTag = prefix.length <= 8 && prefix.all { it.isUpperCase() || it.isDigit() || it in "&._/" }
    return if (prefixLooksLikeTag) value.substring(separatorIndex + 3).trim() else value
}

private fun stripTrailingBracketedTags(value: String): String {
    var current = value.trim()
    while (true) {
        val stripped = TRAILING_BRACKETED_TAG_REGEX.replace(current, "").trim()
        if (stripped == current) return current
        current = stripped
    }
}

private fun stripTrailingSuffixTokens(value: String): String {
    var tokens = value.split(Regex("""\s+""")).toMutableList()
    while (tokens.isNotEmpty() && tokens.last().lowercase(Locale.ROOT) in COLLAPSIBLE_SUFFIX_TOKENS) {
        tokens.removeAt(tokens.lastIndex)
    }
    if (tokens.isEmpty()) return value
    return tokens.joinToString(" ").replace(TRAILING_SEPARATOR_REGEX, "").trim()
}
