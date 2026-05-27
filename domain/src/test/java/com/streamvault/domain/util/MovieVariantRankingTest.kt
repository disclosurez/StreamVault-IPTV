package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MovieVariantRankingTest {

    @Test
    fun `ranks 4k variants above 1080p and hd`() {
        val fourK = movieVariantQualityScore("4K-A+ - Apple Music Live: Lady Gaga MAYHEM Requiem")
        val fullHd = movieVariantQualityScore("A+ - Apple Music Live: Lady Gaga MAYHEM Requiem 1080p")
        val hd = movieVariantQualityScore("A+ - Apple Music Live: Lady Gaga MAYHEM Requiem HD")

        assertThat(fourK).isGreaterThan(fullHd)
        assertThat(fullHd).isGreaterThan(hd)
    }

    @Test
    fun `recognizes quality bonuses for same resolution`() {
        val remux = movieVariantQualityScore("Movie Title 1080p REMUX")
        val webRip = movieVariantQualityScore("Movie Title 1080p WEBRip")

        assertThat(remux).isGreaterThan(webRip)
    }
}
