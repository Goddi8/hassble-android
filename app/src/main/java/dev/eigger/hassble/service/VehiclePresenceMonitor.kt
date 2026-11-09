package dev.eigger.hassble.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.eigger.hassble.config.VehiclePresenceConfig

/** Detects a configured vehicle from its active hands-free/media Bluetooth profile or Wi-Fi network. */
internal class VehiclePresenceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val profiles = mutableMapOf<Int, BluetoothProfile>()

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            synchronized(profiles) { profiles[profile] = proxy }
        }

        override fun onServiceDisconnected(profile: Int) {
            synchronized(profiles) { profiles.remove(profile) }
        }
    }

    init {
        if (hasBluetoothConnectPermission()) {
            // Keep the nullable adapter check local to the receiver used below.
            adapter?.let { bluetoothAdapter ->
                // Android reports connected car hands-free and media devices through these profiles.
                listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).forEach { profile ->
                    runCatching { bluetoothAdapter.getProfileProxy(appContext, listener, profile) }
                }
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothConnected(expectedMac: String?): Boolean {
        if (expectedMac.isNullOrBlank()) return false
        if (VehicleCompanionManager.isPresent(appContext, expectedMac)) return true
        if (!hasBluetoothConnectPermission()) return false
        return synchronized(profiles) {
            profiles.values.any { profile ->
                runCatching {
                    profile.connectedDevices.any { it.address.equals(expectedMac.trim(), ignoreCase = true) }
                }.getOrDefault(false)
            }
        }
    }

    private fun isWifiConnected(expectedSsid: String?): Boolean {
        if (expectedSsid.isNullOrBlank()) return false
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val actual = runCatching { wifiManager.connectionInfo?.ssid?.trim('"') }.getOrNull()
        return !actual.isNullOrBlank() &&
            actual != "<unknown ssid>" &&
            actual.equals(expectedSsid.trim().trim('"'), ignoreCase = false)
    }

    /** Returns every configured vehicle currently visible through Bluetooth or Wi-Fi. */
    fun detectVehicleIds(config: VehiclePresenceConfig): Set<String> {
        if (!config.enabled) return emptySet()
        if (config.vehicles.isNotEmpty()) {
            return config.vehicles.filter { vehicle ->
                isBluetoothConnected(vehicle.bluetoothMac) || isWifiConnected(vehicle.wifiSsid)
            }.map { it.id }.toSet()
        }
        return if (isBluetoothConnected(config.bluetoothMac) || isWifiConnected(config.wifiSsid)) {
            setOf(LEGACY_VEHICLE_ID)
        } else emptySet()
    }

    fun close() {
        if (!hasBluetoothConnectPermission()) return
        synchronized(profiles) {
            profiles.forEach { (profile, proxy) ->
                runCatching { adapter?.closeProfileProxy(profile, proxy) }
            }
            profiles.clear()
        }
    }

    companion object {
        const val LEGACY_VEHICLE_ID = "__legacy_vehicle__"
    }
}
