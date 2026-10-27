package com.mentra.bluetoothsdk.controllers

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.mentra.bluetoothsdk.Bridge
import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.utils.ControllerTypes

/**
 * A ring that the user pairs with the phone in Android settings, and that then sends ordinary keys as a Bluetooth
 * keyboard (HID over GATT). Each supported ring has a profile that maps its keys to ring gestures.
 */
internal data class HidRingProfile(val namePrefix: String, val gestures: Map<Int, String>)

internal object HidRingProfiles {
    /** Recorded on hardware 2026-10-25: each gesture sends one key. */
    val INMO = HidRingProfile(
        namePrefix = "INMO Ring",
        gestures = mapOf(
            KeyEvent.KEYCODE_ENTER to "single_tap",
            KeyEvent.KEYCODE_DEL to "double_tap",
            KeyEvent.KEYCODE_HOME to "long_press",
            KeyEvent.KEYCODE_DPAD_RIGHT to "swipe_up",
            KeyEvent.KEYCODE_DPAD_LEFT to "swipe_down",
            // The Go button: the key stays down while the button is held.
            KeyEvent.KEYCODE_F8 to "go_press",
        ),
    )

    val ALL = listOf(INMO)

    fun forName(name: String?): HidRingProfile? =
        if (name == null) null else ALL.firstOrNull { name.startsWith(it.namePrefix) }
}

/**
 * Rings that pair with the phone as keyboards. The ring's keys reach the app through [RingKeyService], an
 * accessibility service, which hands them to the active ring here so that they work in the background and don't
 * also act on the phone (the INMO ring's long press sends Home).
 */
class HidRing : ControllerManager() {
    companion object {
        private const val TAG = "HidRing"

        /** The ring whose keys [RingKeyService] forwards; set while a ring is wanted. */
        @Volatile
        internal var active: HidRing? = null
    }

    private val context: Context = Bridge.getContext()
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The paired ring's Bluetooth name; null until connectById(). */
    @Volatile
    private var ringName: String? = null

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshConnection()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshConnection()
        override fun onInputDeviceChanged(deviceId: Int) = refreshConnection()
    }

    init {
        type = ControllerTypes.HID_RING
        hasMic = false
    }

    /** Returns true when the key came from this ring, so the service consumes it. */
    internal fun onKey(event: KeyEvent): Boolean {
        val name = ringName ?: return false
        if (event.device?.name?.startsWith(name) != true) return false
        val profile = HidRingProfiles.forName(name) ?: return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val gesture = profile.gestures[event.keyCode]
            if (gesture != null) {
                Bridge.sendTouchEvent(ControllerTypes.HID_RING, gesture, System.currentTimeMillis())
            } else {
                Bridge.log("$TAG: unmapped key ${KeyEvent.keyCodeToString(event.keyCode)} from $name")
            }
        }
        return true
    }

    /**
     * Rings with a known profile, paired or connected. Android can keep a ring's bond without its name (the INMO ring,
     * seen 2026-10-25), so the names of connected input devices count too.
     */
    private fun pairedRingNames(): List<String> {
        val names = linkedSetOf<String>()
        for (id in InputDevice.getDeviceIds()) {
            val name = InputDevice.getDevice(id)?.name ?: continue
            if (HidRingProfiles.forName(name) != null) names.add(name)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = device.name
                if (name != null && HidRingProfiles.forName(name) != null) names.add(name)
            }
        } catch (missingPermission: SecurityException) {
            Bridge.log("$TAG: Bluetooth permission missing: ${missingPermission.message}")
        }
        return names.toList()
    }

    private fun ringInputDevice(): InputDevice? {
        val name = ringName ?: return null
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.name.startsWith(name)) return device
        }
        return null
    }

    private fun refreshConnection() {
        val connected = ringInputDevice() != null
        Bridge.log("$TAG: ${ringName ?: "ring"} ${if (connected) "connected" else "not connected"}")
        // The name first: controllerFullyBooted turning true saves the paired controller (handleControllerReady), and
        // pairing and the reconnect after a restart both need the name in that save.
        if (connected) DeviceStore.apply("bluetooth", "controller_device_name", ringName ?: "")
        DeviceStore.apply("glasses", "controllerConnected", connected)
        // A keyboard ring has nothing to set up once Android has it connected.
        DeviceStore.apply("glasses", "controllerFullyBooted", connected)
    }

    // MARK: - ControllerManager overrides

    override fun findCompatibleDevices() {
        val names = pairedRingNames()
        Bridge.log("$TAG: paired rings: $names")
        for (name in names) Bridge.sendDiscoveredDevice(ControllerTypes.HID_RING, name)
    }

    override fun stopScan() {}

    override fun connectById(id: String) {
        val name = id.takeIf { it.isNotEmpty() } ?: pairedRingNames().singleOrNull()
        if (name == null) {
            Bridge.log("$TAG: no paired ring matches '$id'")
            return
        }
        ringName = name
        active = this
        inputManager.registerInputDeviceListener(inputListener, mainHandler)
        refreshConnection()
        if (!RingKeyService.isEnabled(context)) {
            Bridge.log("$TAG: ring key access is off; enable Mentra in Android Accessibility settings")
        }
    }

    override fun disconnect() {
        inputManager.unregisterInputDeviceListener(inputListener)
        if (active === this) active = null
        ringName = null
        DeviceStore.apply("glasses", "controllerConnected", false)
        DeviceStore.apply("glasses", "controllerFullyBooted", false)
    }

    override fun forget() = disconnect()

    override fun cleanup() = disconnect()

    override fun getConnectedBluetoothName(): String? = ringInputDevice()?.name

    override fun ping() {}

    override fun getBatteryStatus() {}

    // MARK: - No-op implementations (the ring has no display, camera, wifi or mic)

    override fun sendIncidentId(incidentId: String) {}
    override fun setMicEnabled(enabled: Boolean) {}
    override fun sortMicRanking(list: MutableList<String>): MutableList<String> = list
    override fun sendJson(jsonOriginal: Map<String, Any>, wakeUp: Boolean, requireAck: Boolean) {}
    override fun requestPhoto(request: PhotoRequest) {}
    override fun startVideoRecording(requestId: String, save: Boolean, sound: Boolean) {}
    override fun stopVideoRecording(requestId: String) {}
    override fun startStream(message: Map<String, Any>) {}
    override fun stopStream() {}
    override fun sendStreamKeepAlive(message: Map<String, Any>) {}
    override fun sendButtonPhotoSettings() {}
    override fun sendButtonVideoRecordingSettings() {}
    override fun sendButtonMaxRecordingTime() {}
    override fun setBrightness(level: Int, autoMode: Boolean) {}
    override fun clearDisplay() {}
    override fun sendTextWall(text: String) {}
    override fun sendDoubleTextWall(top: String, bottom: String) {}
    override fun displayBitmap(
        base64ImageData: String,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?,
    ): Boolean = false
    override fun showDashboard() {}
    override fun setDashboardPosition(height: Int, depth: Int) {}
    override fun setHeadUpAngle(angle: Int) {}
    override fun setSilentMode(enabled: Boolean) {}
    override fun exit() {}
    override fun sendShutdown() = disconnect()
    override fun sendReboot() {}
    override fun sendRgbLedControl(
        requestId: String,
        packageName: String?,
        action: String,
        color: String?,
        onDurationMs: Int,
        offDurationMs: Int,
        count: Int,
    ) {}
    override fun requestWifiScan() {}
    override fun sendWifiCredentials(ssid: String, password: String) {}
    override fun forgetWifiNetwork(ssid: String) {}
    override fun sendHotspotState(enabled: Boolean) {}
    override fun sendOtaStart(otaVersionUrl: String?) {}
    override fun sendUserEmailToGlasses(email: String) {}
    override fun queryGalleryStatus() {}
    override fun sendGalleryMode() {}
    override fun requestVersionInfo() {}
}
