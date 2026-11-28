package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import com.example.R
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportAndFaqTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun supportDeveloperIsLastSettingsSection() {
        assertEquals(
            SettingsSection.SUPPORT_DEVELOPER,
            SETTINGS_CATEGORY_CARD_ITEMS.last().section
        )
        assertEquals(
            "Support the Developer",
            SETTINGS_CATEGORY_CARD_ITEMS.last().title
        )
    }

    @Test
    fun creatorSupportUrlAndSupportEmailMatchExpectedValues() {
        assertEquals("https://buymeacoffee.com/paulrajmes9", BUY_ME_A_COFFEE_URL)
        assertTrue(BUY_ME_A_COFFEE_URL.startsWith("https://"))
        assertEquals("offgridmusicsupport@gmail.com", OFFGRID_SUPPORT_EMAIL)
    }

    @Test
    fun faqEntriesContainAllCoreQuestionsWithUniqueIdsAndAnswers() {
        val ids = OFFGRID_FAQ_ENTRIES.map { it.id }
        assertEquals("FAQ IDs must be unique", ids.size, ids.toSet().size)
        assertTrue("Expected at least 5 FAQ entries", OFFGRID_FAQ_ENTRIES.size >= 5)

        OFFGRID_FAQ_ENTRIES.forEach { entry ->
            assertTrue("Question should not be blank", entry.question.isNotBlank())
            assertTrue("Answer should not be blank", entry.answer.isNotBlank())
            assertTrue("Category should not be blank", entry.category.isNotBlank())
        }

        val combinedText = OFFGRID_FAQ_ENTRIES.joinToString("\n") { "${it.question} ${it.answer}" }.lowercase()
        assertTrue(combinedText.contains("play store"))
        assertTrue(combinedText.contains("google sign-in"))
        assertTrue(combinedText.contains("email & password"))
        assertTrue(combinedText.contains("what data is backed up"))
        assertTrue(combinedText.contains("backed-up"))
        assertTrue(combinedText.contains("off-grid jam"))
        assertTrue(combinedText.contains("smart shuffle"))
        assertTrue(combinedText.contains("api key"))
        assertTrue(combinedText.contains("surge"))
    }

    @Test
    fun apkInstallAndPlayProtectSafetyAreTopPriorityWithBothScreenshots() {
        assertEquals("download_and_install_apk", OFFGRID_FAQ_ENTRIES[0].id)
        assertEquals("browser_harmful_warning_safe", OFFGRID_FAQ_ENTRIES[1].id)

        val installEntry = OFFGRID_FAQ_ENTRIES[0]
        val installText = "${installEntry.question}\n${installEntry.answer}".lowercase()
        assertTrue(installText.contains("install unknown apps"))
        assertTrue(installText.contains("allow from this source"))
        assertTrue(installText.contains("special app access"))

        val warningEntry = OFFGRID_FAQ_ENTRIES[1]
        assertEquals(2, warningEntry.images.size)
        assertEquals(R.drawable.faq_browser_harmful_warning, warningEntry.images[0].drawableResId)
        assertEquals(R.drawable.faq_play_protect_safe, warningEntry.images[1].drawableResId)
        assertTrue(warningEntry.answer.contains("This app looks safe"))
        assertTrue(warningEntry.answer.contains("File might be harmful"))

        composeTestRule.setContent {
            MyApplicationTheme {
                FaqScreen(onBack = {})
            }
        }

        // First priority APK download guide is expanded upfront
        composeTestRule.onNodeWithText(installEntry.question).assertIsDisplayed()
        // Collapse #1 so #2 + its screenshots sit cleanly in the test viewport, then expand #2
        composeTestRule.onNodeWithTag("faq_item_0").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("faq_list").performScrollToKey("browser_harmful_warning_safe")
        composeTestRule.onNodeWithTag("faq_item_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("faq_images_browser_harmful_warning_safe", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("faq_image_browser_harmful_warning_safe_0", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("faq_image_browser_harmful_warning_safe_1", useUnmergedTree = true).assertIsDisplayed()
    }
}
