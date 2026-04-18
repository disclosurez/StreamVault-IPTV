package com.streamvault.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgResolutionEngine
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.Result
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class EpgSourceRepositoryImplTest {

    private val context: Context = mock()
    private val contentResolver: ContentResolver = mock()
    private val epgSourceDao: EpgSourceDao = mock()
    private val providerEpgSourceDao: ProviderEpgSourceDao = mock()
    private val channelEpgMappingDao: ChannelEpgMappingDao = mock()
    private val epgChannelDao: EpgChannelDao = mock()
    private val epgProgrammeDao: EpgProgrammeDao = mock()
    private val xmltvParser: XmltvParser = mock()
    private val okHttpClient: OkHttpClient = mock()
    private val resolutionEngine: EpgResolutionEngine = mock()

    private lateinit var repository: EpgSourceRepositoryImpl

    @Before
    fun setup() {
        whenever(context.contentResolver).thenReturn(contentResolver)
        repository = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            channelEpgMappingDao = channelEpgMappingDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClient,
            resolutionEngine = resolutionEngine
        )
    }

    @Test
    fun `assignSourceToProvider_resolvesProviderImmediately`() = runTest {
        whenever(epgSourceDao.getById(10L)).thenReturn(
            EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml")
        )

        val result = repository.assignSourceToProvider(providerId = 7L, epgSourceId = 10L, priority = 1)

        assertThat(result is Result.Success).isTrue()
        verify(providerEpgSourceDao).insert(any())
        verify(resolutionEngine).resolveForProvider(7L)
    }

    @Test
    fun `setSourceEnabled_resolvesEachAffectedProviderOnce`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(7L, 8L, 7L))

        repository.setSourceEnabled(10L, enabled = false)

        verify(epgSourceDao).setEnabled(eq(10L), eq(false), any())
        verify(resolutionEngine).resolveForProvider(7L)
        verify(resolutionEngine).resolveForProvider(8L)
        verifyNoMoreInteractions(resolutionEngine)
    }

    @Test
    fun `deleteSource_rebuildsAffectedProviderMappings`() = runTest {
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(listOf(4L, 5L))

        repository.deleteSource(10L)

        verify(epgProgrammeDao).deleteBySource(10L)
        verify(epgChannelDao).deleteBySource(10L)
        verify(epgSourceDao).delete(10L)
        verify(resolutionEngine).resolveForProvider(4L)
        verify(resolutionEngine).resolveForProvider(5L)
    }

    @Test
    fun `applyManualOverride_persistsManualExternalMapping`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgChannelDao.getBySourceAndChannelId(10L, "bbc.one")).thenReturn(
            EpgChannelEntity(
                id = 5L,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                displayName = "BBC One"
            )
        )
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(null)

        val result = repository.applyManualOverride(
            providerId = 7L,
            channelId = 101L,
            epgSourceId = 10L,
            xmltvChannelId = "bbc.one"
        )

        assertThat(result is Result.Success).isTrue()
        val captor = argumentCaptor<ChannelEpgMappingEntity>()
        verify(channelEpgMappingDao).upsert(captor.capture())
        assertThat(captor.firstValue.providerId).isEqualTo(7L)
        assertThat(captor.firstValue.providerChannelId).isEqualTo(101L)
        assertThat(captor.firstValue.epgSourceId).isEqualTo(10L)
        assertThat(captor.firstValue.xmltvChannelId).isEqualTo("bbc.one")
        assertThat(captor.firstValue.sourceType).isEqualTo(EpgSourceType.EXTERNAL.name)
        assertThat(captor.firstValue.matchType).isEqualTo(EpgMatchType.MANUAL.name)
        assertThat(captor.firstValue.isManualOverride).isTrue()
    }

    @Test
    fun `clearManualOverride_rebuildsAutomaticResolution`() = runTest {
        whenever(channelEpgMappingDao.getForChannel(7L, 101L)).thenReturn(
            ChannelEpgMappingEntity(
                id = 1L,
                providerChannelId = 101L,
                providerId = 7L,
                sourceType = EpgSourceType.EXTERNAL.name,
                epgSourceId = 10L,
                xmltvChannelId = "bbc.one",
                matchType = EpgMatchType.MANUAL.name,
                confidence = 1f,
                isManualOverride = true
            )
        )

        val result = repository.clearManualOverride(providerId = 7L, channelId = 101L)

        assertThat(result is Result.Success).isTrue()
        verify(resolutionEngine).resolveForProvider(7L)
    }

    @Test
    fun `getOverrideCandidates_readsEnabledAssignedSourcesOnly`() = runTest {
        whenever(providerEpgSourceDao.getEnabledForProviderSync(7L)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1L, providerId = 7L, epgSourceId = 10L, priority = 0))
        )
        whenever(epgSourceDao.getAllSync()).thenReturn(
            listOf(EpgSourceEntity(id = 10L, name = "Primary", url = "https://example.com/epg.xml"))
        )
        whenever(epgChannelDao.getBySource(10L)).thenReturn(
            listOf(
                EpgChannelEntity(id = 1L, epgSourceId = 10L, xmltvChannelId = "bbc.one", displayName = "BBC One"),
                EpgChannelEntity(id = 2L, epgSourceId = 10L, xmltvChannelId = "cnn.intl", displayName = "CNN International")
            )
        )

        val result = repository.getOverrideCandidates(providerId = 7L, query = "bbc")

        assertThat(result.map { it.displayName }).containsExactly("BBC One")
        assertThat(result.single().epgSourceName).isEqualTo("Primary")
    }

    @Test
    fun `refreshSource_closesDecompressedXmlStream`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml.gz"
        )
        val decompressedStream = CloseTrackingInputStream()

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenReturn(decompressedStream)
        whenever(providerEpgSourceDao.getProviderIdsForSourceSync(10L)).thenReturn(emptyList())

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Success).isTrue()
        assertThat(decompressedStream.closed).isTrue()
    }

    @Test
    fun `refreshSource returns typed error when parser hits oversized chunked response`() = runTest {
        val source = EpgSourceEntity(
            id = 10L,
            name = "Primary",
            url = "content://epg/source.xml"
        )

        whenever(epgSourceDao.getById(10L)).thenReturn(source)
        whenever(contentResolver.openInputStream(Uri.parse(source.url))).thenReturn(ByteArrayInputStream("<tv/>".toByteArray()))
        whenever(xmltvParser.maybeDecompressGzip(eq(source.url), any())).thenAnswer { it.arguments[1] }
        whenever(xmltvParser.parseStreamingWithChannels(any(), any(), any())).thenThrow(IOException("EPG response too large (>200 MB)"))

        val result = repository.refreshSource(10L)

        assertThat(result is Result.Error).isTrue()
        assertThat((result as Result.Error).message).isEqualTo("EPG response exceeded 200 MB limit")
        verify(epgSourceDao).updateRefreshStatus(eq(10L), any(), eq("EPG response exceeded 200 MB limit"))
    }

    private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf()) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
