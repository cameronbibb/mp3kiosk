package com.kumh.mp3kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    private var spotifyLaunched = false
    private var tapCount = 0
    private var firstTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        spotifyLaunched = false
        applyKioskState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyKioskState()
    }

    override fun onStop() {
        super.onStop()
        spotifyLaunched = false
    }

    @SuppressLint("SetTextI18n")
    private fun applyKioskState() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)
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

                setContentView(R.layout.activity_main)
                findViewById<Button>(R.id.musicButton).setOnClickListener {
                    tryLaunchSpotify(dpm, admin, 0)
                }
                findViewById<View>(R.id.adminZone).setOnClickListener {
                    val now = System.currentTimeMillis()
                    if (now - firstTapTime > 5000) {
                        tapCount = 0
                        firstTapTime = now
                    }
                    tapCount++
                    if (tapCount >= 7) {
                        tapCount = 0
                        showPinDialog()
                    }
                }

                if (!isInLockTaskMode()) {
                    Log.d("KioskAdmin", "Locking to self")
                    startLockTask()
                }

            } else {
                Log.d("KioskAdmin", "Kiosk disabled - showing lock option")
                setContentView(R.layout.activity_main)
                findViewById<Button>(R.id.musicButton).visibility = View.GONE
                findViewById<Button>(R.id.lockButton).apply {
                    visibility = View.VISIBLE
                    setOnClickListener { relockKiosk() }
                }
            }
        } else {
            val tv = TextView(this)
            tv.text = "Device not provisioned."
            tv.textSize = 20f
            tv.gravity = Gravity.CENTER
            setContentView(tv)
        }
    }

    private fun isInLockTaskMode(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }
    @SuppressLint("SetTextI18n")
    private fun tryLaunchSpotify(dpm: DevicePolicyManager, admin: ComponentName, attempt: Int) {
        if (spotifyLaunched) return

        val launch = packageManager.getLaunchIntentForPackage("com.spotify.music")

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Handler(Looper.getMainLooper()).postDelayed({
                val options = ActivityOptions.makeBasic()
                options.setLockTaskEnabled(true)
                startActivity(launch, options.toBundle())
                spotifyLaunched = true
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

    private fun showPinDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Admin")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (checkPin(input.text.toString())) {
                    unlockKiosk()
                } else {
                    Toast.makeText(this, "Incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPin(pin: String): Boolean {
        val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)
        val salt = prefs.getString("pinSalt", null) ?: return false
        val hash = prefs.getString("pinHash", null) ?: return false
        return KioskPolicy.hashPin(pin, salt) == hash
    }

    private fun unlockKiosk() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskAdminReceiver::class.java)
        val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)

        stopLockTask()
        KioskPolicy.disableKiosk(this, dpm, admin, prefs)
        finish()
    }
    private fun relockKiosk() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskAdminReceiver::class.java)
        val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)

        if (KioskPolicy.enableKiosk(dpm, admin, prefs)) {
            applyKioskState()
        } else {
            Toast.makeText(this, "No admin PIN set. Set one from the dashboard first.",
                Toast.LENGTH_LONG).show()
        }
    }
}