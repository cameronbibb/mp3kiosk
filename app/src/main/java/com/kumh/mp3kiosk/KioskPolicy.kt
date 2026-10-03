package com.kumh.mp3kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

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
        UserManager.DISALLOW_CONFIG_BLUETOOTH,
        UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
        UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT
    )

    val alwaysHiddenApps = listOf(
        // Other music, video, and books
        "com.amazon.mp3",
        "com.apple.android.music",
        "com.aspiro.tidal",
        "com.pandora.android",
        "com.hiby.music",
        "com.aimp.player",
        "remix.myplayer",
        "com.spotify.kids",
        "com.audible.application",
        "com.overdrive.mobile.android.libby",
        "com.amazon.kindle",
        "com.flyersoft.moonreader",
        "com.flyersoft.moonreaderp",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.mxtech.videoplayer.pro",
        "com.kmplayer",
        "com.android.fmradio",

        // Installing apps and sharing files
        "com.android.vending",
        "com.innioasis.xapkinstaller",
        "com.omniashare.minishare",
        "com.android.gallery3d",

        // Recording
        "com.android.soundrecorder",
        "com.mediatek.callrecorder",

        // Parental control apps (one had a known bypass; both compete with the kiosk)
        "com.innioasis.parentmanager",
        "com.hongyao.parentalmanagement",

        // Factory, engineering, and debug tools
        "com.mediatek.engineermode",
        "com.jz.agingtest",
        "com.zte.engineer",
        "com.mediatek.lbs.em2.ui",

        // System changes residents shouldn't be able to make
        "com.google.android.apps.wellbeing",   // app timers could block Spotify

        // Apps with no kiosk use
        "com.android.calendar",
        "com.android.calculator2",
        "com.android.deskclock",
        "com.android.egg",
    )

    val unlockedOnlyApps = listOf(
        "com.android.chrome",                  // Spotify login needs a browser
        "com.android.documentsui",             // Files — the file manager on Android 14
        "com.google.android.documentsui",
        "com.itel.filemanager",                // Android 9, older build
        "com.mediatek.filemanager",            // Android 9, newer build
        "com.android.providers.downloads.ui",  // Downloads app
    )

    val neverHideApps = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",                   // holds the boot-time fallback screen
        "com.android.launcher3",                  // home screen when unlocked
        "com.android.shell",                      // ADB
        "com.android.packageinstaller",           // on-device APK installs
        "com.android.permissioncontroller",
        "com.android.managedprovisioning",
        "com.android.intentresolver",             // the system app chooser
        "com.android.webview",
        "com.google.android.webview",             // Spotify login
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",   // keyboard for the PIN dialog
        "com.android.bluetooth",                  // headphones
        "com.android.providers.settings",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.externalstorage",
        "com.android.phone",
        "com.kumh.mp3kiosk",
        "com.spotify.music",
        "com.mediatek.voiceunlock",            // another way to set a screen lock
        "com.android.dynsystem",               // can boot a different system image

        // Hiding one of these caused a boot loop on Android 14 (culprit not yet isolated)
        "com.debug.loggerui",
        "com.focaltech.fpsensormmitest",
        "com.mediatek.ygps",
        "com.mediatek.factorymode",
        "com.sprd.factorymode",
        )

    fun enableKiosk(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        prefs: SharedPreferences
    ): Boolean {
        if (!prefs.contains("pinHash")) return false
        ensureResetToken(dpm, admin, prefs)

        for (r in temporaryRestrictions) dpm.addUserRestriction(admin, r)
        for (r in permanentRestrictions) dpm.addUserRestriction(admin, r)

        setAppsHidden(dpm, admin, alwaysHiddenApps, true)
        setAppsHidden(dpm, admin, unlockedOnlyApps, true)

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin, filter,
            ComponentName(context.packageName, MainActivity::class.java.name)
        )
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        )
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName, "com.spotify.music"))

        applyLockScreenInfo(dpm, admin, prefs.getString("clientInitials", "").orEmpty())

        val keyguardOff = dpm.setKeyguardDisabled(admin, true)
        Log.d("KioskAdmin", "setKeyguardDisabled = $keyguardOff")


        prefs.edit().putBoolean("kioskEnabled", true).commit()
        return true
    }
    fun disableKiosk(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        prefs: SharedPreferences
    ) {
        Log.d("KioskAdmin", "Disabling kiosk...")
        prefs.edit().putBoolean("kioskEnabled", false).commit()
        dpm.setLockTaskPackages(admin, arrayOf())
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        for (r in temporaryRestrictions) {
            dpm.clearUserRestriction(admin, r)
        }
        setAppsHidden(dpm, admin, unlockedOnlyApps, false)
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

    fun setAppsHidden(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packages: List<String>,
        hidden: Boolean
    ) {
        for (pkg in packages) {
            if (hidden && pkg in neverHideApps) {
                Log.w("KioskAdmin", "Refusing to hide protected package $pkg")
                continue
            }
            val ok = try {
                dpm.setApplicationHidden(admin, pkg, hidden)
            } catch (e: Exception) {
                false
            }
            Log.d("KioskAdmin", "setApplicationHidden($pkg, $hidden) = $ok")
        }
    }

    private const val LOCK_MESSAGE = "Property of KUMH. Please return to staff."

    fun applyLockScreenInfo(dpm: DevicePolicyManager, admin: ComponentName, initials: String) {
        val text = if (initials.isEmpty()) LOCK_MESSAGE else "$initials · $LOCK_MESSAGE"
        dpm.setDeviceOwnerLockScreenInfo(admin, text)
    }

    fun ensureResetToken(dpm: DevicePolicyManager, admin: ComponentName, prefs: SharedPreferences) {
        if (dpm.isResetPasswordTokenActive(admin) && prefs.contains("resetToken")) return
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ok = dpm.setResetPasswordToken(admin, token)
        if (ok) {
            prefs.edit()
                .putString("resetToken", Base64.encodeToString(token, Base64.NO_WRAP))
                .commit()
        }
        Log.d("KioskAdmin", "setResetPasswordToken = $ok, active = ${dpm.isResetPasswordTokenActive(admin)}")
    }
}