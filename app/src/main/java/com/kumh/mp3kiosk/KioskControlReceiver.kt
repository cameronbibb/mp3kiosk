package com.kumh.mp3kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class KioskControlReceiver : BroadcastReceiver() {
    companion object {
        private const val CONTROL_TOKEN = BuildConfig.KIOSK_TOKEN
    }
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("token") != CONTROL_TOKEN) {
            Log.d("KioskAdmin", "Control rejected: bad token")
            return
        }

        val pending = goAsync()

        val action = intent.getStringExtra("action")
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, KioskAdminReceiver::class.java)

        when (action) {
            "enableKiosk" -> {
                Log.d("KioskAdmin", "Enabling kiosk...")
                for (restriction in temporaryRestrictions) {
                    dpm.addUserRestriction(admin, restriction)
                }
                for (restriction in permanentRestrictions) {
                    dpm.addUserRestriction(admin, restriction)
                }
                val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("kioskEnabled", true).apply()
                val launch = Intent(context, MainActivity::class.java)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                Log.d("KioskAdmin", "Kiosk enabled")
            }
            "disableKiosk" -> {
                Log.d("KioskAdmin", "Disabling kiosk...")
                val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("kioskEnabled", false).apply()
                dpm.setLockTaskPackages(admin, arrayOf())
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                for (restriction in temporaryRestrictions) {
                    dpm.clearUserRestriction(admin, restriction)
                }
                Log.d("KioskAdmin", "Kiosk disabled")
            }
            "wipe" -> {
                Log.d("KioskAdmin", "Wiping device")
                dpm.wipeData(0)
                Log.d("KioskAdmin", "Wipe initiated")
            }
            "restrict" -> {
                Log.d("KioskAdmin", "Setting permanent user restrictions...")
                for (restriction in permanentRestrictions) {
                    dpm.addUserRestriction(admin, restriction)
                }
                Log.d("KioskAdmin", "Permanent user restrictions set")
            }
            "clearRestrictions" -> {
                Log.d("KioskAdmin", "Clearing permanent user restrictions")
                for (restriction in permanentRestrictions) {
                    dpm.clearUserRestriction(admin, restriction)
                }
                Log.d("KioskAdmin", "Permanent user restrictions cleared")
            }
            "stopLock" -> {
                Log.d("KioskAdmin", "Stopping lock tasks")
                dpm.setLockTaskPackages(admin, arrayOf())
                Log.d("KioskAdmin", "Lock task stopped")
            }
            "clearHome" -> {
                Log.d("KioskAdmin", "Clearing persistent home")
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                Log.d("KioskAdmin", "Persistent home cleared")
            }
            else -> Log.d("KioskAdmin", "Unknown action: $action")
        }

        pending.finish()
    }
}