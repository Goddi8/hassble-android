package dev.eigger.hassble.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService

/** System-bound service for Bluetooth connection/disconnection events of associated cars. */
class VehicleCompanionService : CompanionDeviceService() {

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { handlePresence(it, true) }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { handlePresence(it, false) }
    }

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        handlePresence(address, true)
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        handlePresence(address, false)
    }

    private fun handlePresence(address: String, present: Boolean) {
        if (!VehicleCompanionManager.autoGatewayEnabled(this)) return

        val anotherVehiclePresent = VehicleCompanionManager.updatePresence(this, address, present)
        LiveEventLogger.log(
            LogType.LINK,
            "Companion vehicle ${address.uppercase()} ${if (present) "connected" else "disconnected"}; any vehicle present=$anotherVehiclePresent",
        )

        if (present) {
            BleGatewayService.startForVehicle(this)
        } else if (!anotherVehiclePresent) {
            BleGatewayService.scheduleVehicleStop(this)
        }
    }
}
