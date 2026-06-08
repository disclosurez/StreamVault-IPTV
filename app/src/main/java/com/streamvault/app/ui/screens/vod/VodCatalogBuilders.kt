package com.streamvault.app.ui.screens.vod

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Favorite
import com.streamvault.app.ui.screens.vod.matchesVodGroupMembership
import com.streamvault.app.ui.model.categoryDisplayGroupKey
import com.streamvault.app.ui.model.canonicalCategoryDisplayName
import com.streamvault.app.ui.model.mergeSimilarCategories

data class VodCatalogSnapshot<Item>(
    val grouped: Map<String, List<Item>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

suspend fun <Item> buildVodPreviewCatalog(
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    providerCategories: List<Category>,
    providerCategoryCounts: Map<Long, Int>,
    libraryCount: Int,
    hiddenProviderCategoryIds: Set<Long>,
    loadItemsByIds: suspend (List<Long>) -> List<Item>,
    providerPreviews: Map<Long?, List<Item>>,
    itemId: (Item) -> Long,
    itemCategoryId: (Item) -> Long?,
    copyWithFavorite: (Item, Boolean) -> Item
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val previewRows = linkedMapOf<String, List<Item>>()
    val countMap = linkedMapOf<String, Int>()
    val displayProviderCategories = mergeSimilarCategories(providerCategories)
    val providerCategoriesByGroupKey = providerCategories.groupBy { categoryDisplayGroupKey(it.name) }

    val favoritesIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .sortedBy(Favorite::position)
        .map(Favorite::contentId)
        .toList()
    if (favoritesIds.isNotEmpty()) {
        val preview = loadItemsByIds(favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT))
            .filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds }
            .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
        if (preview.isNotEmpty()) {
            previewRows[VodBrowseDefaults.FAVORITES_CATEGORY] = preview
            countMap[VodBrowseDefaults.FAVORITES_CATEGORY] = favoritesIds.size
        }
    }

    val customCategoryPreviewIds = customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .associateWith { category ->
            allFavorites
                .asSequence()
                .filter { matchesVodGroupMembership(it.groupId, category.id) }
                .sortedBy(Favorite::position)
                .map(Favorite::contentId)
                .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                .toList()
        }

    val idsToPreload = buildSet {
        addAll(favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT))
        customCategoryPreviewIds.values.forEach(::addAll)
    }
    val preloadedById = if (idsToPreload.isEmpty()) {
        emptyMap()
    } else {
        loadItemsByIds(idsToPreload.toList()).associateBy(itemId)
    }

    customCategoryPreviewIds.forEach { (category, previewIds) ->
        if (previewIds.isNotEmpty()) {
            val preview = previewIds.mapNotNull(preloadedById::get)
                .filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds }
                .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
            if (preview.isNotEmpty()) {
                previewRows[category.name] = preview
                countMap[category.name] = allFavorites.count { favorite -> matchesVodGroupMembership(favorite.groupId, category.id) }
            }
        }
    }

    displayProviderCategories.forEach { displayCategory ->
        val groupKey = categoryDisplayGroupKey(displayCategory.name)
        val rawCategories = providerCategoriesByGroupKey[groupKey].orEmpty()
        val preview = rawCategories
            .flatMap { rawCategory -> providerPreviews[rawCategory.id].orEmpty() }
            .distinctBy(itemId)
            .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
        val count = rawCategories.sumOf { rawCategory -> providerCategoryCounts[rawCategory.id] ?: rawCategory.count }
        countMap[displayCategory.name] = count
        if (preview.isNotEmpty()) {
            previewRows[displayCategory.name] = preview
        }
    }

    return VodCatalogSnapshot(
        grouped = previewRows,
        categoryNames = previewRows.keys.toList(),
        categoryCounts = countMap,
        libraryCount = libraryCount
    )
}

fun <Item> buildVodSearchCatalog(
    items: List<Item>,
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    providerCategories: List<Category>,
    hiddenProviderCategoryIds: Set<Long>,
    itemId: (Item) -> Long,
    itemCategoryId: (Item) -> Long?,
    itemCategoryName: (Item) -> String?,
    copyWithFavorite: (Item, Boolean) -> Item,
    uncategorizedName: String
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val enrichedItems = markVodFavorites(
        items.filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds },
        globalFavoriteIds,
        itemId,
        copyWithFavorite
    )
    val displayProviderCategories = mergeSimilarCategories(providerCategories)
    val providerNamesByGroupKey = displayProviderCategories
        .associateBy({ categoryDisplayGroupKey(it.name) }, { it.name })
    val groupedLists = linkedMapOf<String, MutableList<Item>>()
    enrichedItems.forEach { item ->
        val rawName = itemCategoryName(item) ?: uncategorizedName
        val groupKey = categoryDisplayGroupKey(rawName)
        val displayName = providerNamesByGroupKey[groupKey] ?: canonicalCategoryDisplayName(rawName)
        groupedLists.getOrPut(displayName) { mutableListOf() }.add(item)
    }

    val favoriteMatches = enrichedItems.filter { item -> itemId(item) in globalFavoriteIds }
    if (favoriteMatches.isNotEmpty()) {
        groupedLists[VodBrowseDefaults.FAVORITES_CATEGORY] = favoriteMatches.toMutableList()
    }

    customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .forEach { customCategory ->
            val itemIdsInGroup = allFavorites
                .asSequence()
                .filter { matchesVodGroupMembership(it.groupId, customCategory.id) }
                .map(Favorite::contentId)
                .toSet()
            groupedLists[customCategory.name] = enrichedItems
                .filter { itemId(it) in itemIdsInGroup }
                .toMutableList()
        }

    val customNames = customCategories.map(Category::name).toSet()
    val preferredProviderNames = displayProviderCategories.map(Category::name)
    val orderedNames = buildList {
        if (groupedLists.containsKey(VodBrowseDefaults.FAVORITES_CATEGORY)) {
            add(VodBrowseDefaults.FAVORITES_CATEGORY)
        }
        customCategories.forEach { category ->
            if (groupedLists.containsKey(category.name)) {
                add(category.name)
            }
        }
        preferredProviderNames.forEach { categoryName ->
            if (groupedLists.containsKey(categoryName)) {
                add(categoryName)
            }
        }
        groupedLists.keys
            .filterNot { it == VodBrowseDefaults.FAVORITES_CATEGORY || it in customNames || it in preferredProviderNames }
            .sortedBy { it.lowercase() }
            .forEach(::add)
    }

    return VodCatalogSnapshot(
        grouped = groupedLists.mapValues { it.value.toList() },
        categoryNames = orderedNames,
        categoryCounts = orderedNames.associateWith { name -> groupedLists[name]?.size ?: 0 },
        libraryCount = items.size
    )
}

fun <Item> markVodFavorites(
    items: List<Item>,
    globalFavoriteIds: Set<Long>,
    itemId: (Item) -> Long,
    copyWithFavorite: (Item, Boolean) -> Item
): List<Item> = items.map { item ->
    copyWithFavorite(item, itemId(item) in globalFavoriteIds)
}
