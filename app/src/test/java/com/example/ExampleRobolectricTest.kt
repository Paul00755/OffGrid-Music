package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.widget.WidgetUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OffGrid Music", appName)
  }

  @Test
  fun `adaptive lyrics layout scales surrounding lines with vertical height and always shows current line`() {
    // Shrunk ultra-compact height (< 112dp): only current active line is shown clearly
    val shrunk = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 280,
      heightDp = 100,
      availablePrevLines = 6,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(0, shrunk.prevCount)
    assertEquals(0, shrunk.nextCount)
    assertTrue(shrunk.currTextSizeSp >= 14f)

    // Compact height (140dp): 1 previous + current + 1 upcoming
    val compact = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 300,
      heightDp = 140,
      availablePrevLines = 6,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(1, compact.prevCount)
    assertEquals(1, compact.nextCount)

    // Medium/Standard height (175dp): 2 previous + current + 2 upcoming
    val standard = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 320,
      heightDp = 175,
      availablePrevLines = 6,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(2, standard.prevCount)
    assertEquals(2, standard.nextCount)

    // Expanded tall height (275dp): 4 previous + current + 4 upcoming
    val tall = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 340,
      heightDp = 275,
      availablePrevLines = 6,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(4, tall.prevCount)
    assertEquals(4, tall.nextCount)

    // Extra-expanded vertical height (360dp): 6 previous + current + 6 upcoming
    val extraExpanded = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 360,
      heightDp = 360,
      availablePrevLines = 6,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(6, extraExpanded.prevCount)
    assertEquals(6, extraExpanded.nextCount)
    assertTrue(extraExpanded.currTextSizeSp > standard.currTextSizeSp)
  }

  @Test
  fun `adaptive lyrics layout rebalances unused previous slots to upcoming lines at start of song`() {
    // At the very first lyric line (0 previous lines available, 6 upcoming available) in a 230dp widget (target 3+3)
    val startOfSong = WidgetUpdateManager.computeAdaptiveLyricsLayout(
      widthDp = 320,
      heightDp = 230,
      availablePrevLines = 0,
      availableNextLines = 6,
      density = 2.75f
    )
    assertEquals(0, startOfSong.prevCount)
    assertEquals(6, startOfSong.nextCount)
  }
}

