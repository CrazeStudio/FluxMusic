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
    @Test
    fun visionOsRequestBodyContainsCompleteClientAndPlaybackContext() {
        val client = org.json.JSONObject()
            .put("clientName", "VISIONOS")
            .put("clientVersion", "1.04")
            .put("clientScreen", "WATCH")
            .put("platform", "MOBILE")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice17,1")
            .put("osName", "visionOS")
            .put("osVersion", "26.6.0.23O770")
            .put("hl", "en")
            .put("gl", "IN")
            .put("utcOffsetMinutes", 0)
            .put("visitorData", "visitor-token")

        val body = AudioStreamResolver.buildVisionOsRequestBody(client)
        val context = body.getJSONObject("context")

        assertEquals("VISIONOS", context.getJSONObject("client").getString("clientName"))
        assertEquals("visitor-token", context.getJSONObject("client").getString("visitorData"))
        assertTrue(context.getJSONObject("request").getBoolean("useSsl"))
        assertFalse(context.getJSONObject("user").getBoolean("lockedSafetyMode"))
        assertEquals(0, context.getJSONObject("request").getJSONArray("internalExperimentFlags").length())
    }

    @Test
    fun signedStreamExpiryUsesSafetyMarginAndNeverExtendsProviderDeadline() {
        val now = 1_800_000_000_000L
        val providerExpirySeconds = now / 1000L + 20L
        val url = "https://rr1.googlevideo.com/videoplayback?expire=$providerExpirySeconds"

        val expiry = AudioStreamResolver.calculateExpiry(url, now)

        assertEquals(providerExpirySeconds * 1000L - 45_000L, expiry)
        assertTrue("An almost-expired signed URL must not be extended", expiry < now)
    }

    @Test
    fun unsignedStreamExpiryUsesBoundedInMemoryCacheTtl() {
        val now = 1_800_000_000_000L

        val expiry = AudioStreamResolver.calculateExpiry(
            "https://example.com/audio",
            now
        )

        assertEquals(now + AudioStreamResolver.STREAM_CACHE_TTL_MS, expiry)
    }

}
