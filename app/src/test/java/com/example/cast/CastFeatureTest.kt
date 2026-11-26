package com.example.cast

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Song
import com.example.ui.components.CastDeviceSheet
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AudioDeviceType
import com.example.util.AudioRouteInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CastFeatureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun localCastMediaServerServesAudioRangeLyricsVttAndCorsPreflight() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempAudio1 = File.createTempFile("offgrid_cast_test_1_", ".mp3")
        val tempAudio2 = File.createTempFile("offgrid_cast_test_2_", ".mp3")
        val payload1 = ByteArray(1024) { (it % 251).toByte() }
        val payload2 = ByteArray(512) { ((it * 3) % 239).toByte() }
        tempAudio1.writeBytes(payload1)
        tempAudio2.writeBytes(payload2)

        val server = LocalCastMediaServer(context)
        try {
            val song1 = Song(
                id = 42L,
                title = "Midnight Echo",
                artist = "OffGrid Band",
                album = "Live Session",
                durationMs = 180_000L,
                path = tempAudio1.absolutePath,
                contentUriString = ""
            )
            val song2 = Song(
                id = 43L,
                title = "Next Horizon",
                artist = "OffGrid Band",
                album = "Live Session",
                durationMs = 200_000L,
                path = tempAudio2.absolutePath,
                contentUriString = ""
            )

            val vtt = LocalCastMediaServer.buildWebVttFromLyrics(
                syncedLines = listOf(
                    1_500L to "First line of lyrics",
                    5_250L to "Second line of lyrics"
                ),
                songDurationMs = song1.durationMs,
                syncOffsetMs = 200L
            )
            assertNotNull(vtt)
            assertTrue(vtt!!.startsWith("WEBVTT"))
            assertTrue(vtt.contains("00:00:01.700 --> 00:00:05.390"))
            assertTrue(vtt.contains("First line of lyrics"))

            val urls1 = server.prepareMediaForSong(song1, "127.0.0.1", vtt)
            val urls2 = server.prepareMediaForSong(song2, "127.0.0.1", null)
            assertNotNull(urls1)
            assertNotNull(urls2)
            assertEquals("audio/mpeg", urls1!!.mimeType)
            assertEquals(1024L, urls1.fileSizeBytes)
            assertTrue(urls1.hasSyncedLyrics)
            assertNotNull(urls1.lyricsVttUrl)

            val client = OkHttpClient()

            // 1. Full GET for song 1 still works even after pre-queuing song 2
            client.newCall(Request.Builder().url(urls1.streamUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("bytes", resp.header("Accept-Ranges"))
                assertEquals("*", resp.header("Access-Control-Allow-Origin"))
                assertArrayEquals(payload1, resp.body?.bytes())
            }

            // 2. Pre-queued song 2 is also authorized simultaneously
            client.newCall(Request.Builder().url(urls2!!.streamUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertArrayEquals(payload2, resp.body?.bytes())
            }

            // 3. Partial Range request (206) for TV seek support
            val rangeReq = Request.Builder()
                .url(urls1.streamUrl)
                .header("Range", "bytes=100-199")
                .get()
                .build()
            client.newCall(rangeReq).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 100-199/1024", resp.header("Content-Range"))
                assertArrayEquals(payload1.copyOfRange(100, 200), resp.body?.bytes())
            }

            // 4. WebVTT lyrics GET + OPTIONS CORS preflight
            client.newCall(Request.Builder().url(urls1.lyricsVttUrl!!).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("*", resp.header("Access-Control-Allow-Origin"))
                assertTrue(resp.header("Content-Type").orEmpty().contains("text/vtt"))
                assertTrue(resp.body?.string().orEmpty().contains("First line of lyrics"))
            }
            client.newCall(Request.Builder().url(urls1.lyricsVttUrl!!).method("OPTIONS", null).build()).execute().use { resp ->
                assertEquals(204, resp.code)
                assertEquals("*", resp.header("Access-Control-Allow-Origin"))
            }

            // 5. Invalid token must be rejected with 403
            val badTokenUrl = "http://127.0.0.1:${server.port}/stream/invalid_token/42.mp3"
            client.newCall(Request.Builder().url(badTokenUrl).get().build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }
        } finally {
            server.stop()
            tempAudio1.delete()
            tempAudio2.delete()
        }
    }

    @Test
    fun dlnaEngineParsesSmartTvXmlAndUpnpTime() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = DlnaCastEngine(context)
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Living Room Samsung QLED</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>QN65Q80</modelName>
                <UDN>uuid:12345678-abcd-ef00-1234-56789abcdef0</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/upnp/control/RenderingControl1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val device = engine.parseDeviceDescriptionXml("http://192.168.1.50:9197/dmr", xml)
        assertNotNull(device)
        assertEquals("Living Room Samsung QLED", device!!.name)
        assertEquals(CastProtocol.DLNA_UPNP, device.protocol)
        assertEquals(CastDeviceKind.TV, device.kind)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", device.dlnaAvTransportUrl)
        assertEquals("http://192.168.1.50:9197/upnp/control/RenderingControl1", device.dlnaRenderingControlUrl)

        assertEquals("01:02:03", DlnaCastEngine.formatUpnpTime(3_723_000L))
        assertEquals(3_723_000L, DlnaCastEngine.parseUpnpTimeMs("01:02:03"))
    }

    @Test
    fun castDeviceSheetOpensSimplifiedHowItWorksGuideAndConnects() {
        val sampleTv = CastDevice(
            id = "gcast_living_room",
            name = "Living Room Google TV",
            subtitle = "Chromecast / Google TV",
            protocol = CastProtocol.GOOGLE_CAST,
            kind = CastDeviceKind.TV,
            mediaRouteId = "route_1"
        )
        val state = CastSessionState(
            discoveredDevices = listOf(sampleTv),
            isWifiConnected = true,
            localIpAddress = "192.168.1.25"
        )

        var selectedDevice: CastDevice? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                CastDeviceSheet(
                    castState = state,
                    currentSong = null,
                    audioRouteInfo = AudioRouteInfo("Phone speaker", AudioDeviceType.SPEAKER, Icons.AutoMirrored.Filled.VolumeUp),
                    availableAudioDevices = emptyList(),
                    onStartScan = {},
                    onStopScan = {},
                    onSelectCastDevice = { selectedDevice = it },
                    onDisconnectCast = {},
                    onToggleRemotePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeekRemote = {},
                    onSetRemoteVolume = {},
                    onToggleTvLyrics = {},
                    onQuickPlayOnCast = {},
                    onSelectAudioDevice = {},
                    onLaunchSystemCast = {},
                    onOpenWifiSettings = {},
                    onOpenBluetoothSettings = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Cast to TV & Devices").assertExists()
        composeTestRule.onNodeWithText("Living Room Google TV").assertExists()
        composeTestRule.onNodeWithTag("btn_how_to_connect_help").assertExists()

        // Tap "How it works" and verify simple 3-step guide renders
        composeTestRule.onNodeWithTag("btn_how_to_connect_help").performClick()
        composeTestRule.onNodeWithText("How should I connect?").assertExists()
        composeTestRule.onNodeWithText("Join the same Wi-Fi").assertExists()
        composeTestRule.onNodeWithText("Tap your TV in the list").assertExists()
        composeTestRule.onNodeWithText("Control from anywhere").assertExists()
        composeTestRule.onNodeWithTag("btn_cast_guide_rescan").assertExists()

        // Go back to Devices and verify tapping TV triggers connection callback
        composeTestRule.onNodeWithTag("btn_cast_guide_back").performClick()
        composeTestRule.onNodeWithTag("cast_device_gcast_living_room").performClick()
        assertTrue(selectedDevice?.id == sampleTv.id)
    }

    @Test
    fun castManagerIdleLifecycleAndReleaseAreSafeWithoutPlayServices() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = CastManager(context)
        try {
            assertEquals(CastConnectionState.IDLE, manager.sessionState.value.connectionState)
            assertEquals(false, manager.isCastingRemote)
            assertEquals(false, manager.isCastingOrConnecting)
            manager.refreshNetworkState()
            manager.stopDiscovery()
            manager.setTvLyricsEnabled(false)
            manager.adjustRemoteVolume(0.05f)
            manager.disconnect()
            assertEquals(CastConnectionState.IDLE, manager.sessionState.value.connectionState)
        } finally {
            manager.release()
        }
    }
}
