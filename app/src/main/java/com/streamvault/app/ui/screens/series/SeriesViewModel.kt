package com.streamvault.app.ui.screens.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.GetCustomCategories
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeriesLibraryLens {
    FAVORITES,
    CONTINUE,
    TOP_RATED,
    FRESH
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val seriesRepository: SeriesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val getCustomCategories: GetCustomCategories
) : ViewModel() {
    private companion object {
        const val UNCATEGORIZED = "Uncategorized"
        const val FAVORITES_CATEGORY = "\u2605 Favorites"
        const val FAVORITES_SENTINEL_ID = -999L
        const val FULL_LIBRARY_CATEGORY = "__full_library__"
        const val PREVIEW_ROW_LIMIT = 18
        const val SELECTED_CATEGORY_PAGE_SIZE = 60
    }

    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategoryLoadLimit = MutableStateFlow(SELECTED_CATEGORY_PAGE_SIZE)

    init {
        viewModelScope.launch {
            try {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.SERIES),
                        getCustomCategories(ContentType.SERIES),
                        seriesRepository.getCategories(provider.id),
                        seriesRepository.getCategoryItemCounts(provider.id),
                        seriesRepository.getLibraryCount(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts, libraryCount ->
                        SeriesCatalogDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = providerCategories,
                            providerCategoryCounts = providerCategoryCounts,
                            libraryCount = libraryCount
                        )
                    }.combine(_searchQuery) { dependencies, query ->
                        SeriesCatalogParams(
                            providerId = provider.id,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            providerCategoryCounts = dependencies.providerCategoryCounts,
                            libraryCount = dependencies.libraryCount,
                            query = query.trim()
                        )
                    }
                }
                .flatMapLatest { params ->
                    flow {
                        emit(
                            if (params.query.isBlank()) {
                                buildPreviewCatalog(params)
                            } else {
                                val searchResults = seriesRepository.searchSeries(params.providerId, params.query).first()
                                buildSearchCatalog(
                                    series = searchResults,
                                    allFavorites = params.allFavorites,
                                    customCategories = params.customCategories
                                ).copy(libraryCount = searchResults.size)
                            }
                        )
                    }
                }
                .collect { snapshot ->
                    val isReordering = _uiState.value.isReorderMode
                    _uiState.update {
                        it.copy(
                            seriesByCategory = snapshot.grouped,
                            categoryNames = snapshot.categoryNames,
                            categoryCounts = snapshot.categoryCounts,
                            libraryCount = snapshot.libraryCount,
                            filteredSeries = if (isReordering) it.filteredSeries else emptyList(),
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load series") }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.SERIES),
                        getCustomCategories(ContentType.SERIES),
                        seriesRepository.getCategories(provider.id),
                        seriesRepository.getCategoryItemCounts(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts ->
                        SeriesCategorySelectionDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = providerCategories,
                            providerCategoryCounts = providerCategoryCounts
                        )
                    }.combine(
                        combine(
                            _uiState.map { it.selectedCategory }.distinctUntilChanged(),
                            _selectedCategoryLoadLimit,
                            _searchQuery
                        ) { selectedCategory, loadLimit, query ->
                            Triple(selectedCategory, loadLimit, query.trim())
                        }
                    ) { dependencies, selection ->
                        SelectedSeriesCategoryRequest(
                            providerId = provider.id,
                            selectedCategory = selection.first,
                            loadLimit = selection.second,
                            query = selection.third,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            providerCategoryCounts = dependencies.providerCategoryCounts
                        )
                    }
                }
                .flatMapLatest { request ->
                    flow {
                        emit(loadSelectedCategoryItems(request))
                    }
                }
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            selectedCategoryItems = snapshot.items,
                            selectedCategoryLoadedCount = snapshot.loadedCount,
                            selectedCategoryTotalCount = snapshot.totalCount,
                            canLoadMoreSelectedCategory = snapshot.canLoadMore,
                            isLoadingSelectedCategory = false
                        )
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .collectLatest { provider ->
                    launch {
                        playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 20)
                            .collect { history ->
                                _uiState.update {
                                    it.copy(
                                        continueWatching = history.filter { entry ->
                                            entry.contentType == ContentType.SERIES ||
                                                entry.contentType == ContentType.SERIES_EPISODE
                                        }
                                    )
                                }
                            }
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.SERIES),
                        playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 24),
                        seriesRepository.getTopRatedPreview(provider.id, PREVIEW_ROW_LIMIT),
                        seriesRepository.getFreshPreview(provider.id, PREVIEW_ROW_LIMIT)
                    ) { allFavorites, history, topRated, fresh ->
                        SeriesLibraryLensDependencies(
                            providerId = provider.id,
                            allFavorites = allFavorites,
                            history = history,
                            topRated = topRated,
                            fresh = fresh
                        )
                    }
                }
                .collectLatest { dependencies ->
                    val globalFavoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .map { it.contentId }
                        .toSet()
                    val favoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .take(PREVIEW_ROW_LIMIT)
                        .toList()
                    val continueIds = dependencies.history
                        .asSequence()
                        .filter {
                            it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE
                        }
                        .sortedByDescending { it.lastWatchedAt }
                        .distinctBy { it.seriesId ?: it.contentId }
                        .map { it.seriesId ?: it.contentId }
                        .take(PREVIEW_ROW_LIMIT)
                        .toList()

                    val favoritePreview = if (favoriteIds.isEmpty()) {
                        emptyList()
                    } else {
                        seriesRepository.getSeriesByIds(favoriteIds).first().orderByIds(favoriteIds)
                    }.markFavorites(globalFavoriteIds)
                    val continuePreview = if (continueIds.isEmpty()) {
                        emptyList()
                    } else {
                        seriesRepository.getSeriesByIds(continueIds).first().orderByIds(continueIds)
                    }.markFavorites(globalFavoriteIds)

                    _uiState.update {
                        it.copy(
                            libraryLensRows = mapOf(
                                SeriesLibraryLens.FAVORITES to favoritePreview,
                                SeriesLibraryLens.CONTINUE to continuePreview,
                                SeriesLibraryLens.TOP_RATED to dependencies.topRated.markFavorites(globalFavoriteIds),
                                SeriesLibraryLens.FRESH to dependencies.fresh.markFavorites(globalFavoriteIds)
                            ).filterValues { rows -> rows.isNotEmpty() }
                        )
                    }
                }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _uiState.update { it.copy(parentalControlLevel = level) }
            }
        }

        viewModelScope.launch {
            getCustomCategories(ContentType.SERIES).collect { categories ->
                _uiState.update { it.copy(categories = categories) }
            }
        }
    }

    fun selectCategory(categoryName: String?) {
        _selectedCategoryLoadLimit.value = SELECTED_CATEGORY_PAGE_SIZE
        _uiState.update {
            it.copy(
                selectedCategory = categoryName,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = categoryName != null
            )
        }
    }

    fun selectFullLibraryBrowse() {
        selectCategory(FULL_LIBRARY_CATEGORY)
    }

    fun loadMoreSelectedCategory() {
        if (!_uiState.value.canLoadMoreSelectedCategory) return
        _selectedCategoryLoadLimit.update { it + SELECTED_CATEGORY_PAGE_SIZE }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun onShowDialog(series: Series) {
        viewModelScope.launch {
            val memberships = favoriteRepository.getGroupMemberships(series.id, ContentType.SERIES)
            val isFavorite = favoriteRepository.isFavorite(series.id, ContentType.SERIES)
            _uiState.update {
                it.copy(
                    showDialog = true,
                    selectedSeriesForDialog = series.copy(isFavorite = isFavorite),
                    dialogGroupMemberships = memberships
                )
            }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedSeriesForDialog = null) }
    }

    fun addFavorite(series: Series) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(series.id, ContentType.SERIES)
            _uiState.update { it.copy(selectedSeriesForDialog = series.copy(isFavorite = true)) }
        }
    }

    fun removeFavorite(series: Series) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(series.id, ContentType.SERIES)
            _uiState.update { it.copy(selectedSeriesForDialog = series.copy(isFavorite = false)) }
        }
    }

    fun addToGroup(series: Series, group: Category) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(series.id, ContentType.SERIES, groupId = -group.id)
            val memberships = favoriteRepository.getGroupMemberships(series.id, ContentType.SERIES)
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun removeFromGroup(series: Series, group: Category) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(series.id, ContentType.SERIES, groupId = -group.id)
            val memberships = favoriteRepository.getGroupMemberships(series.id, ContentType.SERIES)
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun createCustomGroup(name: String) {
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName)
        if (validationError != null) {
            _uiState.update { it.copy(userMessage = validationError) }
            return
        }

        viewModelScope.launch {
            favoriteRepository.createGroup(normalizedName, contentType = ContentType.SERIES)
            _uiState.update { it.copy(userMessage = "Created group $normalizedName") }
        }
    }

    fun showCategoryOptions(categoryName: String) {
        val matchedCategory = _uiState.value.categories.find { it.name == categoryName }
            ?: if (categoryName == FAVORITES_CATEGORY) {
                Category(
                    id = FAVORITES_SENTINEL_ID,
                    name = FAVORITES_CATEGORY,
                    type = ContentType.SERIES,
                    isVirtual = true
                )
            } else {
                null
            }

        if (matchedCategory != null) {
            _uiState.update { it.copy(selectedCategoryForOptions = matchedCategory) }
        }
    }

    fun dismissCategoryOptions() {
        _uiState.update { it.copy(selectedCategoryForOptions = null) }
    }

    fun requestRenameGroup(category: Category) {
        if (!category.isVirtual || category.id == FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showRenameGroupDialog = true,
                groupToRename = category,
                renameGroupError = null
            )
        }
    }

    fun cancelRenameGroup() {
        _uiState.update {
            it.copy(
                showRenameGroupDialog = false,
                groupToRename = null,
                renameGroupError = null
            )
        }
    }

    fun confirmRenameGroup(name: String) {
        val category = _uiState.value.groupToRename ?: return
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName, currentGroupId = category.id)
        if (validationError != null) {
            _uiState.update { it.copy(renameGroupError = validationError) }
            return
        }

        viewModelScope.launch {
            favoriteRepository.renameGroup(-category.id, normalizedName)
            _uiState.update {
                it.copy(
                    showRenameGroupDialog = false,
                    groupToRename = null,
                    renameGroupError = null,
                    userMessage = "Renamed group to $normalizedName"
                )
            }
        }
    }

    fun requestDeleteGroup(category: Category) {
        if (!category.isVirtual || category.id == FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showDeleteGroupDialog = true,
                groupToDelete = category
            )
        }
    }

    fun cancelDeleteGroup() {
        _uiState.update { it.copy(showDeleteGroupDialog = false, groupToDelete = null) }
    }

    fun confirmDeleteGroup() {
        val category = _uiState.value.groupToDelete ?: return
        viewModelScope.launch {
            favoriteRepository.deleteGroup(-category.id)
            _uiState.update {
                it.copy(
                    showDeleteGroupDialog = false,
                    groupToDelete = null,
                    userMessage = "Deleted group ${category.name}"
                )
            }
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun enterCategoryReorderMode(category: Category) {
        dismissCategoryOptions()
        viewModelScope.launch {
            val seriesInView = loadReorderSeries(category)
            _uiState.update {
                it.copy(
                    isReorderMode = true,
                    reorderCategory = category,
                    filteredSeries = seriesInView
                )
            }
        }
    }

    fun exitCategoryReorderMode() {
        _uiState.update {
            it.copy(
                isReorderMode = false,
                reorderCategory = null,
                filteredSeries = emptyList()
            )
        }
    }

    fun moveItemUp(series: Series) {
        val list = _uiState.value.filteredSeries.toMutableList()
        val index = list.indexOf(series)
        if (index > 0) {
            list.removeAt(index)
            list.add(index - 1, series)
            _uiState.update { it.copy(filteredSeries = list) }
        }
    }

    fun moveItemDown(series: Series) {
        val list = _uiState.value.filteredSeries.toMutableList()
        val index = list.indexOf(series)
        if (index >= 0 && index < list.lastIndex) {
            list.removeAt(index)
            list.add(index + 1, series)
            _uiState.update { it.copy(filteredSeries = list) }
        }
    }

    fun saveReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        val currentList = state.filteredSeries

        exitCategoryReorderMode()

        viewModelScope.launch {
            try {
                val groupId = if (category.id == FAVORITES_SENTINEL_ID) null else -category.id
                val favoritesFlow = if (groupId == null) {
                    favoriteRepository.getFavorites(ContentType.SERIES)
                } else {
                    favoriteRepository.getFavoritesByGroup(groupId)
                }

                val favorites = favoritesFlow.first()
                val favoriteMap = favorites.associateBy { it.contentId }
                val reorderedFavorites = currentList.mapNotNull { series ->
                    favoriteMap[series.id]
                }.mapIndexed { index, favorite ->
                    favorite.copy(position = index)
                }

                favoriteRepository.reorderFavorites(reorderedFavorites)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun loadReorderSeries(category: Category): List<Series> {
        if (!category.isVirtual) return emptyList()

        val groupId = if (category.id == FAVORITES_SENTINEL_ID) null else -category.id
        val favorites = if (groupId == null) {
            favoriteRepository.getFavorites(ContentType.SERIES).first()
        } else {
            favoriteRepository.getFavoritesByGroup(groupId).first()
        }

        val orderedIds = favorites
            .sortedBy { it.position }
            .map { it.contentId }

        if (orderedIds.isEmpty()) return emptyList()
        return seriesRepository.getSeriesByIds(orderedIds).first().orderByIds(orderedIds)
    }

    private suspend fun buildPreviewCatalog(
        params: SeriesCatalogParams
    ): SeriesCatalogSnapshot {
        val globalFavoriteIds = params.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val previewRows = linkedMapOf<String, List<Series>>()
        val countMap = linkedMapOf<String, Int>()

        val favoritesIds = params.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .sortedBy { it.position }
            .map { it.contentId }
            .toList()
        if (favoritesIds.isNotEmpty()) {
            val preview = seriesRepository.getSeriesByIds(favoritesIds.take(PREVIEW_ROW_LIMIT)).first()
                .markFavorites(globalFavoriteIds)
            if (preview.isNotEmpty()) {
                previewRows[FAVORITES_CATEGORY] = preview
                countMap[FAVORITES_CATEGORY] = favoritesIds.size
            }
        }

        val customCategoryPreviewIds = params.customCategories
            .filter { it.id != FAVORITES_SENTINEL_ID }
            .associateWith { category ->
                params.allFavorites
                    .asSequence()
                    .filter { it.groupId == -category.id }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .take(PREVIEW_ROW_LIMIT)
                    .toList()
            }

        val idsToPreload = buildSet {
            addAll(favoritesIds.take(PREVIEW_ROW_LIMIT))
            customCategoryPreviewIds.values.forEach { addAll(it) }
        }
        val preloadedById = if (idsToPreload.isEmpty()) {
            emptyMap()
        } else {
            seriesRepository.getSeriesByIds(idsToPreload.toList()).first().associateBy { it.id }
        }

        customCategoryPreviewIds.forEach { (category, previewIds) ->
            if (previewIds.isNotEmpty()) {
                val preview = previewIds.mapNotNull { preloadedById[it] }.markFavorites(globalFavoriteIds)
                if (preview.isNotEmpty()) {
                    previewRows[category.name] = preview
                    countMap[category.name] = params.allFavorites.count { favorite -> favorite.groupId == -category.id }
                }
            }
        }

        val providerPreviews = seriesRepository
            .getCategoryPreviewRows(params.providerId, PREVIEW_ROW_LIMIT)
            .first()

        params.providerCategories
            .sortedBy { it.name.lowercase() }
            .forEach { category ->
            val preview = providerPreviews[category.id].orEmpty().markFavorites(globalFavoriteIds)
            if (preview.isNotEmpty()) {
                previewRows[category.name] = preview
                countMap[category.name] = params.providerCategoryCounts[category.id] ?: preview.size
            }
            }

        return SeriesCatalogSnapshot(
            grouped = previewRows,
            categoryNames = previewRows.keys.toList(),
            categoryCounts = countMap,
            libraryCount = params.libraryCount
        )
    }

    private fun buildSearchCatalog(
        series: List<Series>,
        allFavorites: List<com.streamvault.domain.model.Favorite>,
        customCategories: List<Category>
    ): SeriesCatalogSnapshot {
        val globalFavoriteIds = allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val enrichedSeries = series.map { it.copy(isFavorite = globalFavoriteIds.contains(it.id)) }
        val grouped = enrichedSeries
            .groupBy { it.categoryName ?: UNCATEGORIZED }
            .toMutableMap()

        grouped[FAVORITES_CATEGORY] = enrichedSeries.filter { it.isFavorite }

        customCategories
            .filter { it.id != FAVORITES_SENTINEL_ID }
            .forEach { customCategory ->
                val seriesIdsInGroup = allFavorites
                    .asSequence()
                    .filter { it.groupId == -customCategory.id }
                    .map { it.contentId }
                    .toSet()
                grouped[customCategory.name] = enrichedSeries.filter { it.id in seriesIdsInGroup }
            }

        val customNames = customCategories.map { it.name }.toSet()
        val categoryNames = grouped.keys.sortedWith(
            compareBy<String> {
                when (it) {
                    FAVORITES_CATEGORY -> 0
                    in customNames -> 1
                    else -> 2
                }
            }.thenBy { it }
        )

        val categoryCounts = categoryNames.associateWith { name -> grouped[name]?.size ?: 0 }

        return SeriesCatalogSnapshot(
            grouped = grouped,
            categoryNames = categoryNames,
            categoryCounts = categoryCounts,
            libraryCount = series.size
        )
    }

    private suspend fun loadSelectedCategoryItems(
        request: SelectedSeriesCategoryRequest
    ): SelectedSeriesCategorySnapshot {
        if (request.selectedCategory.isNullOrBlank() || request.query.isNotBlank()) {
            return SelectedSeriesCategorySnapshot()
        }

        val globalFavoriteIds = request.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val (selectedItems, totalCount) = when (request.selectedCategory) {
            FULL_LIBRARY_CATEGORY -> {
                val result = seriesRepository
                    .browseSeries(
                        LibraryBrowseQuery(
                            providerId = request.providerId,
                            categoryId = null,
                            limit = request.loadLimit,
                            offset = 0
                        )
                    )
                    .first()
                result.items to result.totalCount
            }
            FAVORITES_CATEGORY -> {
                val ids = request.allFavorites
                    .asSequence()
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .toList()
                val pagedIds = ids.take(request.loadLimit)
                val items = if (pagedIds.isEmpty()) {
                    emptyList()
                } else {
                    seriesRepository.getSeriesByIds(pagedIds).first().orderByIds(pagedIds)
                }
                items to ids.size
            }
            else -> {
                val customCategory = request.customCategories.firstOrNull { it.name == request.selectedCategory }
                if (customCategory != null) {
                    val ids = request.allFavorites
                        .asSequence()
                        .filter { it.groupId == -customCategory.id }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .toList()
                    val pagedIds = ids.take(request.loadLimit)
                    val items = if (pagedIds.isEmpty()) {
                        emptyList()
                    } else {
                        seriesRepository.getSeriesByIds(pagedIds).first().orderByIds(pagedIds)
                    }
                    items to ids.size
                } else {
                    val providerCategory = request.providerCategories.firstOrNull { it.name == request.selectedCategory }
                    if (providerCategory != null) {
                        val result = seriesRepository
                            .browseSeries(
                                LibraryBrowseQuery(
                                    providerId = request.providerId,
                                    categoryId = providerCategory.id,
                                    limit = request.loadLimit,
                                    offset = 0
                                )
                            )
                            .first()
                        result.items to result.totalCount
                    } else {
                        emptyList<Series>() to 0
                    }
                }
            }
        }

        val enrichedItems = selectedItems.markFavorites(globalFavoriteIds)
        return SelectedSeriesCategorySnapshot(
            items = enrichedItems,
            loadedCount = enrichedItems.size,
            totalCount = totalCount,
            canLoadMore = totalCount > enrichedItems.size
        )
    }

    private fun validateGroupName(name: String, currentGroupId: Long? = null): String? {
        if (name.isBlank()) return "Enter a group name"
        if (name.equals("favorites", ignoreCase = true)) return "Favorites is reserved"

        val duplicate = _uiState.value.categories.any { category ->
            category.id != currentGroupId && category.name.equals(name, ignoreCase = true)
        }
        return if (duplicate) "A series group with that name already exists" else null
    }

    private fun List<Series>.markFavorites(globalFavoriteIds: Set<Long>): List<Series> =
        map { series -> series.copy(isFavorite = series.id in globalFavoriteIds) }

    private fun List<Series>.orderByIds(ids: List<Long>): List<Series> {
        val seriesMap = associateBy { it.id }
        return ids.mapNotNull { seriesMap[it] }
    }
}

private data class SeriesCatalogParams(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int,
    val query: String
)

private data class SeriesCatalogDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int
)

private data class SeriesCatalogSnapshot(
    val grouped: Map<String, List<Series>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

private data class SeriesLibraryLensDependencies(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val history: List<PlaybackHistory>,
    val topRated: List<Series>,
    val fresh: List<Series>
)

private data class SeriesCategorySelectionDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedSeriesCategoryRequest(
    val providerId: Long,
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedSeriesCategorySnapshot(
    val items: List<Series> = emptyList(),
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false
)

data class SeriesUiState(
    val seriesByCategory: Map<String, List<Series>> = emptyMap(),
    val categoryNames: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val libraryCount: Int = 0,
    val favoriteCategoryName: String = "\u2605 Favorites",
    val fullLibraryCategoryName: String = "__full_library__",
    val libraryLensRows: Map<SeriesLibraryLens, List<Series>> = emptyMap(),
    val selectedCategory: String? = null,
    val selectedCategoryItems: List<Series> = emptyList(),
    val selectedCategoryLoadedCount: Int = 0,
    val selectedCategoryTotalCount: Int = 0,
    val canLoadMoreSelectedCategory: Boolean = false,
    val isLoadingSelectedCategory: Boolean = false,
    val searchQuery: String = "",
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val isLoading: Boolean = true,
    val parentalControlLevel: Int = 0,
    val showDialog: Boolean = false,
    val selectedSeriesForDialog: Series? = null,
    val categories: List<Category> = emptyList(),
    val dialogGroupMemberships: List<Long> = emptyList(),
    val userMessage: String? = null,
    val selectedCategoryForOptions: Category? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val isReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val filteredSeries: List<Series> = emptyList(),
    val errorMessage: String? = null
)
