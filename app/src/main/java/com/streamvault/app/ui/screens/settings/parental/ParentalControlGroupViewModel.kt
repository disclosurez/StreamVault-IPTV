package com.streamvault.app.ui.screens.settings.parental

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ParentalControlGroupUiState(
    val providerId: Long = -1L,
    val categories: List<Category> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false
)

@HiltViewModel
class ParentalControlGroupViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val providerId: Long = checkNotNull(savedStateHandle["providerId"])
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    val uiState: StateFlow<ParentalControlGroupUiState> = combine(
        categoryRepository.getCategories(providerId),
        _searchQuery,
        _isLoading
    ) { categories, query, loading ->
        val filtered = if (query.isBlank()) {
            categories
        } else {
            categories.filter { it.name.contains(query, ignoreCase = true) }
        }
        ParentalControlGroupUiState(
            providerId = providerId,
            categories = filtered,
            searchQuery = query,
            isLoading = false // After first load, categories flow will emit
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ParentalControlGroupUiState(providerId = providerId, isLoading = true)
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleCategoryProtection(category: Category) {
        viewModelScope.launch {
            when (val result = categoryRepository.setCategoryProtection(
                providerId = providerId,
                categoryId = category.id,
                type = category.type,
                isProtected = !category.isUserProtected
            )) {
                is Result.Success -> Unit
                is Result.Error -> android.util.Log.w(
                    "ParentalControlGroupVM",
                    "Failed to update protection for ${category.name}: ${result.message}",
                    result.exception
                )
                is Result.Loading -> Unit
            }
        }
    }
}
