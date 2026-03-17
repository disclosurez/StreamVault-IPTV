package com.streamvault.app.ui.screens.movies

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
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.streamvault.app.ui.components.MovieCard
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.SavedCategoryContextCard
import com.streamvault.app.ui.components.SavedCategoryShortcut
import com.streamvault.app.ui.components.SavedCategoryShortcutsRow
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.Movie
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.border
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.dialogs.DeleteGroupDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.streamvault.app.ui.components.shell.BrowseSearchLaunchCard
import com.streamvault.app.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import androidx.tv.material3.Border
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.shell.VodActionChip
import com.streamvault.app.ui.components.shell.VodActionChipRow
import com.streamvault.app.ui.components.shell.VodCategoryOption
import com.streamvault.app.ui.components.shell.VodCategoryPickerDialog
import com.streamvault.app.ui.components.shell.VodHeroStrip
import com.streamvault.app.ui.components.shell.VodSectionHeader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviesScreen(
    onMovieClick: (Movie) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: MoviesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedFacet by rememberSaveable { mutableStateOf(MovieLibraryFacet.ALL.name) }
    var selectedSort by rememberSaveable { mutableStateOf(MovieLibrarySort.LIBRARY.name) }
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
                pendingMovie = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        showPinDialog = false
                        pinError = null
                        pendingMovie?.let { onMovieClick(it) }
                        pendingMovie = null
                    } else {
                        pinError = context.getString(R.string.movies_incorrect_pin)
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
            title = stringResource(R.string.nav_movies),
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
                subtitle = stringResource(R.string.movies_reorder_subtitle)
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
                        text = stringResource(R.string.movies_loading),
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
        } else if (uiState.moviesByCategory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.movies_no_found),
                    subtitle = stringResource(R.string.movies_no_found_subtitle)
                )
            }
        } else {
            MoviesVodContent(
                uiState = uiState,
                selectedFacet = selectedFacet,
                onSelectedFacetChange = { selectedFacet = it },
                selectedSort = selectedSort,
                onSelectedSortChange = { selectedSort = it },
                onMovieClick = onMovieClick,
                onProtectedMovieClick = { movie ->
                    pendingMovie = movie
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onOpenContinueWatching = {
                    selectedFacet = MovieLibraryFacet.RESUME.name
                    selectedSort = MovieLibrarySort.LIBRARY.name
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenTopRated = {
                    selectedFacet = MovieLibraryFacet.TOP_RATED.name
                    selectedSort = MovieLibrarySort.RATING.name
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenFresh = {
                    selectedFacet = MovieLibraryFacet.ALL.name
                    selectedSort = MovieLibrarySort.RELEASE.name
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

    if (uiState.showDialog && uiState.selectedMovieForDialog != null) {
        val movie = uiState.selectedMovieForDialog!!
        com.streamvault.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = movie.name,
            groups = uiState.categories.filter { it.isVirtual && it.id != -999L },
            isFavorite = movie.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (movie.isFavorite) viewModel.removeFavorite(movie) else viewModel.addFavorite(movie)
            },
            onAddToGroup = { group -> viewModel.addToGroup(movie, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(movie, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) }
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
                {
                    viewModel.requestDeleteGroup(category)
                }
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

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        DeleteGroupDialog(
            groupName = uiState.groupToDelete!!.name,
            onDismissRequest = { viewModel.cancelDeleteGroup() },
            onConfirmDelete = { viewModel.confirmDeleteGroup() }
        )
    }
}

private enum class MovieLibraryFacet {
    ALL,
    FAVORITES,
    RESUME,
    UNWATCHED,
    TOP_RATED
}

@Composable
private fun MoviesVodContent(
    uiState: MoviesUiState,
    selectedFacet: String,
    onSelectedFacetChange: (String) -> Unit,
    selectedSort: String,
    onSelectedSortChange: (String) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onProtectedMovieClick: (Movie) -> Unit,
    onShowDialog: (Movie) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenTopRated: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteMovies = uiState.moviesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshMovies = uiState.libraryLensRows[MovieLibraryLens.FRESH].orEmpty()
    val topRatedMovies = uiState.libraryLensRows[MovieLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroMovie = freshMovies.firstOrNull() ?: topRatedMovies.firstOrNull() ?: favoriteMovies.firstOrNull()
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
            if (heroMovie != null) {
                item("hero") {
                    VodHeroStrip(
                        title = heroMovie.name,
                        subtitle = heroMovie.plot?.takeIf { it.isNotBlank() }
                            ?: heroMovie.year
                            ?: stringResource(R.string.movies_library_lens_subtitle),
                        actionLabel = stringResource(R.string.player_resume).substringBefore(" "),
                        onClick = {
                            val isLocked = (heroMovie.isAdult || heroMovie.isUserProtected) && uiState.parentalControlLevel == 1
                            if (isLocked) onProtectedMovieClick(heroMovie) else onMovieClick(heroMovie)
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
                                label = stringResource(R.string.library_full_browse_title_movies),
                                detail = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                onClick = onSelectFullLibraryBrowse
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.movies_categories_title),
                                detail = "${uiState.categoryNames.size} groups",
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        if (favoriteMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "favorites",
                                    label = stringResource(R.string.favorites_title),
                                    detail = stringResource(R.string.library_saved_items_count, favoriteMovies.size),
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
                        if (topRatedMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedMovies.size} picks",
                                    onClick = onOpenTopRated
                                )
                            )
                        }
                        if (freshMovies.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = MovieLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_movies),
                                    detail = "${freshMovies.size} picks",
                                    onClick = onOpenFresh
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            if (continueWatching.isNotEmpty()) {
                item("continue_watching") {
                    ContinueWatchingRow(
                        items = continueWatching,
                        onItemClick = { history ->
                            onMovieClick(
                                Movie(
                                    id = history.contentId,
                                    name = history.title,
                                    posterUrl = history.posterUrl,
                                    streamUrl = history.streamUrl,
                                    providerId = history.providerId
                                )
                            )
                        }
                    )
                }
            }

            if (favoriteMovies.isNotEmpty()) {
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
                        items(favoriteMovies, key = { it.id }) { movie ->
                            val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                            MovieCard(
                                movie = movie,
                                isLocked = isLocked,
                                onClick = {
                                    if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie)
                                },
                                onLongClick = { onShowDialog(movie) },
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }

            if (freshMovies.isNotEmpty()) {
                item("fresh_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_fresh_movies))
                }
                item("fresh_row") {
                    CategoryRow(
                        title = "",
                        items = freshMovies,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { movie ->
                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                        MovieCard(
                            movie = movie,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                            onLongClick = { onShowDialog(movie) }
                        )
                    }
                }
            }

            if (topRatedMovies.isNotEmpty()) {
                item("top_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_top_rated))
                }
                item("top_row") {
                    CategoryRow(
                        title = "",
                        items = topRatedMovies,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { movie ->
                        val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                        MovieCard(
                            movie = movie,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                            onLongClick = { onShowDialog(movie) }
                        )
                    }
                }
            }

            items(
                items = uiState.moviesByCategory.entries.filter { (name, items) ->
                    name != uiState.favoriteCategoryName && items.isNotEmpty()
                }.take(8),
                key = { it.key }
            ) { (categoryName, movies) ->
                VodSectionHeader(
                    title = categoryName,
                    onSeeAll = { onSelectCategory(categoryName) }
                )
                CategoryRow(
                    title = "",
                    items = movies,
                    onSeeAll = null,
                    keySelector = { it.id }
                ) { movie ->
                    val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                    MovieCard(
                        movie = movie,
                        isLocked = isLocked,
                        onClick = { if (isLocked) onProtectedMovieClick(movie) else onMovieClick(movie) },
                        onLongClick = { onShowDialog(movie) }
                    )
                }
            }
        }
        return
    }

    val baseMovies = uiState.selectedCategoryItems
    val resumeMovieIds = remember(uiState.continueWatching) { uiState.continueWatching.map { it.contentId }.toSet() }
    val activeFacet = remember(selectedFacet) {
        MovieLibraryFacet.entries.firstOrNull { it.name == selectedFacet } ?: MovieLibraryFacet.ALL
    }
    val activeSort = remember(selectedSort) {
        MovieLibrarySort.entries.firstOrNull { it.name == selectedSort } ?: MovieLibrarySort.LIBRARY
    }
    val filteredGridMovies = remember(baseMovies, activeFacet, activeSort, resumeMovieIds, uiState.isReorderMode, uiState.filteredMovies) {
        val source = if (uiState.isReorderMode) uiState.filteredMovies else baseMovies
        if (uiState.isReorderMode) source else applyMovieFacetAndSort(
            items = source,
            facet = activeFacet,
            sort = activeSort,
            resumeMovieIds = resumeMovieIds
        )
    }
    var draggingMovie by remember { mutableStateOf<Movie?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VodActionChipRow(
            actions = buildList {
                add(
                    VodActionChip(
                        key = "back_home",
                        label = stringResource(R.string.nav_movies),
                        detail = stringResource(R.string.category_see_all),
                        onClick = { onSelectCategory(null) }
                    )
                )
                add(
                    VodActionChip(
                        key = uiState.fullLibraryCategoryName,
                        label = stringResource(R.string.library_full_browse_title_movies),
                        detail = "${uiState.libraryCount} titles",
                        onClick = onSelectFullLibraryBrowse
                    )
                )
                add(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.movies_categories_title),
                        detail = "${uiState.categoryNames.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
                if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                    add(
                        VodActionChip(
                            key = uiState.selectedCategory ?: "",
                            label = uiState.selectedCategory ?: stringResource(R.string.nav_movies),
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
                chips = buildMovieFacetChips(baseMovies, resumeMovieIds),
                selectedKey = activeFacet.name,
                onChipSelected = onSelectedFacetChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
            SelectionChipRow(
                title = stringResource(R.string.library_sort_title),
                chips = MovieLibrarySort.entries.map { sort ->
                    SelectionChip(
                        key = sort.name,
                        label = when (sort) {
                            MovieLibrarySort.LIBRARY -> stringResource(R.string.library_sort_library)
                            MovieLibrarySort.TITLE -> stringResource(R.string.library_sort_az)
                            MovieLibrarySort.RELEASE -> stringResource(R.string.library_sort_release)
                            MovieLibrarySort.RATING -> stringResource(R.string.library_sort_rating)
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
                            draggingMovie = null
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
                        uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_movies)
                        else -> uiState.selectedCategory ?: stringResource(R.string.nav_movies)
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
                                text = stringResource(R.string.movies_loading),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                gridItems(filteredGridMovies, key = { it.id }) { movie ->
                    val isLocked = (movie.isAdult || movie.isUserProtected) && uiState.parentalControlLevel == 1
                    val isDraggingThis = draggingMovie == movie
                    MovieCard(
                        movie = movie,
                        isLocked = isLocked,
                        isReorderMode = uiState.isReorderMode,
                        isDragging = isDraggingThis,
                        onClick = {
                            if (uiState.isReorderMode) {
                                draggingMovie = if (isDraggingThis) null else movie
                            } else if (isLocked) {
                                onProtectedMovieClick(movie)
                            } else {
                                onMovieClick(movie)
                            }
                        },
                        onLongClick = {
                            if (!uiState.isReorderMode) onShowDialog(movie)
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

private enum class MovieLibrarySort {
    LIBRARY,
    TITLE,
    RELEASE,
    RATING
}

@Composable
private fun movieLibraryLensLabel(lens: MovieLibraryLens): String =
    when (lens) {
        MovieLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        MovieLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        MovieLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        MovieLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_movies)
    }

private fun buildMovieFacetChips(
    items: List<Movie>,
    resumeMovieIds: Set<Long>
): List<SelectionChip> {
    val favoriteCount = items.count { it.isFavorite }
    val resumeCount = items.count { it.id in resumeMovieIds || it.watchProgress > 0L }
    val unwatchedCount = items.count { it.watchProgress <= 0L && it.id !in resumeMovieIds }
    val topRatedCount = items.count { it.rating > 0f }
    return listOf(
        SelectionChip(MovieLibraryFacet.ALL.name, "All", "${items.size} visible"),
        SelectionChip(MovieLibraryFacet.FAVORITES.name, "Favorites", "$favoriteCount saved"),
        SelectionChip(MovieLibraryFacet.RESUME.name, "Resume", "$resumeCount in progress"),
        SelectionChip(MovieLibraryFacet.UNWATCHED.name, "Unwatched", "$unwatchedCount not started"),
        SelectionChip(MovieLibraryFacet.TOP_RATED.name, "Top Rated", "$topRatedCount rated")
    )
}

private fun applyMovieFacetAndSort(
    items: List<Movie>,
    facet: MovieLibraryFacet,
    sort: MovieLibrarySort,
    resumeMovieIds: Set<Long>
): List<Movie> {
    val filtered = when (facet) {
        MovieLibraryFacet.ALL -> items
        MovieLibraryFacet.FAVORITES -> items.filter { it.isFavorite }
        MovieLibraryFacet.RESUME -> items.filter { it.id in resumeMovieIds || it.watchProgress > 0L }
        MovieLibraryFacet.UNWATCHED -> items.filter { it.watchProgress <= 0L && it.id !in resumeMovieIds }
        MovieLibraryFacet.TOP_RATED -> items.filter { it.rating > 0f }
    }

    return when (sort) {
        MovieLibrarySort.LIBRARY -> filtered
        MovieLibrarySort.TITLE -> filtered.sortedBy { it.name.lowercase() }
        MovieLibrarySort.RELEASE -> filtered.sortedByDescending(::movieReleaseScore)
        MovieLibrarySort.RATING -> filtered.sortedByDescending { it.rating }
    }
}

private fun movieReleaseScore(movie: Movie): Long {
    return movie.releaseDate
        ?.filter { it.isDigit() }
        ?.take(8)
        ?.toLongOrNull()
        ?: movie.year?.toLongOrNull()
        ?: 0L
}


