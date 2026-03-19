package com.streamvault.app.ui.screens.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.streamvault.app.ui.components.SearchInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.ContinueWatchingRow
import com.streamvault.app.ui.components.SavedCategoryContextCard
import com.streamvault.app.ui.components.SavedCategoryShortcut
import com.streamvault.app.ui.components.SavedCategoryShortcutsRow
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.SeriesCard
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.Series
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.dialogs.DeleteGroupDialog
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.shell.BrowseHeroPanel
import com.streamvault.app.ui.components.shell.BrowseSearchLaunchCard
import com.streamvault.app.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.shell.VodActionChip
import com.streamvault.app.ui.components.shell.VodActionChipRow
import com.streamvault.app.ui.components.shell.VodCategoryOption
import com.streamvault.app.ui.components.shell.VodCategoryPickerDialog
import com.streamvault.app.ui.components.shell.VodHeroStrip
import com.streamvault.app.ui.components.shell.VodSectionHeader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(
    onSeriesClick: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: SeriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingSeriesId by remember { mutableStateOf<Long?>(null) }
    var selectedFacet by rememberSaveable { mutableStateOf(SeriesLibraryFacet.ALL.name) }
    var selectedSort by rememberSaveable { mutableStateOf(SeriesLibrarySort.LIBRARY.name) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    BackHandler(enabled = uiState.selectedCategory != null && !uiState.isReorderMode) {
        viewModel.selectCategory(null)
    }

    if (showPinDialog) {
        com.streamvault.app.ui.components.dialogs.PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingSeriesId = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        showPinDialog = false
                        pinError = null
                        pendingSeriesId?.let { onSeriesClick(it) }
                        pendingSeriesId = null
                    } else {
                        pinError = context.getString(R.string.series_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_series),
            subtitle = null,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
        if (uiState.isReorderMode && uiState.reorderCategory != null) {
            ReorderTopBar(
                categoryName = uiState.reorderCategory!!.name,
                onSave = { viewModel.saveReorder() },
                onCancel = { viewModel.exitCategoryReorderMode() },
                subtitle = stringResource(R.string.series_reorder_subtitle)
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.series_loading),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (uiState.errorMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.home_error_load_failed),
                    subtitle = uiState.errorMessage ?: ""
                )
            }
        } else if (uiState.seriesByCategory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.series_no_found),
                    subtitle = stringResource(R.string.series_no_found_subtitle)
                )
            }
        } else {
            SeriesVodContent(
                uiState = uiState,
                selectedFacet = selectedFacet,
                onSelectedFacetChange = { selectedFacet = it },
                selectedSort = selectedSort,
                onSelectedSortChange = { selectedSort = it },
                onSeriesClick = onSeriesClick,
                onProtectedSeriesClick = { seriesId ->
                    pendingSeriesId = seriesId
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onOpenContinueWatching = {
                    selectedFacet = SeriesLibraryFacet.RESUME.name
                    selectedSort = SeriesLibrarySort.LIBRARY.name
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenTopRated = {
                    selectedFacet = SeriesLibraryFacet.TOP_RATED.name
                    selectedSort = SeriesLibrarySort.RATING.name
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenFresh = {
                    selectedFacet = SeriesLibraryFacet.RECENTLY_UPDATED.name
                    selectedSort = SeriesLibrarySort.UPDATED.name
                    viewModel.selectFullLibraryBrowse()
                },
                onLoadMore = viewModel::loadMoreSelectedCategory,
                onDismissReorder = viewModel::exitCategoryReorderMode
            )
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (uiState.showDialog && uiState.selectedSeriesForDialog != null) {
        val series = uiState.selectedSeriesForDialog!!
        com.streamvault.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = series.name,
            groups = uiState.categories.filter { it.isVirtual && it.id != -999L },
            isFavorite = series.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (series.isFavorite) viewModel.removeFavorite(series) else viewModel.addFavorite(series)
            },
            onAddToGroup = { group -> viewModel.addToGroup(series, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(series, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) }
        )
    }

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        DeleteGroupDialog(
            groupName = uiState.groupToDelete!!.name,
            onDismissRequest = { viewModel.cancelDeleteGroup() },
            onConfirmDelete = { viewModel.confirmDeleteGroup() }
        )
    }

    if (uiState.selectedCategoryForOptions != null) {
        val category = uiState.selectedCategoryForOptions!!
        com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onRename = if (category.isVirtual && category.id != -999L) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onDelete = if (category.isVirtual && category.id != -999L) {
                { viewModel.requestDeleteGroup(category) }
            } else null,
            onReorderChannels = if (category.isVirtual) {
                { viewModel.enterCategoryReorderMode(category) }
            } else null
        )
    }

    if (uiState.showRenameGroupDialog && uiState.groupToRename != null) {
        RenameGroupDialog(
            initialName = uiState.groupToRename!!.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }
}

private enum class SeriesLibraryFacet {
    ALL,
    FAVORITES,
    RESUME,
    RECENTLY_UPDATED,
    TOP_RATED
}

@Composable
private fun SeriesVodContent(
    uiState: SeriesUiState,
    selectedFacet: String,
    onSelectedFacetChange: (String) -> Unit,
    selectedSort: String,
    onSelectedSortChange: (String) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onProtectedSeriesClick: (Long) -> Unit,
    onShowDialog: (Series) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenTopRated: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteSeries = uiState.seriesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshSeries = uiState.libraryLensRows[SeriesLibraryLens.FRESH].orEmpty()
    val topRatedSeries = uiState.libraryLensRows[SeriesLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroSeries = freshSeries.firstOrNull() ?: topRatedSeries.firstOrNull() ?: favoriteSeries.firstOrNull()
    val categoryOptions = remember(uiState.categoryNames, uiState.categoryCounts) {
        uiState.categoryNames.map { name ->
            VodCategoryOption(
                name = name,
                count = uiState.categoryCounts[name] ?: 0,
                onClick = { onSelectCategory(name) }
            )
        }
    }

    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = categoryOptions,
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (uiState.selectedCategory == null) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            if (heroSeries != null) {
                item("hero") {
                    VodHeroStrip(
                        title = heroSeries.name,
                        subtitle = heroSeries.plot?.takeIf { it.isNotBlank() }
                            ?: heroSeries.genre
                            ?: stringResource(R.string.series_library_lens_subtitle),
                        actionLabel = stringResource(R.string.player_resume).substringBefore(" "),
                        onClick = {
                            val isLocked = (heroSeries.isAdult || heroSeries.isUserProtected) && uiState.parentalControlLevel == 1
                            if (isLocked) onProtectedSeriesClick(heroSeries.id) else onSeriesClick(heroSeries.id)
                        },
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    )
                }
            }

            item("actions") {
                VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "browse_all",
                                label = stringResource(R.string.library_full_browse_title_series),
                                detail = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                onClick = onSelectFullLibraryBrowse
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.series_categories_title),
                                detail = "${uiState.categoryNames.size} groups",
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        if (favoriteSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "favorites",
                                    label = stringResource(R.string.favorites_title),
                                    detail = stringResource(R.string.library_saved_items_count, favoriteSeries.size),
                                    onClick = { onSelectCategory(uiState.favoriteCategoryName) }
                                )
                            )
                        }
                        if (continueWatching.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "resume",
                                    label = stringResource(R.string.library_lens_continue),
                                    detail = "${continueWatching.size} items",
                                    onClick = onOpenContinueWatching
                                )
                            )
                        }
                        if (topRatedSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedSeries.size} picks",
                                    onClick = onOpenTopRated
                                )
                            )
                        }
                        if (freshSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_series),
                                    detail = "${freshSeries.size} picks",
                                    onClick = onOpenFresh
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            if (continueWatching.isNotEmpty()) {
                item("continue") {
                    ContinueWatchingRow(
                        items = continueWatching,
                        onItemClick = { history -> onSeriesClick(history.seriesId ?: history.contentId) }
                    )
                }
            }

            if (favoriteSeries.isNotEmpty()) {
                item("favorites_header") {
                    VodSectionHeader(
                        title = stringResource(R.string.favorites_title),
                        onSeeAll = { onSelectCategory(uiState.favoriteCategoryName) }
                    )
                }
                item("favorites_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(favoriteSeries, key = { it.id }) { series ->
                            val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                            SeriesCard(
                                series = series,
                                isLocked = isLocked,
                                onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                                onLongClick = { onShowDialog(series) },
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }

            if (freshSeries.isNotEmpty()) {
                item("fresh_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_fresh_series))
                }
                item("fresh_row") {
                    CategoryRow(
                        title = "",
                        items = freshSeries,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) }
                        )
                    }
                }
            }

            if (topRatedSeries.isNotEmpty()) {
                item("top_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_top_rated))
                }
                item("top_row") {
                    CategoryRow(
                        title = "",
                        items = topRatedSeries,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) }
                        )
                    }
                }
            }

            items(
                items = uiState.seriesByCategory.entries.filter { (name, items) ->
                    name != uiState.favoriteCategoryName && items.isNotEmpty()
                }.take(8),
                key = { it.key }
            ) { (categoryName, seriesList) ->
                VodSectionHeader(
                    title = categoryName,
                    onSeeAll = { onSelectCategory(categoryName) }
                )
                CategoryRow(
                    title = "",
                    items = seriesList,
                    onSeeAll = null,
                    keySelector = { it.id }
                ) { series ->
                    val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                    SeriesCard(
                        series = series,
                        isLocked = isLocked,
                        onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                        onLongClick = { onShowDialog(series) }
                    )
                }
            }
        }
        return
    }

    val baseSeries = uiState.selectedCategoryItems
    val resumeSeriesIds = remember(uiState.continueWatching) {
        uiState.continueWatching.mapNotNull { it.seriesId ?: it.contentId }.toSet()
    }
    val activeFacet = remember(selectedFacet) {
        SeriesLibraryFacet.entries.firstOrNull { it.name == selectedFacet } ?: SeriesLibraryFacet.ALL
    }
    val activeSort = remember(selectedSort) {
        SeriesLibrarySort.entries.firstOrNull { it.name == selectedSort } ?: SeriesLibrarySort.LIBRARY
    }
    val filteredGridSeries = remember(baseSeries, activeFacet, activeSort, resumeSeriesIds, uiState.isReorderMode, uiState.filteredSeries) {
        val source = if (uiState.isReorderMode) uiState.filteredSeries else baseSeries
        if (uiState.isReorderMode) source else applySeriesFacetAndSort(
            items = source,
            facet = activeFacet,
            sort = activeSort,
            resumeSeriesIds = resumeSeriesIds
        )
    }
    var draggingSeries by remember { mutableStateOf<Series?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VodActionChipRow(
            actions = buildList {
                add(
                    VodActionChip(
                        key = "back_home",
                        label = stringResource(R.string.nav_series),
                        detail = stringResource(R.string.category_see_all),
                        onClick = { onSelectCategory(null) }
                    )
                )
                add(
                    VodActionChip(
                        key = uiState.fullLibraryCategoryName,
                        label = stringResource(R.string.library_full_browse_title_series),
                        detail = "${uiState.libraryCount} titles",
                        onClick = onSelectFullLibraryBrowse
                    )
                )
                add(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.series_categories_title),
                        detail = "${uiState.categoryNames.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
                if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                    add(
                        VodActionChip(
                            key = uiState.selectedCategory ?: "",
                            label = uiState.selectedCategory ?: stringResource(R.string.nav_series),
                            detail = "${uiState.selectedCategoryTotalCount} titles",
                            onClick = { showCategoryPicker = true }
                        )
                    )
                }
            },
            selectedKey = uiState.selectedCategory,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (!uiState.isReorderMode) {
            SelectionChipRow(
                title = stringResource(R.string.library_filter_title),
                chips = buildSeriesFacetChips(baseSeries, resumeSeriesIds),
                selectedKey = activeFacet.name,
                onChipSelected = onSelectedFacetChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
            SelectionChipRow(
                title = stringResource(R.string.library_sort_title),
                chips = SeriesLibrarySort.entries.map { sort ->
                    SelectionChip(
                        key = sort.name,
                        label = when (sort) {
                            SeriesLibrarySort.LIBRARY -> stringResource(R.string.library_sort_library)
                            SeriesLibrarySort.TITLE -> stringResource(R.string.library_sort_az)
                            SeriesLibrarySort.UPDATED -> stringResource(R.string.library_sort_updated)
                            SeriesLibrarySort.RATING -> stringResource(R.string.library_sort_rating)
                        }
                    )
                },
                selectedKey = activeSort.name,
                onChipSelected = onSelectedSortChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                            draggingSeries = null
                            onDismissReorder()
                            true
                        } else false
                    } else false
                },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VodSectionHeader(
                    title = when (uiState.selectedCategory) {
                        uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_series)
                        else -> uiState.selectedCategory ?: stringResource(R.string.nav_series)
                    }
                )
            }

            if (uiState.isLoadingSelectedCategory) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = stringResource(R.string.series_loading),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                gridItems(filteredGridSeries, key = { it.id }) { series ->
                    val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                    val isDraggingThis = draggingSeries == series
                    SeriesCard(
                        series = series,
                        isLocked = isLocked,
                        isReorderMode = uiState.isReorderMode,
                        isDragging = isDraggingThis,
                        onClick = {
                            if (uiState.isReorderMode) {
                                draggingSeries = if (isDraggingThis) null else series
                            } else if (isLocked) {
                                onProtectedSeriesClick(series.id)
                            } else {
                                onSeriesClick(series.id)
                            }
                        },
                        onLongClick = {
                            if (!uiState.isReorderMode) onShowDialog(series)
                        }
                    )
                }

                if (!uiState.isReorderMode && uiState.canLoadMoreSelectedCategory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreCard(
                            label = stringResource(
                                R.string.library_load_more,
                                uiState.selectedCategoryLoadedCount,
                                uiState.selectedCategoryTotalCount
                            ),
                            onClick = onLoadMore,
                            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class SeriesLibrarySort {
    LIBRARY,
    TITLE,
    UPDATED,
    RATING
}

@Composable
private fun seriesLibraryLensLabel(lens: SeriesLibraryLens): String =
    when (lens) {
        SeriesLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        SeriesLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        SeriesLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        SeriesLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_series)
    }

private fun buildSeriesFacetChips(
    items: List<com.streamvault.domain.model.Series>,
    resumeSeriesIds: Set<Long>
): List<SelectionChip> {
    val favoriteCount = items.count { it.isFavorite }
    val resumeCount = items.count { it.id in resumeSeriesIds }
    val updatedCount = items.count { seriesFreshnessScore(it) > 0L }
    val topRatedCount = items.count { it.rating > 0f }
    return listOf(
        SelectionChip(SeriesLibraryFacet.ALL.name, "All", "${items.size} visible"),
        SelectionChip(SeriesLibraryFacet.FAVORITES.name, "Favorites", "$favoriteCount saved"),
        SelectionChip(SeriesLibraryFacet.RESUME.name, "Resume", "$resumeCount in progress"),
        SelectionChip(SeriesLibraryFacet.RECENTLY_UPDATED.name, "Updated", "$updatedCount tracked"),
        SelectionChip(SeriesLibraryFacet.TOP_RATED.name, "Top Rated", "$topRatedCount rated")
    )
}

private fun applySeriesFacetAndSort(
    items: List<com.streamvault.domain.model.Series>,
    facet: SeriesLibraryFacet,
    sort: SeriesLibrarySort,
    resumeSeriesIds: Set<Long>
): List<com.streamvault.domain.model.Series> {
    val filtered = when (facet) {
        SeriesLibraryFacet.ALL -> items
        SeriesLibraryFacet.FAVORITES -> items.filter { it.isFavorite }
        SeriesLibraryFacet.RESUME -> items.filter { it.id in resumeSeriesIds }
        SeriesLibraryFacet.RECENTLY_UPDATED -> items.filter { seriesFreshnessScore(it) > 0L }
        SeriesLibraryFacet.TOP_RATED -> items.filter { it.rating > 0f }
    }

    return when (sort) {
        SeriesLibrarySort.LIBRARY -> filtered
        SeriesLibrarySort.TITLE -> filtered.sortedBy { it.name.lowercase() }
        SeriesLibrarySort.UPDATED -> filtered.sortedByDescending(::seriesFreshnessScore)
        SeriesLibrarySort.RATING -> filtered.sortedByDescending { it.rating }
    }
}

private fun seriesFreshnessScore(series: com.streamvault.domain.model.Series): Long {
    return series.lastModified
        .takeIf { it > 0L }
        ?: series.releaseDate
            ?.filter { it.isDigit() }
            ?.take(8)
            ?.toLongOrNull()
        ?: 0L
}

