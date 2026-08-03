package com.kumh.mp3kiosk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.app.ActivityOptions
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        applyKioskState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyKioskState()
    }

    private fun applyKioskState() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("kioskEnabled", false)

            if (enabled) {
                val filter = IntentFilter(Intent.ACTION_MAIN)
                filter.addCategory(Intent.CATEGORY_HOME)
                filter.addCategory(Intent.CATEGORY_DEFAULT)

                dpm.addPersistentPreferredActivity(
                    admin,
                    filter,
                    ComponentName(packageName, MainActivity::class.java.name)
                )

                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )

                dpm.setLockTaskPackages(admin, arrayOf(packageName, "com.spotify.music"))
                tryLaunchSpotify(dpm, admin, 0)

            } else {
                Log.d("KioskAdmin", "Kiosk disabled - skipping lockdown")
            }
        } else {
            val tv = TextView(this)
            tv.text = "Device not provisioned."
            tv.textSize = 20f
            tv.gravity = Gravity.CENTER
            setContentView(tv)
        }
    }
    private fun tryLaunchSpotify(dpm: DevicePolicyManager, admin: ComponentName, attempt: Int) {
        val failed = dpm.setPackagesSuspended(admin, arrayOf("com.spotify.music"), true)
        Log.d("KioskAdmin", "Suspend failed for: ${failed.joinToString()}")
        dpm.setPackagesSuspended(admin, arrayOf("com.spotify.music"), false)

        val launch = packageManager.getLaunchIntentForPackage("com.spotify.music")

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Handler(Looper.getMainLooper()).postDelayed({
                val options = ActivityOptions.makeBasic()
                options.setLockTaskEnabled(true)
                startActivity(launch, options.toBundle())
            }, 1000)
            return
        }
        if (attempt < 5) {
            Log.d("KioskAdmin", "Spotify not ready, retry $attempt")
            Handler(Looper.getMainLooper()).postDelayed({
                tryLaunchSpotify(dpm, admin, attempt + 1)}, 1000)
        } else {
            Log.d("KioskAdmin", "Spotify launch intent null - not installed?")
            val tv = TextView(this)
            tv.text = "Music app unavailable. Please contact staff."
            tv.textSize = 20f
            tv.gravity = Gravity.CENTER
            setContentView(tv)
            startLockTask()
        }
    }

}