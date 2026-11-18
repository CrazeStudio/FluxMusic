package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONObject
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

    private val androidProfile = AudioStreamResolver.ClientProfile(
        name = "ANDROID",
        version = "19.45.38",
        clientId = "3",
        userAgent = "FluxMusicTest/1.0",
        client = JSONObject().put("clientName", "ANDROID")
    )

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
        val otherUrl = "https://media.example.com/videoplayback?id=abc&rn=1"
        assertEquals(otherUrl, AudioStreamResolver.prepareYoutubePlaybackUrl(otherUrl))
    }

    @Test
    fun resolveIdentityForUrlMatchesKnownClientUserAgents() {
        val (vrUa, vrHeaders) = AudioStreamResolver.resolveIdentityForUrl(
            "https://rr1.googlevideo.com/videoplayback?c=ANDROID_VR&itag=140"
        )
        assertEquals(AudioStreamResolver.ANDROID_VR_USER_AGENT, vrUa)
        assertTrue(vrHeaders.isEmpty())

        val (iosUa, iosHeaders) = AudioStreamResolver.resolveIdentityForUrl(
            "https://rr1.googlevideo.com/videoplayback?c=IOS&itag=140"
        )
        assertEquals(AudioStreamResolver.IOS_USER_AGENT, iosUa)
        assertTrue(iosHeaders.isEmpty())

        val (webUa, webHeaders) = AudioStreamResolver.resolveIdentityForUrl(
            "https://rr1.googlevideo.com/videoplayback?c=WEB&itag=140"
        )
        assertEquals(AudioStreamResolver.USER_AGENT, webUa)
        assertEquals("https://www.youtube.com", webHeaders["Origin"])
    }

    @Test
    fun parserReturnsOnlyDirectAudioUrlsAndPreservesFormatMetadata() {
        val response = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 140,
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1.googlevideo.com/videoplayback?expire=1800000000&itag=140",
                    "averageBitrate": 128000,
                    "audioTrack": { "audioIsDefault": true }
                  },
                  {
                    "itag": 251,
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback&sp=sig&s=encrypted",
                    "bitrate": 160000
                  },
                  {
                    "itag": 18,
                    "mimeType": "video/mp4",
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=18"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val candidates = AudioStreamResolver.parseAudioCandidates(
            response,
            androidProfile,
            AudioQuality.AUTO
        )

        assertEquals(1, candidates.size)
        assertEquals("audio/mp4", candidates.single().mimeType)
        assertEquals(128, candidates.single().bitrateKbps)
        assertEquals("ANDROID", candidates.single().source)
        assertTrue(candidates.single().url.contains("itag=140"))
    }

    @Test
    fun parserDoesNotTreatSignatureCipherAsAPlayableUrl() {
        val response = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback&s=encrypted"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertTrue(
            AudioStreamResolver.parseAudioCandidates(
                response,
                androidProfile,
                AudioQuality.AUTO
            ).isEmpty()
        )
    }

    @Test
    fun orderCandidatesRespectsQualityAndPrefersMp4OnTie() {
        val opus128 = ResolvedAudioStream(
            "https://example.com/opus128", "ua", mimeType = "audio/webm", bitrateKbps = 128
        )
        val mp4128 = ResolvedAudioStream(
            "https://example.com/mp4128", "ua", mimeType = "audio/mp4", bitrateKbps = 128
        )
        val low64 = ResolvedAudioStream(
            "https://example.com/low64", "ua", mimeType = "audio/mp4", bitrateKbps = 64
        )
        val high256 = ResolvedAudioStream(
            "https://example.com/high256", "ua", mimeType = "audio/webm", bitrateKbps = 256
        )

        assertEquals(
            "https://example.com/mp4128",
            AudioStreamResolver.orderCandidates(
                listOf(opus128, high256, low64, mp4128),
                AudioQuality.AUTO
            ).first().url
        )
        assertEquals(
            "https://example.com/high256",
            AudioStreamResolver.orderCandidates(
                listOf(opus128, high256, low64, mp4128),
                AudioQuality.HIGH
            ).first().url
        )
        assertEquals(
            "https://example.com/low64",
            AudioStreamResolver.orderCandidates(
                listOf(opus128, high256, low64, mp4128),
                AudioQuality.DATA_SAVER
            ).first().url
        )
    }
}
