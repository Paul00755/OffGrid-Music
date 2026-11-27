package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPreferencesRepository
import com.example.ui.theme.NowPlayingLayout
import com.example.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingWaveformDefaultsTest {

    private suspend fun awaitUiJob(job: Job) {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        var ticks = 0
        while (job.isActive && ticks < 200) {
            mainLooper.idle()
            delay(25)
            ticks++
        }
        mainLooper.idle()
        assertFalse("Timed out waiting for MusicViewModel coroutine job", job.isActive)
    }

    @Test
    fun testModernBentoDefaultsToWaveAndUserSelectedWaveformPersistsAcrossLayouts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[MusicViewModel::class.java]

        try {
            vm.preferences.clearAllForTesting()

            // Default un-overridden switch to Modern Bento sets WAVY (Wave), others set SNAKE
            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.MODERN_BENTO.id))
            assertEquals("WAVY", vm.preferences.waveformStyle.first())
            assertFalse(vm.preferences.isWaveformUserOverriddenSync())

            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.VINYL_TURNTABLE.id))
            assertEquals("SNAKE", vm.preferences.waveformStyle.first())

            // Once user explicitly picks a waveform, switching Now Playing layouts must preserve it
            awaitUiJob(vm.setWaveformStyle("BARS"))
            assertTrue(vm.preferences.isWaveformUserOverriddenSync())

            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.MODERN_BENTO.id))
            assertEquals("BARS", vm.preferences.waveformStyle.first())

            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.NEON_WAVEFORM.id))
            assertEquals("BARS", vm.preferences.waveformStyle.first())

            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.MINIMAL_GLASS.id))
            assertEquals("BARS", vm.preferences.waveformStyle.first())

            // If Sleek Line is currently being used, switching layouts updates to the layout-specific default
            awaitUiJob(vm.setWaveformStyle("SLEEK_LINE"))
            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.MODERN_BENTO.id))
            assertEquals("WAVY", vm.preferences.waveformStyle.first())

            awaitUiJob(vm.setWaveformStyle("SLEEK_LINE"))
            awaitUiJob(vm.setNowPlayingLayout(NowPlayingLayout.MINIMAL_GLASS.id))
            assertEquals("SNAKE", vm.preferences.waveformStyle.first())
        } finally {
            runCatching { UserPreferencesRepository.getInstance(app).clearAllForTesting() }
            store.clear()
        }
    }
}
