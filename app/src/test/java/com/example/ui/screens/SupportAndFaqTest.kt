package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportAndFaqTest {

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
        assertTrue(combinedText.contains("backed-up"))
        assertTrue(combinedText.contains("api key"))
        assertTrue(combinedText.contains("surge"))
    }
}
