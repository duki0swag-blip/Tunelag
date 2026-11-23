package com.tunelag.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainScreen : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var tunnelButton: Button
    private lateinit var lagSwitch: Switch
    private lateinit var bubbleButton: Button
    private lateinit var logText: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_main)

        statusText = findViewById(R.id.statusText)
        tunnelButton = findViewById(R.id.tunnelButton)
        lagSwitch = findViewById(R.id.lagSwitch)
        bubbleButton = findViewById(R.id.bubbleButton)
        logText = findViewById(R.id.logText)

        val prefs = AppSettings.get(this)

        val auto = findViewById<Switch>(R.id.autoSwitch)
        auto.isChecked = prefs.getBoolean(AppSettings.KEY_AUTO, false)
        auto.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean(AppSettings.KEY_AUTO, value).apply()
            pushAction(VpnTunnelService.ACTION_REFRESH)
        }

        val bidi = findViewById<Switch>(R.id.bidiSwitch)
        bidi.isChecked = prefs.getBoolean(AppSettings.KEY_BIDI, false)
        bidi.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean(AppSettings.KEY_BIDI, value).apply()
            pushAction(VpnTunnelService.ACTION_REFRESH)
        }

        val profiles = findViewById<RadioGroup>(R.id.profileGroup)
        profiles.check(
            when (prefs.getInt(AppSettings.KEY_PROFILE, 1)) {
                0 -> R.id.profileSoft
                2 -> R.id.profileHard
                else -> R.id.profileMid
            }
        )
        profiles.setOnCheckedChangeListener { _, id ->
            val chosen = when (id) {
                R.id.profileSoft -> 0
                R.id.profileHard -> 2
                else -> 1
            }
            prefs.edit().putInt(AppSettings.KEY_PROFILE, chosen).apply()
            pushAction(VpnTunnelService.ACTION_REFRESH)
        }

        lagSwitch.setOnCheckedChangeListener { _, _ ->
            pushAction(VpnTunnelService.ACTION_TOGGLE_LAG)
        }

        tunnelButton.setOnClickListener {
            if (RuntimeState.tunnelUp) {
                pushAction(VpnTunnelService.ACTION_STOP)
            } else {
                val consent = VpnService.prepare(this)
                if (consent != null) startActivityForResult(consent, REQ_VPN) else launchTunnel()
            }
        }

        bubbleButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ), REQ_OVERLAY
                )
                return@setOnClickListener
            }
            val on = !prefs.getBoolean(AppSettings.KEY_BUBBLE, false)
            prefs.edit().putBoolean(AppSettings.KEY_BUBBLE, on).apply()
            if (on && !RuntimeState.tunnelUp) {
                val consent = VpnService.prepare(this)
                if (consent != null) startActivityForResult(consent, REQ_VPN) else launchTunnel()
            } else {
                pushAction(VpnTunnelService.ACTION_TOGGLE_BUBBLE)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        EventLog.attachSink {
            runOnUiThread { logText.text = EventLog.dump() }
        }
        logText.text = EventLog.dump()
        ui.post(refresher)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) launchTunnel()
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) {
                AppSettings.get(this).edit().putBoolean(AppSettings.KEY_BUBBLE, true).apply()
                if (RuntimeState.tunnelUp) pushAction(VpnTunnelService.ACTION_TOGGLE_BUBBLE)
            }
        }
    }

    private fun launchTunnel() {
        ContextCompat.startForegroundService(this, Intent(this, VpnTunnelService::class.java))
    }

    private fun pushAction(action: String) {
        startService(Intent(this, VpnTunnelService::class.java).setAction(action))
    }

    private fun render() {
        statusText.text = when {
            !RuntimeState.tunnelUp -> "Túnel inactivo"
            RuntimeState.lagActive -> "Cortando"
            RuntimeState.foundEndpoints > 0 -> "Activo · ${RuntimeState.foundEndpoints} endpoints del juego"
            else -> "Activo · sin endpoints (entra a una partida)"
        }
        tunnelButton.text =
            if (RuntimeState.tunnelUp) getString(R.string.stop_tunnel) else getString(R.string.start_tunnel)
        lagSwitch.isEnabled = RuntimeState.tunnelUp

        val bubbleOn = AppSettings.get(this).getBoolean(AppSettings.KEY_BUBBLE, false) &&
            Settings.canDrawOverlays(this)
        bubbleButton.text =
            getString(if (bubbleOn) R.string.bubble_on else R.string.bubble_off)

        if (lagSwitch.isChecked != RuntimeState.lagActive && RuntimeState.tunnelUp) {
            lagSwitch.setOnCheckedChangeListener(null)
            lagSwitch.isChecked = RuntimeState.lagActive
            lagSwitch.setOnCheckedChangeListener { _, _ ->
                pushAction(VpnTunnelService.ACTION_TOGGLE_LAG)
            }
        }
    }

    override fun onDestroy() {
        EventLog.attachSink(null)
        ui.removeCallbacks(refresher)
        super.onDestroy()
    }

    private companion object {
        const val REQ_VPN = 1
        const val REQ_NOTIF = 2
        const val REQ_OVERLAY = 3
    }
}
