package com.streamvault.app.ui.model

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import org.junit.Test

class CategoryDisplayPreferencesTest {
    @Test
    fun applyProviderCategoryDisplayPreferences_mergesSimilarNames() {
        val categories = listOf(
            Category(id = 1, name = "Movies", count = 10),
            Category(id = 2, name = "Movies HD", count = 4),
            Category(id = 3, name = "Kids", count = 7)
        )

        val merged = applyProviderCategoryDisplayPreferences(
            categories = categories,
            hiddenCategoryIds = emptySet(),
            sortMode = CategorySortMode.DEFAULT,
            mergeSimilarNames = true
        )

        assertThat(merged).hasSize(2)
        assertThat(merged.map { it.name }).containsExactly("Movies", "Kids").inOrder()
        assertThat(merged.first { it.name == "Movies" }.count).isEqualTo(14)
    }

    @Test
    fun applyProviderCategoryDisplayPreferences_filtersHiddenBeforeMerging() {
        val categories = listOf(
            Category(id = 1, name = "Movies", count = 10),
            Category(id = 2, name = "Movies HD", count = 4),
            Category(id = 3, name = "Kids", count = 7)
        )

        val merged = applyProviderCategoryDisplayPreferences(
            categories = categories,
            hiddenCategoryIds = setOf(2L),
            sortMode = CategorySortMode.DEFAULT,
            mergeSimilarNames = true
        )

        assertThat(merged).hasSize(2)
        assertThat(merged.first { it.name == "Movies" }.count).isEqualTo(10)
    }

    @Test
    fun canonicalCategoryDisplayName_stripsProviderPrefixAndQualitySuffix() {
        assertThat(canonicalCategoryDisplayName("AMZ - Drama (HD)")).isEqualTo("Drama")
        assertThat(categoryDisplayGroupKey("AMZ - Drama (HD)")).isEqualTo(categoryDisplayGroupKey("Drama"))
    }
}
