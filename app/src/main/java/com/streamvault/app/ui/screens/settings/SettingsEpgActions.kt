package com.streamvault.app.ui.screens.settings

import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class SettingsEpgActions(
    private val epgSourceRepository: EpgSourceRepository,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    // Tracks the active assignment-collector job per provider so recompositions
    // cancel the old coroutine rather than leaking it in viewModelScope.
    private val assignmentJobs = ConcurrentHashMap<Long, Job>()

    fun loadEpgAssignments(scope: CoroutineScope, providerId: Long) {
        assignmentJobs[providerId]?.cancel()
        assignmentJobs[providerId] = scope.launch {
            epgSourceRepository.getAssignmentsForProvider(providerId).collect { assignments ->
                val summary = epgSourceRepository.getResolutionSummary(providerId)
                uiState.update {
                    it.copy(
                        epgSourceAssignments = it.epgSourceAssignments + (providerId to assignments),
                        epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary)
                    )
                }
            }
        }
    }

    fun addEpgSource(scope: CoroutineScope, name: String, url: String) {
        scope.launch {
            val result = epgSourceRepository.addSource(name, url)
            if (result is Result.Error) {
                uiState.update { it.copy(userMessage = result.message) }
            }
        }
    }

    fun deleteEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            uiState.update { it.copy(epgPendingDeleteSourceId = null) }
            // Query from the repository BEFORE deletion so we capture all affected
            // providers, not just those already loaded into the UI state.
            val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
            epgSourceRepository.deleteSource(sourceId)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun toggleEpgSourceEnabled(scope: CoroutineScope, sourceId: Long, enabled: Boolean) {
        scope.launch {
            // Query from the repository so all assigned providers are refreshed regardless
            // of which ones have been opened in the UI.
            val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
            epgSourceRepository.setSourceEnabled(sourceId, enabled)
            refreshLoadedResolutionSummaries(affectedProviders)
        }
    }

    fun refreshEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds + sourceId) }
            val affectedProviders = loadedProvidersForSource(sourceId)
            val result = epgSourceRepository.refreshSource(sourceId)
            if (result !is Result.Error) {
                refreshLoadedResolutionSummaries(affectedProviders)
            }
            uiState.update {
                it.copy(
                    refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId,
                    userMessage = if (result is Result.Error) result.message else "EPG source refreshed"
                )
            }
        }
    }

    fun assignEpgSourceToProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val existingAssignments = uiState.value.epgSourceAssignments[providerId].orEmpty()
            val nextPriority = (existingAssignments.maxOfOrNull { it.priority } ?: 0) + 1
            val result = epgSourceRepository.assignSourceToProvider(providerId, epgSourceId, nextPriority)
            if (result is Result.Error) {
                uiState.update { it.copy(userMessage = result.message) }
            } else {
                refreshProviderEpgSummary(providerId)
            }
        }
    }

    fun unassignEpgSourceFromProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            epgSourceRepository.unassignSourceFromProvider(providerId, epgSourceId)
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentUp(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index <= 0) return@launch
            val current = assignments[index]
            val previous = assignments[index - 1]
            epgSourceRepository.swapAssignmentPriorities(
                providerId,
                current.epgSourceId, previous.priority,
                previous.epgSourceId, current.priority
            )
            refreshProviderEpgSummary(providerId)
        }
    }

    fun moveEpgSourceAssignmentDown(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
            val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
            if (index == -1 || index >= assignments.lastIndex) return@launch
            val current = assignments[index]
            val next = assignments[index + 1]
            epgSourceRepository.swapAssignmentPriorities(
                providerId,
                current.epgSourceId, next.priority,
                next.epgSourceId, current.priority
            )
            refreshProviderEpgSummary(providerId)
        }
    }

    private suspend fun refreshProviderEpgSummary(providerId: Long) {
        val summary = epgSourceRepository.getResolutionSummary(providerId)
        uiState.update {
            it.copy(epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary))
        }
    }

    private fun loadedProvidersForSource(sourceId: Long): List<Long> =
        uiState.value.epgSourceAssignments
            .filterValues { assignments -> assignments.any { it.epgSourceId == sourceId } }
            .keys
            .toList()

    private suspend fun refreshLoadedResolutionSummaries(providerIds: Iterable<Long>) {
        providerIds.asSequence().distinct().forEach { providerId ->
            refreshProviderEpgSummary(providerId)
        }
    }
}
