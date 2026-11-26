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
    fun localCastMediaServerServesAuthorizedRangeAndRejectsBadToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempAudio = File.createTempFile("offgrid_cast_test_", ".mp3")
        val payload = ByteArray(1024) { (it % 251).toByte() }
        tempAudio.writeBytes(payload)

        val server = LocalCastMediaServer(context)
        try {
            val song = Song(
                id = 42L,
                title = "Midnight Echo",
                artist = "OffGrid Band",
                album = "Live Session",
                durationMs = 180_000L,
                path = tempAudio.absolutePath,
                contentUriString = ""
            )

            val urls = server.prepareMediaForSong(song, "127.0.0.1")
            assertNotNull(urls)
            assertEquals("audio/mpeg", urls!!.mimeType)
            assertEquals(1024L, urls.fileSizeBytes)

            val client = OkHttpClient()

            // 1. Full GET with valid session token
            client.newCall(Request.Builder().url(urls.streamUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("bytes", resp.header("Accept-Ranges"))
                assertArrayEquals(payload, resp.body?.bytes())
            }

            // 2. Partial Range request (206) for TV seek support
            val rangeReq = Request.Builder()
                .url(urls.streamUrl)
                .header("Range", "bytes=100-199")
                .get()
                .build()
            client.newCall(rangeReq).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 100-199/1024", resp.header("Content-Range"))
                assertArrayEquals(payload.copyOfRange(100, 200), resp.body?.bytes())
            }

            // 3. Invalid token must be rejected with 403
            val badTokenUrl = "http://127.0.0.1:${server.port}/stream/invalid_token/42.mp3"
            client.newCall(Request.Builder().url(badTokenUrl).get().build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }
        } finally {
            server.stop()
            tempAudio.delete()
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
    fun castDeviceSheetOpensHowShouldIConnectHelpGuide() {
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
                    onQuickPlayOnCast = {},
                    onSelectAudioDevice = {},
                    onLaunchSystemCast = {},
                    onOpenWifiSettings = {},
                    onOpenBluetoothSettings = {},
                    onDismiss = {}
                )
            }
        }

        // Verify discovered TV and "How should I connect?" button are visible
        composeTestRule.onNodeWithText("Cast to TV & Devices").assertExists()
        composeTestRule.onNodeWithText("Living Room Google TV").assertExists()
        composeTestRule.onNodeWithTag("btn_how_to_connect_help").assertExists()

        // Tap "How should I connect?" and verify step-by-step guide appears
        composeTestRule.onNodeWithTag("btn_how_to_connect_help").performClick()
        composeTestRule.onNodeWithText("How should I connect?").assertExists()
        composeTestRule.onNodeWithText("Chromecast, Google TV & Android TV").assertExists()
        composeTestRule.onNodeWithText("Samsung, LG, Sony, Roku & Smart TVs").assertExists()
        composeTestRule.onNodeWithText("Bluetooth TVs, Soundbars & Speakers").assertExists()
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
            manager.refreshNetworkState()
            manager.stopDiscovery()
            manager.disconnect()
            assertEquals(CastConnectionState.IDLE, manager.sessionState.value.connectionState)
        } finally {
            manager.release()
        }
    }
}
