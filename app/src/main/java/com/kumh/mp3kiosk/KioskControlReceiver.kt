package com.kumh.mp3kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.kumh.mp3kiosk.KioskPolicy.permanentRestrictions
import com.kumh.mp3kiosk.KioskPolicy.temporaryRestrictions
import org.json.JSONObject

class KioskControlReceiver : BroadcastReceiver() {
    companion object {
        private const val CONTROL_TOKEN = BuildConfig.KIOSK_TOKEN
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val RESULT_UNAUTHORIZED = 3
        const val RESULT_UNKNOWN_ACTION = 4
        const val RESULT_MISSING_ARG = 5
        const val RESULT_NO_PIN = 6
    }

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
                    if (KioskPolicy.enableKiosk(dpm, admin, prefs)) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        pending.setResultCode(RESULT_OK)
                        pending.setResultData("kioskEnabled")
                    } else {
                        pending.setResultCode(RESULT_NO_PIN)
                        pending.setResultData("noPin")
                    }
                }
                "disableKiosk" -> {
                    KioskPolicy.disableKiosk(context, dpm, admin, prefs)
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
                "setPin" -> {
                    val pin = intent.getStringExtra("pin")
                    if (pin.isNullOrBlank() || !pin.all { it.isDigit() } || pin.length !in 4..8) {
                        Log.d("KioskAdmin", "setPin: invalid or missing pin")
                        pending.setResultCode(RESULT_MISSING_ARG)
                        pending.setResultData("invalidPin")
                    } else {
                        val salt = KioskPolicy.generateSalt()
                        prefs.edit()
                            .putString("pinSalt", salt)
                            .putString("pinHash", KioskPolicy.hashPin(pin, salt))
                            .apply()
                        Log.d("KioskAdmin", "PIN set")
                        pending.setResultCode(RESULT_OK)
                        pending.setResultData("pinSet")
                    }
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
            put("pinSet", prefs.contains("pinHash"))
        }.toString()
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