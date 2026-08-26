package com.kumh.mp3kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import org.json.JSONObject

class KioskControlReceiver : BroadcastReceiver() {
    companion object {
        private const val CONTROL_TOKEN = BuildConfig.KIOSK_TOKEN
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val RESULT_UNAUTHORIZED = 3
        const val RESULT_UNKNOWN_ACTION = 4
    }
    private val permanentRestrictions = listOf(
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_SET_WALLPAPER,
        UserManager.DISALLOW_OUTGOING_BEAM,
        UserManager.DISALLOW_BLUETOOTH_SHARING
    )

    private val temporaryRestrictions = listOf(
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
        val pending = goAsync()
        try {
            if (intent.getStringExtra("token") != CONTROL_TOKEN) {
                Log.d("KioskAdmin", "Control rejected: bad token")
                pending.setResultCode(RESULT_UNAUTHORIZED)
                pending.setResultData("badToken")
                return
            }

            val action = intent.getStringExtra("action")
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, KioskAdminReceiver::class.java)
            val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

            when (action) {
                "queryState" -> {
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData(queryState(context, dpm, prefs))
                }
                "enableKiosk" -> {
                    enableKiosk(context, dpm, admin, prefs)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("kioskEnabled")
                }
                "disableKiosk" -> {
                    disableKiosk(context, dpm, admin, prefs)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("kioskDisabled")
                }
                "wipe" -> {
                    wipeDevice(dpm)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("wipeInitiated")
                }
                "restrict" -> {
                    restrictDevice(dpm, admin)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("permanentRestrictionsSet")
                }
                "clearRestrictions" -> {
                    clearRestrictions(dpm, admin)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("permanentRestrictionsCleared")
                }
                "stopLock" -> {
                    stopLock(dpm, admin)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("lockTaskStopped")
                }
                "clearHome" -> {
                    clearHome(dpm, admin, context.packageName)
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("persistentHomeCleared")
                }
                else -> {
                    pending.setResultCode(RESULT_UNKNOWN_ACTION)
                    pending.setResultData("unknownAction: $action")
                }
            }
        } catch (e: Exception) {
            Log.e("KioskAdmin", "Command failed", e)
            pending.setResultCode(RESULT_ERROR)
            pending.setResultData(e.message ?: "error")
        } finally {
            pending.finish()
        }
    }
    private fun queryState(context: Context, dpm: DevicePolicyManager, prefs: SharedPreferences): String {
        return JSONObject().apply {
            put("deviceOwner", dpm.isDeviceOwnerApp(context.packageName))
            put("kioskEnabled", prefs.getBoolean("kioskEnabled", false))
            put("appVersion", BuildConfig.VERSION_NAME)
        }.toString()
    }
    private fun enableKiosk(context: Context, dpm: DevicePolicyManager, admin: ComponentName, prefs: SharedPreferences) {
        Log.d("KioskAdmin", "Enabling kiosk...")
        for (restriction in temporaryRestrictions) {
            dpm.addUserRestriction(admin, restriction)
        }
        for (restriction in permanentRestrictions) {
            dpm.addUserRestriction(admin, restriction)
        }
        prefs.edit().putBoolean("kioskEnabled", true).apply()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun disableKiosk(context: Context, dpm: DevicePolicyManager, admin: ComponentName, prefs: SharedPreferences) {
        Log.d("KioskAdmin", "Disabling kiosk...")
        prefs.edit().putBoolean("kioskEnabled", false).apply()
        dpm.setLockTaskPackages(admin, arrayOf())
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        for (restriction in temporaryRestrictions) {
            dpm.clearUserRestriction(admin, restriction)
        }
    }

    private fun wipeDevice(dpm: DevicePolicyManager) {
        Log.d("KioskAdmin", "Wiping device...")
        dpm.wipeData(0)
    }

    private fun restrictDevice(dpm: DevicePolicyManager, admin: ComponentName) {
        Log.d("KioskAdmin", "Setting permanent user restrictions...")
        for (restriction in permanentRestrictions) {
            dpm.addUserRestriction(admin, restriction)
        }
    }

    private fun clearRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        Log.d("KioskAdmin", "Clearing permanent user restrictions...")
        for (restriction in permanentRestrictions) {
            dpm.clearUserRestriction(admin, restriction)
        }
    }

    private fun stopLock(dpm: DevicePolicyManager, admin: ComponentName) {
        Log.d("KioskAdmin", "Stopping lock tasks...")
        dpm.setLockTaskPackages(admin, arrayOf())
    }

    private fun clearHome(dpm: DevicePolicyManager, admin: ComponentName, packageName: String) {
        Log.d("KioskAdmin", "Clearing persistent home...")
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
    }
}