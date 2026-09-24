package com.kumh.mp3kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log

object KioskPolicy {

    val permanentRestrictions = listOf(
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_SET_WALLPAPER,
        UserManager.DISALLOW_OUTGOING_BEAM,
        UserManager.DISALLOW_BLUETOOTH_SHARING
    )

    val temporaryRestrictions = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_CONFIG_WIFI,
        UserManager.DISALLOW_NETWORK_RESET,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_CONFIG_BLUETOOTH
    )

    fun enableKiosk(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        prefs: SharedPreferences
    ): Boolean {
        if (!prefs.contains("pinHash")) return false
        for (r in temporaryRestrictions) dpm.addUserRestriction(admin, r)
        for (r in permanentRestrictions) dpm.addUserRestriction(admin, r)
        prefs.edit().putBoolean("kioskEnabled", true).apply()
        return true
    }
    fun disableKiosk(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        prefs: SharedPreferences
    ) {
        Log.d("KioskAdmin", "Disabling kiosk...")
        prefs.edit().putBoolean("kioskEnabled", false).apply()
        dpm.setLockTaskPackages(admin, arrayOf())
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        for (r in temporaryRestrictions) {
            dpm.clearUserRestriction(admin, r)
        }
    }

    fun hashPin(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}