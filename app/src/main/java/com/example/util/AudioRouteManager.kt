package com.example.util

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioDeviceType {
    SPEAKER,
    HEADPHONES,
    BLUETOOTH,
    USB,
    UNKNOWN
}

data class AudioRouteInfo(
    val name: String,
    val type: AudioDeviceType,
    val icon: ImageVector,
    val deviceId: String? = null
)

data class AudioRouteDevice(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val isSelected: Boolean,
    val description: String,
    val icon: ImageVector,
    val rawDeviceInfo: AudioDeviceInfo? = null
)

class AudioRouteManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var a2dpProfile: BluetoothA2dp? = null
    private var headsetProfile: BluetoothHeadset? = null
    private var userSelectedRoute: AudioRouteDevice? = null
    var onDeviceSelected: ((AudioDeviceInfo?) -> Unit)? = null

    private val _currentRoute = MutableStateFlow(detectCurrentRoute())
    val currentRoute: StateFlow<AudioRouteInfo> = _currentRoute.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<AudioRouteDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioRouteDevice>> = _availableDevices.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val sel = userSelectedRoute
            if (sel != null && sel.type != AudioDeviceType.SPEAKER) {
                val removed = removedDevices?.any { it.id == sel.rawDeviceInfo?.id } ?: false
                if (removed) {
                    userSelectedRoute = null
                    onDeviceSelected?.invoke(null)
                }
            }
            updateRoute()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRoute()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProfile = proxy as? BluetoothHeadset
            }
            updateRoute()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = null
                BluetoothProfile.HEADSET -> headsetProfile = null
            }
            updateRoute()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            } catch (_: Exception) {}
        }

        // Register Bluetooth profile proxy
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        } catch (_: Exception) {}

        // Register broadcast receiver for audio/bluetooth changes
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(bluetoothReceiver, filter)
            }
        } catch (_: Exception) {}

        updateRoute()
    }

    fun updateRoute() {
        val detected = detectCurrentRoute()
        _currentRoute.value = detected
        _availableDevices.value = detectAvailableDevices(detected)
    }

    private fun isGenericName(name: String): Boolean {
        val phoneModel = Build.MODEL.trim()
        val phoneDevice = Build.DEVICE.trim()
        val lower = name.lowercase().trim()
        if (lower.isBlank()) return true
        if (lower == "phone" || lower == "speaker" || lower == "built-in speaker" ||
            lower == "phone speaker" || lower == "earpiece" || lower == "bluetooth headset" ||
            lower == "bluetooth audio" || lower == "bluetooth a2dp" || lower == "bluetooth" ||
            lower == "audio output" || lower == "default" || lower == "unknown" ||
            lower.startsWith("device ") || lower.startsWith("device_") || lower.startsWith("audio ") ||
            lower.startsWith("output ") || lower.startsWith("bt ") || lower.startsWith("bt_") ||
            lower.matches(Regex("^(device|audio|output|bt|endpoint)[\\s_-]*\\d+$", RegexOption.IGNORE_CASE)) ||
            lower.matches(Regex("^\\d+$"))
        ) return true
        return name.equals(phoneModel, ignoreCase = true) || name.equals(phoneDevice, ignoreCase = true)
    }

    private fun getBluetoothDeviceName(device: AudioDeviceInfo?): String {
        // 1. Try AudioDeviceInfo product name if valid and specific
        if (device != null) {
            val prod = device.productName?.toString()?.trim() ?: ""
            if (prod.isNotBlank() && !isGenericName(prod)) {
                return prod
            }
        }

        // 2. Try connected A2DP profile devices
        try {
            val connectedA2dp = a2dpProfile?.connectedDevices
            val activeA2dp = connectedA2dp?.firstOrNull()
            if (activeA2dp != null) {
                val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { activeA2dp.alias } catch (_: SecurityException) { null }
                } else null
                val name = alias ?: try { activeA2dp.name } catch (_: SecurityException) { null }
                if (!name.isNullOrBlank() && !isGenericName(name)) {
                    return name
                }
            }
        } catch (_: Exception) {}

        // 3. Try connected Headset profile devices
        try {
            val connectedHeadset = headsetProfile?.connectedDevices
            val activeHeadset = connectedHeadset?.firstOrNull()
            if (activeHeadset != null) {
                val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { activeHeadset.alias } catch (_: SecurityException) { null }
                } else null
                val name = alias ?: try { activeHeadset.name } catch (_: SecurityException) { null }
                if (!name.isNullOrBlank() && !isGenericName(name)) {
                    return name
                }
            }
        } catch (_: Exception) {}

        // 4. Try bonded devices if address matches or active
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                val bonded = try { adapter.bondedDevices } catch (_: SecurityException) { null }
                if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val devAddr = device.address
                    val match = bonded?.find { it.address.equals(devAddr, ignoreCase = true) }
                    if (match != null) {
                        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try { match.alias } catch (_: SecurityException) { null }
                        } else null
                        val name = alias ?: try { match.name } catch (_: SecurityException) { null }
                        if (!name.isNullOrBlank() && !isGenericName(name)) return name
                    }
                }
            }
        } catch (_: Exception) {}

        if (device?.productName?.isNotBlank() == true && !isGenericName(device.productName.toString())) {
            return device.productName.toString()
        }
        return "Bluetooth Audio"
    }

    private fun detectCurrentRoute(): AudioRouteInfo {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (_: Exception) { emptyArray() }
        } else emptyArray()

        // Respect explicit user selection if still connected/available
        userSelectedRoute?.let { sel ->
            if (sel.type == AudioDeviceType.SPEAKER) {
                return AudioRouteInfo(
                    name = sel.name,
                    type = AudioDeviceType.SPEAKER,
                    icon = sel.icon,
                    deviceId = sel.rawDeviceInfo?.id?.toString()
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && sel.rawDeviceInfo != null) {
                val isStillConnected = devices.any { it.id == sel.rawDeviceInfo.id }
                if (isStillConnected) {
                    return AudioRouteInfo(
                        name = sel.name,
                        type = sel.type,
                        icon = sel.icon,
                        deviceId = sel.rawDeviceInfo.id.toString()
                    )
                } else {
                    userSelectedRoute = null
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 1. Check for connected Bluetooth media devices first (Exclude SCO telephony)
            for (device in devices) {
                val isBleHeadset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                            device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                } else false

                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    isBleHeadset
                ) {
                    val name = getBluetoothDeviceName(device)
                    return AudioRouteInfo(
                        name = name,
                        type = AudioDeviceType.BLUETOOTH,
                        icon = Icons.Default.Bluetooth,
                        deviceId = device.id.toString()
                    )
                }
            }

            // 2. Check for USB Audio
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE
                ) {
                    val rawName = device.productName?.toString()?.trim()
                    val name = if (!rawName.isNullOrBlank() && !isGenericName(rawName)) rawName else "USB Audio"
                    return AudioRouteInfo(
                        name = name,
                        type = AudioDeviceType.USB,
                        icon = Icons.Default.Usb,
                        deviceId = device.id.toString()
                    )
                }
            }

            // 3. Check for wired headphones
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                ) {
                    val rawName = device.productName?.toString()?.trim()
                    val name = if (!rawName.isNullOrBlank() && !isGenericName(rawName)) rawName else "Wired Headphones"
                    return AudioRouteInfo(
                        name = name,
                        type = AudioDeviceType.HEADPHONES,
                        icon = Icons.Default.Headphones,
                        deviceId = device.id.toString()
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothA2dpOn) {
                return AudioRouteInfo(
                    name = getBluetoothDeviceName(null),
                    type = AudioDeviceType.BLUETOOTH,
                    icon = Icons.Default.Bluetooth
                )
            } else if (@Suppress("DEPRECATION") audioManager.isWiredHeadsetOn) {
                return AudioRouteInfo(
                    name = "Headphones",
                    type = AudioDeviceType.HEADPHONES,
                    icon = Icons.Default.Headphones
                )
            }
        }

        return AudioRouteInfo(
            name = "Phone speaker",
            type = AudioDeviceType.SPEAKER,
            icon = Icons.Default.VolumeUp
        )
    }

    private fun detectAvailableDevices(current: AudioRouteInfo): List<AudioRouteDevice> {
        val list = mutableListOf<AudioRouteDevice>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (_: Exception) { emptyArray() }

            // Deduplicate Bluetooth devices by physical device (address or name)
            // Exclude SCO telephony modems to avoid duplicate or phantom Bluetooth devices
            val btDeviceMap = mutableMapOf<String, Pair<AudioDeviceInfo, String>>() // Key -> (DeviceInfo, resolvedName)

            for (dev in devices) {
                val isBleHeadset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                            dev.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                } else false

                if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    dev.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    isBleHeadset
                ) {
                    val resolvedName = getBluetoothDeviceName(dev)
                    val devAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { dev.address.orEmpty() } catch (_: Exception) { "" }
                    } else ""
                    val dedupeKey = if (devAddress.isNotBlank()) devAddress else resolvedName.lowercase().trim()

                    val currentEntry = btDeviceMap[dedupeKey]
                    if (currentEntry == null) {
                        btDeviceMap[dedupeKey] = Pair(dev, resolvedName)
                    } else {
                        val existingScore = getDeviceMediaScore(currentEntry.first.type)
                        val newScore = getDeviceMediaScore(dev.type)
                        if (newScore > existingScore) {
                            btDeviceMap[dedupeKey] = Pair(dev, resolvedName)
                        }
                    }
                }
            }

            // Consolidate if multiple entries resolved to the same device name
            val consolidatedBtMap = mutableMapOf<String, Pair<AudioDeviceInfo, String>>()
            for ((_, pair) in btDeviceMap) {
                val (dev, name) = pair
                val existing = consolidatedBtMap[name]
                if (existing == null) {
                    consolidatedBtMap[name] = pair
                } else {
                    if (getDeviceMediaScore(dev.type) > getDeviceMediaScore(existing.first.type)) {
                        consolidatedBtMap[name] = pair
                    }
                }
            }

            // Add deduplicated Bluetooth devices
            for ((name, pair) in consolidatedBtMap) {
                val (dev, _) = pair
                val isSel = if (userSelectedRoute != null) {
                    userSelectedRoute?.id == "bt_${name.hashCode()}" || userSelectedRoute?.rawDeviceInfo?.id == dev.id
                } else {
                    current.type == AudioDeviceType.BLUETOOTH &&
                            (current.deviceId == dev.id.toString() || current.name.equals(name, ignoreCase = true))
                }
                list.add(
                    AudioRouteDevice(
                        id = "bt_${name.hashCode()}",
                        name = name,
                        type = AudioDeviceType.BLUETOOTH,
                        isSelected = isSel,
                        description = "Bluetooth Audio Device",
                        icon = Icons.Default.Bluetooth,
                        rawDeviceInfo = dev
                    )
                )
            }

            // Add USB Devices
            val usbDeviceMap = mutableMapOf<String, AudioDeviceInfo>()
            for (dev in devices) {
                if (dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_USB_DEVICE
                ) {
                    val rawName = dev.productName?.toString()?.trim()
                    val name = if (!rawName.isNullOrBlank() && !isGenericName(rawName)) rawName else "USB Audio"
                    usbDeviceMap[name] = dev
                }
            }
            for ((name, dev) in usbDeviceMap) {
                val isSel = if (userSelectedRoute != null) {
                    userSelectedRoute?.id == "usb_${dev.id}" || userSelectedRoute?.rawDeviceInfo?.id == dev.id
                } else {
                    current.type == AudioDeviceType.USB
                }
                list.add(
                    AudioRouteDevice(
                        id = "usb_${dev.id}",
                        name = name,
                        type = AudioDeviceType.USB,
                        isSelected = isSel,
                        description = "USB Audio Output",
                        icon = Icons.Default.Usb,
                        rawDeviceInfo = dev
                    )
                )
            }

            // Add Wired Headphones
            val hasWired = devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            if (hasWired) {
                val wiredDev = devices.find {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
                val rawName = wiredDev?.productName?.toString()?.trim()
                val name = if (!rawName.isNullOrBlank() && !isGenericName(rawName)) rawName else "Wired Headphones"
                val isSel = if (userSelectedRoute != null) {
                    userSelectedRoute?.id == "wired_headset" || userSelectedRoute?.rawDeviceInfo?.id == wiredDev?.id
                } else {
                    current.type == AudioDeviceType.HEADPHONES
                }
                list.add(
                    AudioRouteDevice(
                        id = "wired_headset",
                        name = name,
                        type = AudioDeviceType.HEADPHONES,
                        isSelected = isSel,
                        description = "Connected 3.5mm Headset",
                        icon = Icons.Default.Headphones,
                        rawDeviceInfo = wiredDev
                    )
                )
            }

            // Built-in Speaker
            val speakerDev = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val isSpeakerSel = if (userSelectedRoute != null) {
                userSelectedRoute?.type == AudioDeviceType.SPEAKER
            } else {
                current.type == AudioDeviceType.SPEAKER
            }
            list.add(
                AudioRouteDevice(
                    id = "speaker",
                    name = "Phone Speaker",
                    type = AudioDeviceType.SPEAKER,
                    isSelected = isSpeakerSel,
                    description = "This Device",
                    icon = Icons.Default.VolumeUp,
                    rawDeviceInfo = speakerDev
                )
            )
        } else {
            // Fallback for older SDK
            if (current.type == AudioDeviceType.BLUETOOTH) {
                list.add(
                    AudioRouteDevice(
                        id = "bt_default",
                        name = current.name,
                        type = AudioDeviceType.BLUETOOTH,
                        isSelected = userSelectedRoute?.type == AudioDeviceType.BLUETOOTH || userSelectedRoute == null,
                        description = "Bluetooth Audio Device",
                        icon = Icons.Default.Bluetooth
                    )
                )
            }
            list.add(
                AudioRouteDevice(
                    id = "speaker",
                    name = "Phone Speaker",
                    type = AudioDeviceType.SPEAKER,
                    isSelected = userSelectedRoute?.type == AudioDeviceType.SPEAKER || (userSelectedRoute == null && current.type == AudioDeviceType.SPEAKER),
                    description = "This Device",
                    icon = Icons.Default.VolumeUp
                )
            )
        }

        return list.distinctBy { it.id }
    }

    private fun getDeviceMediaScore(type: Int): Int {
        return when {
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 10
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> 9
            type == AudioDeviceInfo.TYPE_HEARING_AID -> 8
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
            else -> 1
        }
    }

    fun resolveDeviceInfo(routeDevice: AudioRouteDevice): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (routeDevice.rawDeviceInfo != null) {
                return routeDevice.rawDeviceInfo
            }
            val outputs: Array<AudioDeviceInfo> = try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (_: Exception) { emptyArray() }
            return when (routeDevice.type) {
                AudioDeviceType.SPEAKER -> {
                    outputs.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: outputs.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                AudioDeviceType.BLUETOOTH -> {
                    outputs.find {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_HEARING_AID
                    }
                }
                AudioDeviceType.HEADPHONES -> {
                    outputs.find {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    }
                }
                AudioDeviceType.USB -> {
                    outputs.find {
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    }
                }
                else -> null
            }
        }
        return null
    }

    fun selectRoute(routeDevice: AudioRouteDevice) {
        userSelectedRoute = routeDevice
        try {
            val targetDeviceInfo: AudioDeviceInfo? = resolveDeviceInfo(routeDevice)

            if (routeDevice.type == AudioDeviceType.SPEAKER) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val speakerComm = audioManager.availableCommunicationDevices.find {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (speakerComm != null) {
                            audioManager.setCommunicationDevice(speakerComm)
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    } catch (_: Exception) {}
                }
                @Suppress("DEPRECATION")
                try {
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) {}
                onDeviceSelected?.invoke(targetDeviceInfo)
            } else if (routeDevice.type == AudioDeviceType.BLUETOOTH) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val matchingComm = if (routeDevice.rawDeviceInfo != null) {
                            audioManager.availableCommunicationDevices.find { it.id == routeDevice.rawDeviceInfo.id }
                        } else null

                        val btComm = matchingComm ?: audioManager.availableCommunicationDevices.find {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                        }
                        if (btComm != null) {
                            audioManager.setCommunicationDevice(btComm)
                        }
                    } catch (_: Exception) {}
                }
                @Suppress("DEPRECATION")
                try {
                    audioManager.isSpeakerphoneOn = false
                } catch (_: Exception) {}
                onDeviceSelected?.invoke(targetDeviceInfo)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (routeDevice.rawDeviceInfo != null) {
                            val matching = audioManager.availableCommunicationDevices.find { it.id == routeDevice.rawDeviceInfo.id }
                            if (matching != null) {
                                audioManager.setCommunicationDevice(matching)
                            } else {
                                audioManager.clearCommunicationDevice()
                            }
                        } else {
                            audioManager.clearCommunicationDevice()
                        }
                    } catch (_: Exception) {}
                }
                @Suppress("DEPRECATION")
                try {
                    audioManager.isSpeakerphoneOn = false
                } catch (_: Exception) {}
                onDeviceSelected?.invoke(targetDeviceInfo)
            }
        } catch (_: Exception) {}

        _currentRoute.value = AudioRouteInfo(
            name = routeDevice.name,
            type = routeDevice.type,
            icon = routeDevice.icon,
            deviceId = routeDevice.rawDeviceInfo?.id?.toString()
        )
        _availableDevices.value = detectAvailableDevices(_currentRoute.value)
    }

    fun launchSystemOutputSwitcher(context: Context) {
        var launched = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                    putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                launched = true
            } catch (_: Exception) {
                try {
                    val intent = Intent("android.settings.panel.action.MEDIA_OUTPUT").apply {
                        putExtra("android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    launched = true
                } catch (_: Exception) {}
            }
        }
        if (!launched) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
        }
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            a2dpProfile?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProfile?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) {}
    }
}