package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class XtreamStreamUrlResolverTest {
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }


    @Test
    fun buildPlaybackUrl_uses_live_container_extension_when_present() {
        val url = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = "https://stream.example.com",
            username = "alice",
            password = "secret",
            kind = XtreamStreamKind.LIVE,
            streamId = 123,
            containerExtension = ".M3U8"
        )

        assertThat(url).isEqualTo("https://stream.example.com/live/alice/secret/123.m3u8")
    }

    @Test
    fun buildInternalStreamUrl_normalizes_container_extension() {
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = ".TS",
            directSource = "https://edge.example.com/live/456/index.ts"
        )

        assertThat(url).isEqualTo("xtream://9/live/456?ext=ts&src=https%3A%2F%2Fedge.example.com%2Flive%2F456%2Findex.ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.containerExtension).isEqualTo("ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.directSource).isEqualTo("https://edge.example.com/live/456/index.ts")
    }

    @Test
    fun resolveWithMetadata_prefers_allowed_direct_source() = runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "ts",
            directSource = "http://edge.example.com/live/456/index.ts?exp=1774017000"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("http://edge.example.com/live/456/index.ts?exp=1774017000")
        assertThat(resolved?.expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun resolveWithMetadata_ignores_unsupported_direct_source_scheme() = runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                ProviderEntity(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "m3u8",
            directSource = "ftp://edge.example.com/live/456/index.m3u8"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.m3u8")
    }

    @Test
    fun extractStreamExpirationTime_reads_unix_seconds_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123&expire=1774017000"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_reads_iso_encoded_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?expires_at=2026-03-20T14%3A30%3A00Z"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_returns_null_when_missing() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123"
        )

        assertThat(expirationTime).isNull()
    }

    private class FakeProviderDao(
        private val provider: ProviderEntity?
    ) : ProviderDao() {
        override fun getAll() = flowOf(listOfNotNull(provider))
        override suspend fun getAllSync(): List<ProviderEntity> = listOfNotNull(provider)
        override fun getActive() = flowOf(provider)
        override suspend fun getByUrlAndUser(serverUrl: String, username: String): ProviderEntity? = null
        override suspend fun getById(id: Long): ProviderEntity? = provider?.takeIf { it.id == id }
        override suspend fun getByIds(ids: List<Long>): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.id in ids }
        override suspend fun insert(provider: ProviderEntity): Long = provider.id
        override suspend fun update(provider: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override suspend fun updateEpgUrl(id: Long, epgUrl: String) = Unit
    }
}
