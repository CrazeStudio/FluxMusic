package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioStreamResolverTest {

    @Test
    fun prepareYoutubePlaybackUrlPreservesSignedParametersAndReplacesRn() {
        val signedUrl =
            "https://rr2---sn-example.googlevideo.com/videoplayback?expire=1800000000&sparams=expire%2Cei%2Cip&rn=42&sig=Ab+Cd%2Bef%3D%3D&c=ANDROID_VR"

        val first = AudioStreamResolver.prepareYoutubePlaybackUrl(signedUrl)
        val second = AudioStreamResolver.prepareYoutubePlaybackUrl(signedUrl)

        assertTrue(first.contains("sig=Ab+Cd%2Bef%3D%3D"))
        assertTrue(first.contains("sparams=expire%2Cei%2Cip"))
        assertFalse(first.contains("rn=42"))
        assertNotEquals(first, second)
    }

    @Test
    fun prepareYoutubePlaybackUrlLeavesNonGoogleVideoUrlsUntouched() {
        val pipedProxy = "https://pipedproxy.example.com/videoplayback?id=abc&rn=1"
        assertEquals(pipedProxy, AudioStreamResolver.prepareYoutubePlaybackUrl(pipedProxy))
    }

    @Test
    fun orderCandidatesRespectsQualityAndPrefersMp4OnTie() {
        val opus128 = ResolvedAudioStream("https://example.com/opus128", "ua", mimeType = "audio/webm", bitrateKbps = 128)
        val mp4128 = ResolvedAudioStream("https://example.com/mp4128", "ua", mimeType = "audio/mp4", bitrateKbps = 128)
        val low64 = ResolvedAudioStream("https://example.com/low64", "ua", mimeType = "audio/mp4", bitrateKbps = 64)
        val high256 = ResolvedAudioStream("https://example.com/high256", "ua", mimeType = "audio/webm", bitrateKbps = 256)

        val autoOrder = AudioStreamResolver.orderCandidates(
            listOf(opus128, high256, low64, mp4128),
            AudioQuality.AUTO
        )
        assertEquals("https://example.com/mp4128", autoOrder.first().url)

        val highOrder = AudioStreamResolver.orderCandidates(
            listOf(opus128, high256, low64, mp4128),
            AudioQuality.HIGH
        )
        assertEquals("https://example.com/high256", highOrder.first().url)

        val saverOrder = AudioStreamResolver.orderCandidates(
            listOf(opus128, high256, low64, mp4128),
            AudioQuality.DATA_SAVER
        )
        assertEquals("https://example.com/low64", saverOrder.first().url)
    }
}
