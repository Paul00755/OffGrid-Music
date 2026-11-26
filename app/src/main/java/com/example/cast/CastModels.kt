package com.example.cast

import com.example.data.model.Song

enum class CastProtocol(val label: String) {
    GOOGLE_CAST("Google Cast"),
    DLNA_UPNP("DLNA Smart TV")
}

enum class CastDeviceKind {
    TV,
    SPEAKER,
    DISPLAY,
    GENERIC
}

data class CastDevice(
    val id: String,
    val name: String,
    val subtitle: String,
    val protocol: CastProtocol,
    val kind: CastDeviceKind = CastDeviceKind.TV,
    val mediaRouteId: String? = null,
    val dlnaAvTransportUrl: String? = null,
    val dlnaRenderingControlUrl: String? = null,
    val dlnaServiceType: String = "urn:schemas-upnp-org:service:AVTransport:1"
)

enum class CastConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class CastSessionState(
    val connectionState: CastConnectionState = CastConnectionState.IDLE,
    val connectedDevice: CastDevice? = null,
    val discoveredDevices: List<CastDevice> = emptyList(),
    val isScanning: Boolean = false,
    val isRemotePlaying: Boolean = false,
    val remotePositionMs: Long = 0L,
    val remoteDurationMs: Long = 0L,
    val remoteVolume: Float = 0.7f,
    val castingSong: Song? = null,
    val tvLyricsEnabled: Boolean = true,
    val hasSyncedLyricsOnTv: Boolean = false,
    val isWifiConnected: Boolean = false,
    val localIpAddress: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = connectionState == CastConnectionState.CONNECTED && connectedDevice != null

    val isConnecting: Boolean
        get() = connectionState == CastConnectionState.CONNECTING

    val isActiveSession: Boolean
        get() = isConnected || isConnecting
}
