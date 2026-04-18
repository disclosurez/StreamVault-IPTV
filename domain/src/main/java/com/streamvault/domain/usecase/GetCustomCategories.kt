package com.streamvault.domain.usecase

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VirtualGroup
import com.streamvault.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class GetCustomCategories @Inject constructor(
    private val favoriteRepository: FavoriteRepository
) {
    private val logger = Logger.getLogger("GetCustomCategories")
    operator fun invoke(providerId: Long, contentType: ContentType = ContentType.LIVE): Flow<List<Category>> =
        invoke(listOf(providerId), contentType)

    operator fun invoke(providerIds: List<Long>, contentType: ContentType = ContentType.LIVE): Flow<List<Category>> {
        if (providerIds.isEmpty()) {
            return flowOf(emptyList())
        }

        return combine(
            favoriteRepository.getGroups(providerIds, contentType),
            favoriteRepository.getFavorites(providerIds, contentType)
        ) { groups, favorites ->
            buildCategories(
                groups = groups,
                favorites = favorites,
                contentType = contentType
            )
        }.catch { e ->
            logger.log(Level.WARNING, "Failed to load custom categories", e)
            emit(emptyList())
        }
    }

    private fun buildCategories(
        groups: List<VirtualGroup>,
        favorites: List<Favorite>,
        contentType: ContentType
    ): List<Category> {
        val groupCounts = favorites
            .asSequence()
            .mapNotNull(Favorite::groupId)
            .groupingBy { it }
            .eachCount()
        val globalCount = favorites.count { it.groupId == null }

        val categories = groups.map { group ->
            Category(
                id = -group.id,
                name = group.name,
                type = contentType,
                isVirtual = true,
                count = groupCounts.getOrDefault(group.id, 0)
            )
        }.toMutableList()

        categories.add(
            index = 0,
            element = Category(
                id = VirtualCategoryIds.FAVORITES,
                name = "Favorites",
                type = contentType,
                isVirtual = true,
                count = globalCount
            )
        )

        return categories.toList()
    }
}
