package com.autoclick.gesture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtOverlayStatus: TextView
    private lateinit var txtAccessibilityStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnToggleService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus)
        txtAccessibilityStatus = findViewById(R.id.txtAccessibilityStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnToggleService = findViewById(R.id.btnToggleService)

        btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopService(Intent(this, OverlayService::class.java))
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    txtOverlayStatus.text = "Overlay permission still required"
                    return@setOnClickListener
                }
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            }
            isServiceRunning = !isServiceRunning
            updateToggleButton()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        txtOverlayStatus.text = if (overlayGranted) "Overlay: granted" else "Overlay: not granted"

        val accessibilityEnabled = isAccessibilityServiceEnabled()
        txtAccessibilityStatus.text =
            if (accessibilityEnabled) "Accessibility service: enabled" else "Accessibility service: not enabled"

        updateToggleButton()
    }

    private fun updateToggleButton() {
        btnToggleService.text =
            if (isServiceRunning) getString(R.string.stop_controls) else getString(R.string.start_controls)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    companion object {
        // Best-effort UI flag only; the service is the source of truth for whether it's alive.
        private var isServiceRunning = false
    }
}
