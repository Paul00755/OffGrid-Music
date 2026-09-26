package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.NavTab
import com.example.ui.viewmodel.MusicViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivitySettingsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testNavigateToSettings() {
        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val vm = ViewModelProvider(activity)[MusicViewModel::class.java]
            vm.completeOnboarding()
            vm.openSettings("ALL")
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val vm = ViewModelProvider(activity)[MusicViewModel::class.java]
            vm.openSettings("AUDIO")
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val vm = ViewModelProvider(activity)[MusicViewModel::class.java]
            vm.openSettings("CACHE")
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val vm = ViewModelProvider(activity)[MusicViewModel::class.java]
            vm.openSettings("UI")
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            val activity = composeTestRule.activity
            val vm = ViewModelProvider(activity)[MusicViewModel::class.java]
            vm.openSettings(null)
        }
        composeTestRule.waitForIdle()
    }
}
