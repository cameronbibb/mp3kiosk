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
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity() {
    private var spotifyLaunched = false
    private var tapCount = 0
    private var firstTapTime = 0L

    private val maxAttempts = 5
    private val baseLockoutMs = 15 * 60 * 1000L
    private val maxLockoutMs = 24 * 60 * 60 * 1000L

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            statusHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Kiosk home screen: ignore back
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d("KioskAdmin", "onResume start")
        spotifyLaunched = false
        applyKioskState()
        Log.d("KioskAdmin", "applyKioskState done")
        statusHandler.removeCallbacks(statusTicker)
        statusHandler.postDelayed(statusTicker, 30_000)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusTicker)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyKioskState()
    }

    override fun onStop() {
        super.onStop()
        spotifyLaunched = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavBar()
    }

    @SuppressLint("SetTextI18n")
    private fun applyKioskState() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)
            val enabled = prefs.getBoolean("kioskEnabled", false)

            if (enabled) {
                setContentView(R.layout.activity_main)
                findViewById<Button>(R.id.infoButton).setOnClickListener { showInfoDialog() }
                Log.d("KioskAdmin", "layout inflated")
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
                        if (lockoutRemaining(prefs) > 0) {
                            Toast.makeText(this, "Try again later", Toast.LENGTH_SHORT).show()
                        } else {
                            showPinDialog()
                        }
                    }
                }
                Log.d("KioskAdmin", "listeners set")
                Log.d("KioskAdmin", "checking lock state")
                val locked = isInLockTaskMode()
                Log.d("KioskAdmin", "lock state = $locked")
                if (!locked) {
                    Log.d("KioskAdmin", "calling startLockTask")
                    startLockTask()
                    Log.d("KioskAdmin", "startLockTask returned")
                }

            } else {
                Log.d("KioskAdmin", "Kiosk disabled - showing lock option")
                setContentView(R.layout.activity_main)
                findViewById<Button>(R.id.infoButton).setOnClickListener { showInfoDialog() }
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
        updateStatus()
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
        val prefs = getSharedPreferences("kiosk", MODE_PRIVATE)
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Admin")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (lockoutRemaining(prefs) > 0) {
                    Toast.makeText(this, "Try again later", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (checkPin(input.text.toString())) {
                    recordSuccess(prefs)
                    unlockKiosk()
                } else {
                    recordFailure(prefs)
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

        if (KioskPolicy.enableKiosk(this, dpm, admin, prefs)) {
            applyKioskState()
        } else {
            Toast.makeText(this, "No admin PIN set. Set one from the dashboard first.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun lockoutRemaining(prefs: SharedPreferences): Long {
        val remaining = prefs.getLong("lockoutUntil", 0L) - System.currentTimeMillis()
        if (remaining > maxLockoutMs) {
            prefs.edit().putLong("lockoutUntil", 0L).commit()
            return 0L
        }
        return maxOf(remaining, 0L)
    }

    private fun recordFailure(prefs: SharedPreferences) {
        val failures = prefs.getInt("pinFailures", 0) + 1
        val editor = prefs.edit().putInt("pinFailures", failures)
        if (failures >= maxAttempts) {
            val lockouts = prefs.getInt("pinLockouts", 0)
            val duration = minOf(baseLockoutMs shl minOf(lockouts, 7), maxLockoutMs)
            editor.putLong("lockoutUntil", System.currentTimeMillis() + duration)
                .putInt("pinFailures", 0)
                .putInt("pinLockouts", lockouts + 1)
        }
        editor.commit()
    }

    private fun recordSuccess(prefs: SharedPreferences) {
        prefs.edit()
            .putInt("pinFailures", 0)
            .putInt("pinLockouts", 0)
            .putLong("lockoutUntil", 0L)
            .commit()
    }

    @SuppressLint("SetTextI18n", "ServiceCast")
    private fun updateStatus() {
        val batteryText = findViewById<TextView>(R.id.batteryText) ?: return
        val storageText = findViewById<TextView>(R.id.storageText) ?: return
        Log.d("KioskAdmin", "reading battery")

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = if (bm.isCharging) "$pct% · Charging" else "$pct%"

        Log.d("KioskAdmin", "reading storage")

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        storageText.text = String.format(Locale.US, "%.1f GB free", freeGb)

        Log.d("KioskAdmin", "status done")
    }

    @SuppressLint("InflateParams")
    private fun showInfoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_info, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.closeInfoButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun hideNavBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}