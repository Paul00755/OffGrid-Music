package com.example.jam

import org.junit.Assert.assertEquals
import org.junit.Test

class JamInviteExtractionTest {

    @Test
    fun extractsCodeFromRawAndSpacedStrings() {
        assertEquals("23456789ABCDEFGH", extractJamInviteCode("23456789abcdefgh"))
        assertEquals("23456789ABCDEFGH", extractJamInviteCode("2345 6789 ABCD EFGH"))
    }

    @Test
    fun extractsCodeFromHttpsInviteUrl() {
        assertEquals(
            "23456789ABCDEFGH",
            extractJamInviteCode("https://offgrid-music.web.app/jam/23456789abcdefgh")
        )
        assertEquals(
            "23456789ABCDEFGH",
            extractJamInviteCode("https://offgrid-music.web.app/jam/23456789abcdefgh?ref=qr")
        )
    }

    @Test
    fun extractsCodeFromAndroidIntentUri() {
        assertEquals(
            "23456789ABCDEFGH",
            extractJamInviteCode(
                "intent://offgrid-music.web.app/jam/23456789abcdefgh#Intent;scheme=https;package=com.aistudio.offgridmusic.player;end"
            )
        )
    }

    @Test
    fun extractsCodeFromQueryParameter() {
        assertEquals(
            "23456789ABCDEFGH",
            extractJamInviteCode("https://offgrid-music.web.app/jam?code=23456789abcdefgh")
        )
    }
}
