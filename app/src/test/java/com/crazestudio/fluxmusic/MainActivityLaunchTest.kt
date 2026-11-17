package com.crazestudio.fluxmusic

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import com.crazestudio.fluxmusic.playback.MusicService
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLaunchTest {

    @get:Rule(order = 0)
    val serviceRule = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val serviceController = Robolectric.buildService(MusicService::class.java).create()
            val service = serviceController.get()
            val binder = service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
                ComponentName(app, MusicService::class.java),
                binder
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndNavigatesAllTabsWithoutCrashing() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FluxMusic").assertIsDisplayed()
        composeRule.onNodeWithTag("home_featured_banner").assertIsDisplayed()

        composeRule.onNodeWithText("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_input").assertIsDisplayed()

        composeRule.onNodeWithText("Library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_liked_card").assertIsDisplayed()

        composeRule.onNodeWithText("Playlists").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_playlist_button").assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_offline_switch").assertIsDisplayed()
    }
}
