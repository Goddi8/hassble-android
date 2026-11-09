package dev.eigger.hassble.service

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Android system association + presence state for configured car infotainment Bluetooth devices. */
object VehicleCompanionManager {
    private const val PREFS = "vehicle_companion"
    private const val KEY_AUTO_GATEWAY = "auto_gateway_enabled"
    private const val KEY_PRESENT_ADDRESSES = "present_addresses"

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun manager(context: Context): CompanionDeviceManager =
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    @Suppress("DEPRECATION")
    fun associatedAddresses(context: Context): Set<String> {
        if (!isSupported(context)) return emptySet()
        return runCatching {
            val cdm = manager(context)
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }.toSet()
            } else {
                cdm.associations.map { it.uppercase() }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    fun isAssociated(context: Context, address: String): Boolean =
        associatedAddresses(context).contains(address.trim().uppercase())

    @Suppress("DEPRECATION")
    fun registerPresenceObservation(context: Context, address: String) {
        if (!isSupported(context)) return
        val normalized = address.trim().uppercase()
        if (normalized.isBlank() || !isAssociated(context, normalized)) return
        runCatching { manager(context).startObservingDevicePresence(normalized) }
            .onFailure { LiveEventLogger.log(LogType.LINK, "Companion observation failed for $normalized: ${it.message}") }
    }

    fun ensureObservingAssociations(context: Context) {
        associatedAddresses(context).forEach { registerPresenceObservation(context, it) }
    }

    fun autoGatewayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_GATEWAY, false)

    fun setAutoGatewayEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_GATEWAY, enabled).apply()
        if (!enabled) prefs.edit().putStringSet(KEY_PRESENT_ADDRESSES, emptySet()).apply()
    }

    fun presentAddresses(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PRESENT_ADDRESSES, emptySet())
            .orEmpty().map { it.uppercase() }.toSet()

    fun isPresent(context: Context, address: String): Boolean =
        presentAddresses(context).contains(address.trim().uppercase())

    /** Returns true while at least one associated vehicle is present. */
    fun updatePresence(context: Context, address: String, present: Boolean): Boolean {
        val normalized = address.trim().uppercase()
        if (normalized.isBlank()) return presentAddresses(context).isNotEmpty()
        val next = presentAddresses(context).toMutableSet()
        if (present) next.add(normalized) else next.remove(normalized)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PRESENT_ADDRESSES, next).apply()
        return next.isNotEmpty()
    }

    fun clearPresentAddresses(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PRESENT_ADDRESSES, emptySet()).apply()
    }

    fun associate(
        activity: Activity,
        address: String,
        launchConsent: (IntentSender) -> Unit,
        onAssociated: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!isSupported(activity)) {
            onFailure("Die Fahrzeug-Automatik benötigt Android 12 oder neuer mit Companion-Device-Unterstützung.")
            return
        }
        val normalized = address.trim().uppercase()
        if (normalized.isBlank()) {
            onFailure("Für dieses Fahrzeug ist keine Bluetooth-MAC konfiguriert.")
            return
        }
        if (isAssociated(activity, normalized)) {
            setAutoGatewayEnabled(activity, true)
            registerPresenceObservation(activity, normalized)
            onAssociated(normalized)
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(normalized).build())
            .setSingleDevice(true)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            @Suppress("DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) {
                launchConsent(intentSender)
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                launchConsent(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val associatedAddress = associationInfo.deviceMacAddress?.toString()
                    ?.takeIf { it.isNotBlank() } ?: normalized
                setAutoGatewayEnabled(activity, true)
                registerPresenceObservation(activity, associatedAddress)
                onAssociated(associatedAddress.uppercase())
            }

            override fun onFailure(errorMessage: CharSequence?) {
                onFailure(errorMessage?.toString() ?: "Android konnte das Fahrzeug nicht zuordnen.")
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager(activity).associate(request, ContextCompat.getMainExecutor(activity), callback)
            } else {
                manager(activity).associate(request, callback, Handler(Looper.getMainLooper()))
            }
        }.onFailure { onFailure(it.localizedMessage ?: it.javaClass.simpleName) }
    }

    /** API 31/32 return the result through onActivityResult after the association is created. */
    fun completeLegacyAssociation(context: Context, address: String): Boolean {
        if (!isAssociated(context, address)) return false
        registerPresenceObservation(context, address)
        return true
    }
}
