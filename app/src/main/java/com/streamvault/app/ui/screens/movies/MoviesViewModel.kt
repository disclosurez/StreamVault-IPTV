package com.streamvault.app.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
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

enum class MovieLibraryLens {
    FAVORITES,
    CONTINUE,
    TOP_RATED,
    FRESH
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class MoviesViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val movieRepository: MovieRepository,
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

    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategoryLoadLimit = MutableStateFlow(SELECTED_CATEGORY_PAGE_SIZE)

    init {
        viewModelScope.launch {
            try {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        getCustomCategories(ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        movieRepository.getCategoryItemCounts(provider.id),
                        movieRepository.getLibraryCount(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts, libraryCount ->
                        MovieCatalogDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = providerCategories,
                            providerCategoryCounts = providerCategoryCounts,
                            libraryCount = libraryCount
                        )
                    }.combine(_searchQuery) { dependencies, query ->
                        MovieCatalogParams(
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
                                val searchResults = movieRepository.searchMovies(params.providerId, params.query).first()
                                buildSearchCatalog(
                                    movies = searchResults,
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
                            moviesByCategory = snapshot.grouped,
                            categoryNames = snapshot.categoryNames,
                            categoryCounts = snapshot.categoryCounts,
                            libraryCount = snapshot.libraryCount,
                            filteredMovies = if (isReordering) it.filteredMovies else emptyList(),
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load movies") }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        getCustomCategories(ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        movieRepository.getCategoryItemCounts(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts ->
                        MovieCategorySelectionDependencies(
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
                        SelectedMovieCategoryRequest(
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
                                            entry.contentType == ContentType.MOVIE
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
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 24),
                        movieRepository.getTopRatedPreview(provider.id, PREVIEW_ROW_LIMIT),
                        movieRepository.getFreshPreview(provider.id, PREVIEW_ROW_LIMIT)
                    ) { allFavorites, history, topRated, fresh ->
                        MovieLibraryLensDependencies(
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
                        .filter { it.contentType == ContentType.MOVIE }
                        .sortedByDescending { it.lastWatchedAt }
                        .distinctBy { it.contentId }
                        .map { it.contentId }
                        .take(PREVIEW_ROW_LIMIT)
                        .toList()

                    val favoritePreview = if (favoriteIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(favoriteIds).first().orderByIds(favoriteIds)
                    }.markFavorites(globalFavoriteIds)
                    val continuePreview = if (continueIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(continueIds).first().orderByIds(continueIds)
                    }.markFavorites(globalFavoriteIds)

                    _uiState.update {
                        it.copy(
                            libraryLensRows = mapOf(
                                MovieLibraryLens.FAVORITES to favoritePreview,
                                MovieLibraryLens.CONTINUE to continuePreview,
                                MovieLibraryLens.TOP_RATED to dependencies.topRated.markFavorites(globalFavoriteIds),
                                MovieLibraryLens.FRESH to dependencies.fresh.markFavorites(globalFavoriteIds)
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
            getCustomCategories(ContentType.MOVIE).collect { categories ->
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

    fun onShowDialog(movie: Movie) {
        viewModelScope.launch {
            val memberships = favoriteRepository.getGroupMemberships(movie.id, ContentType.MOVIE)
            val isFavorite = favoriteRepository.isFavorite(movie.id, ContentType.MOVIE)
            _uiState.update {
                it.copy(
                    showDialog = true,
                    selectedMovieForDialog = movie.copy(isFavorite = isFavorite),
                    dialogGroupMemberships = memberships
                )
            }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedMovieForDialog = null) }
    }

    fun addFavorite(movie: Movie) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(movie.id, ContentType.MOVIE)
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = true)) }
        }
    }

    fun removeFavorite(movie: Movie) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(movie.id, ContentType.MOVIE)
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = false)) }
        }
    }

    fun addToGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(movie.id, ContentType.MOVIE, groupId = -group.id)
            val memberships = favoriteRepository.getGroupMemberships(movie.id, ContentType.MOVIE)
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun removeFromGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(movie.id, ContentType.MOVIE, groupId = -group.id)
            val memberships = favoriteRepository.getGroupMemberships(movie.id, ContentType.MOVIE)
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
            favoriteRepository.createGroup(normalizedName, contentType = ContentType.MOVIE)
            _uiState.update { it.copy(userMessage = "Created group $normalizedName") }
        }
    }

    fun showCategoryOptions(categoryName: String) {
        val matchedCategory = _uiState.value.categories.find { it.name == categoryName }
            ?: if (categoryName == FAVORITES_CATEGORY) {
                Category(
                    id = FAVORITES_SENTINEL_ID,
                    name = FAVORITES_CATEGORY,
                    type = ContentType.MOVIE,
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
            val moviesInView = loadReorderMovies(category)
            _uiState.update {
                it.copy(
                    isReorderMode = true,
                    reorderCategory = category,
                    filteredMovies = moviesInView
                )
            }
        }
    }

    fun exitCategoryReorderMode() {
        _uiState.update {
            it.copy(
                isReorderMode = false,
                reorderCategory = null,
                filteredMovies = emptyList()
            )
        }
    }

    fun moveItemUp(movie: Movie) {
        val list = _uiState.value.filteredMovies.toMutableList()
        val index = list.indexOf(movie)
        if (index > 0) {
            list.removeAt(index)
            list.add(index - 1, movie)
            _uiState.update { it.copy(filteredMovies = list) }
        }
    }

    fun moveItemDown(movie: Movie) {
        val list = _uiState.value.filteredMovies.toMutableList()
        val index = list.indexOf(movie)
        if (index >= 0 && index < list.lastIndex) {
            list.removeAt(index)
            list.add(index + 1, movie)
            _uiState.update { it.copy(filteredMovies = list) }
        }
    }

    fun saveReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        val currentList = state.filteredMovies

        exitCategoryReorderMode()

        viewModelScope.launch {
            try {
                val groupId = if (category.id == FAVORITES_SENTINEL_ID) null else -category.id
                val favoritesFlow = if (groupId == null) {
                    favoriteRepository.getFavorites(ContentType.MOVIE)
                } else {
                    favoriteRepository.getFavoritesByGroup(groupId)
                }

                val favorites = favoritesFlow.first()
                val favoriteMap = favorites.associateBy { it.contentId }
                val reorderedFavorites = currentList.mapNotNull { movie ->
                    favoriteMap[movie.id]
                }.mapIndexed { index, favorite ->
                    favorite.copy(position = index)
                }

                favoriteRepository.reorderFavorites(reorderedFavorites)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun loadReorderMovies(category: Category): List<Movie> {
        if (!category.isVirtual) return emptyList()

        val groupId = if (category.id == FAVORITES_SENTINEL_ID) null else -category.id
        val favorites = if (groupId == null) {
            favoriteRepository.getFavorites(ContentType.MOVIE).first()
        } else {
            favoriteRepository.getFavoritesByGroup(groupId).first()
        }

        val orderedIds = favorites
            .sortedBy { it.position }
            .map { it.contentId }

        if (orderedIds.isEmpty()) return emptyList()
        return movieRepository.getMoviesByIds(orderedIds).first().orderByIds(orderedIds)
    }

    private suspend fun buildPreviewCatalog(
        params: MovieCatalogParams
    ): MovieCatalogSnapshot {
        val globalFavoriteIds = params.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val previewRows = linkedMapOf<String, List<Movie>>()
        val countMap = linkedMapOf<String, Int>()

        val favoritesIds = params.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .sortedBy { it.position }
            .map { it.contentId }
            .toList()
        if (favoritesIds.isNotEmpty()) {
            val preview = movieRepository.getMoviesByIds(favoritesIds.take(PREVIEW_ROW_LIMIT)).first()
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
            movieRepository.getMoviesByIds(idsToPreload.toList()).first().associateBy { it.id }
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

        val providerPreviews = movieRepository
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

        return MovieCatalogSnapshot(
            grouped = previewRows,
            categoryNames = previewRows.keys.toList(),
            categoryCounts = countMap,
            libraryCount = params.libraryCount
        )
    }

    private fun buildSearchCatalog(
        movies: List<Movie>,
        allFavorites: List<com.streamvault.domain.model.Favorite>,
        customCategories: List<Category>
    ): MovieCatalogSnapshot {
        val globalFavoriteIds = allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val enrichedMovies = movies.map { it.copy(isFavorite = globalFavoriteIds.contains(it.id)) }
        val grouped = enrichedMovies
            .groupBy { it.categoryName ?: UNCATEGORIZED }
            .toMutableMap()

        grouped[FAVORITES_CATEGORY] = enrichedMovies.filter { it.isFavorite }

        customCategories
            .filter { it.id != FAVORITES_SENTINEL_ID }
            .forEach { customCategory ->
                val movieIdsInGroup = allFavorites
                    .asSequence()
                    .filter { it.groupId == -customCategory.id }
                    .map { it.contentId }
                    .toSet()
                grouped[customCategory.name] = enrichedMovies.filter { it.id in movieIdsInGroup }
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

        return MovieCatalogSnapshot(
            grouped = grouped,
            categoryNames = categoryNames,
            categoryCounts = categoryCounts,
            libraryCount = movies.size
        )
    }

    private suspend fun loadSelectedCategoryItems(
        request: SelectedMovieCategoryRequest
    ): SelectedMovieCategorySnapshot {
        if (request.selectedCategory.isNullOrBlank() || request.query.isNotBlank()) {
            return SelectedMovieCategorySnapshot()
        }

        val globalFavoriteIds = request.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val (selectedItems, totalCount) = when (request.selectedCategory) {
            FULL_LIBRARY_CATEGORY -> {
                val result = movieRepository
                    .browseMovies(
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
                    movieRepository.getMoviesByIds(pagedIds).first().orderByIds(pagedIds)
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
                        movieRepository.getMoviesByIds(pagedIds).first().orderByIds(pagedIds)
                    }
                    items to ids.size
                } else {
                    val providerCategory = request.providerCategories.firstOrNull { it.name == request.selectedCategory }
                    if (providerCategory != null) {
                        val result = movieRepository
                            .browseMovies(
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
                        emptyList<Movie>() to 0
                    }
                }
            }
        }

        val enrichedItems = selectedItems.markFavorites(globalFavoriteIds)
        return SelectedMovieCategorySnapshot(
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
        return if (duplicate) "A movie group with that name already exists" else null
    }

    private fun List<Movie>.markFavorites(globalFavoriteIds: Set<Long>): List<Movie> =
        map { movie -> movie.copy(isFavorite = movie.id in globalFavoriteIds) }

    private fun List<Movie>.orderByIds(ids: List<Long>): List<Movie> {
        val movieMap = associateBy { it.id }
        return ids.mapNotNull { movieMap[it] }
    }
}

private data class MovieCatalogParams(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int,
    val query: String
)

private data class MovieCatalogDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int
)

private data class MovieCatalogSnapshot(
    val grouped: Map<String, List<Movie>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

private data class MovieLibraryLensDependencies(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val history: List<PlaybackHistory>,
    val topRated: List<Movie>,
    val fresh: List<Movie>
)

private data class MovieCategorySelectionDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedMovieCategoryRequest(
    val providerId: Long,
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedMovieCategorySnapshot(
    val items: List<Movie> = emptyList(),
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false
)

data class MoviesUiState(
    val moviesByCategory: Map<String, List<Movie>> = emptyMap(),
    val categoryNames: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val libraryCount: Int = 0,
    val favoriteCategoryName: String = "\u2605 Favorites",
    val fullLibraryCategoryName: String = "__full_library__",
    val libraryLensRows: Map<MovieLibraryLens, List<Movie>> = emptyMap(),
    val selectedCategory: String? = null,
    val selectedCategoryItems: List<Movie> = emptyList(),
    val selectedCategoryLoadedCount: Int = 0,
    val selectedCategoryTotalCount: Int = 0,
    val canLoadMoreSelectedCategory: Boolean = false,
    val isLoadingSelectedCategory: Boolean = false,
    val searchQuery: String = "",
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val isLoading: Boolean = true,
    val parentalControlLevel: Int = 0,
    val showDialog: Boolean = false,
    val selectedMovieForDialog: Movie? = null,
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
    val filteredMovies: List<Movie> = emptyList(),
    val errorMessage: String? = null
)
