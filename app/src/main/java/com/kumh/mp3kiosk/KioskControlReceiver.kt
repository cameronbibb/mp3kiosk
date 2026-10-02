package com.kumh.mp3kiosk

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.provider.Settings
import android.util.Base64
import com.kumh.mp3kiosk.KioskPolicy.permanentRestrictions
import com.kumh.mp3kiosk.KioskPolicy.temporaryRestrictions
import org.json.JSONArray
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
                    pending.setResultData(queryState(context, dpm, admin, prefs))
                }
                "enableKiosk" -> {
                    if (KioskPolicy.enableKiosk(context, dpm, admin, prefs)) {
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
                            .putInt("pinFailures", 0)
                            .putInt("pinLockouts", 0)
                            .putLong("lockoutUntil", 0L)
                            .commit()
                        Log.d("KioskAdmin", "PIN set")
                        pending.setResultCode(RESULT_OK)
                        pending.setResultData("pinSet")
                    }
                }
                "clearLockout" -> {
                    prefs.edit()
                        .putInt("pinFailures", 0)
                        .putInt("pinLockouts", 0)
                        .putLong("lockoutUntil", 0L)
                        .commit()
                    Log.d("KioskAdmin", "PIN lockout cleared")
                    pending.setResultCode(RESULT_OK)
                    pending.setResultData("lockoutCleared")
                }
                "unhideApp" -> {
                    val pkg = intent.getStringExtra("pkg")
                    if (pkg.isNullOrBlank()) {
                        pending.setResultCode(RESULT_MISSING_ARG)
                        pending.setResultData("missingPkg")
                    } else {
                        val ok = dpm.setApplicationHidden(admin, pkg, false)
                        pending.setResultCode(if (ok) RESULT_OK else RESULT_ERROR)
                        pending.setResultData(if (ok) "unhidden" else "unhideFailed")
                    }
                }

                "setTime" -> {
                    val tz = intent.getStringExtra("tz")
                    val epoch = intent.getLongExtra("epoch", -1L)
                    if (tz.isNullOrBlank() || epoch <= 0) {
                        pending.setResultCode(RESULT_MISSING_ARG)
                        pending.setResultData("missingTimeArgs")
                    } else {
                        setAutoTimeZone(dpm, admin, false)
                        setAutoTime(dpm, admin, false)
                        val tzOk = dpm.setTimeZone(admin, tz)
                        val timeOk = dpm.setTime(admin, epoch)
                        setAutoTime(dpm, admin, true)
                        pending.setResultCode(if (tzOk && timeOk) RESULT_OK else RESULT_ERROR)
                        pending.setResultData("tz=$tzOk time=$timeOk")
                    }
                }

                "setClient" -> {
                    val initials = intent.getStringExtra("initials")?.trim()?.uppercase().orEmpty()
                    if (initials.isNotEmpty() && !initials.matches(Regex("[A-Z.]{1,6}"))) {
                        pending.setResultCode(RESULT_MISSING_ARG)
                        pending.setResultData("invalidInitials")
                    } else {
                        prefs.edit().putString("clientInitials", initials).commit()
                        KioskPolicy.applyLockScreenInfo(dpm, admin, initials)
                        pending.setResultCode(RESULT_OK)
                        pending.setResultData(if (initials.isEmpty()) "clientCleared" else "clientSet")
                    }
                }

                "clearScreenLock" -> {
                    val token = prefs.getString("resetToken", null)
                        ?.let { Base64.decode(it, Base64.NO_WRAP) }
                    val ok = token != null && dpm.resetPasswordWithToken(admin, "", token, 0)
                    if (ok) dpm.setKeyguardDisabled(admin, true)
                    pending.setResultCode(if (ok) RESULT_OK else RESULT_ERROR)
                    pending.setResultData(if (ok) "screenLockCleared" else "clearFailed")
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
    @SuppressLint("ServiceCast")
    private fun queryState(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        prefs: SharedPreferences
    ): String {
        val isOwner = dpm.isDeviceOwnerApp(context.packageName)
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager

        return JSONObject().apply {
            put("deviceOwner", isOwner)
            put("kioskEnabled", prefs.getBoolean("kioskEnabled", false))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("pinSet", prefs.contains("pinHash"))
            put("ownRestrictions",
                if (isOwner) bundleKeys(dpm.getUserRestrictions(admin)) else JSONArray()
            )
            put("effectiveRestrictions", bundleKeys(um.userRestrictions))
            put("deviceTime", System.currentTimeMillis())
            put("clientInitials", prefs.getString("clientInitials", ""))

            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            put("screenLockSet", km.isDeviceSecure)
            put("resetTokenActive", if (isOwner) dpm.isResetPasswordTokenActive(admin) else false)
        }.toString()
    }

    private fun bundleKeys(b: Bundle): JSONArray =
        JSONArray(b.keySet().filter { b.getBoolean(it) }.sorted())

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

    private fun setAutoTime(dpm: DevicePolicyManager, admin: ComponentName, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeEnabled(admin, on)
        } else {
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, if (on) "1" else "0")
        }
    }

    private fun setAutoTimeZone(dpm: DevicePolicyManager, admin: ComponentName, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeZoneEnabled(admin, on)
        } else {
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, if (on) "1" else "0")
        }
    }

}