package com.autoclick.gesture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtOverlayStatus: TextView
    private lateinit var txtAccessibilityStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnToggleService: Button
    private lateinit var txtNoRecordings: TextView
    private lateinit var recordingsContainer: LinearLayout
    private lateinit var storage: RecordingStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = RecordingStorage(this)

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus)
        txtAccessibilityStatus = findViewById(R.id.txtAccessibilityStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnToggleService = findViewById(R.id.btnToggleService)
        txtNoRecordings = findViewById(R.id.txtNoRecordings)
        recordingsContainer = findViewById(R.id.recordingsContainer)

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
        refreshRecordingsList()
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

    // ---- recordings list ----

    private fun refreshRecordingsList() {
        recordingsContainer.removeAllViews()
        val names = storage.list()
        txtNoRecordings.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val activeName = storage.activeName()
        val inflater = LayoutInflater.from(this)
        for (name in names) {
            val row = inflater.inflate(R.layout.item_recording, recordingsContainer, false)
            val recording = storage.load(name)
            val strokeCount = recording?.strokes?.size ?: 0
            val seconds = (recording?.totalDurationMs ?: 0L) / 1000f
            val isActive = name == activeName

            row.findViewById<TextView>(R.id.txtRecordingName).text =
                if (isActive) "$name (active)" else name
            row.findViewById<TextView>(R.id.txtRecordingMeta).text =
                "$strokeCount stroke${if (strokeCount == 1) "" else "s"} - %.1fs".format(seconds)

            row.findViewById<Button>(R.id.btnUseRecording).apply {
                isEnabled = !isActive
                text = if (isActive) "Active" else "Use"
                setOnClickListener {
                    storage.setActive(name)
                    refreshRecordingsList()
                }
            }
            row.findViewById<Button>(R.id.btnRenameRecording).setOnClickListener {
                promptRename(name)
            }
            row.findViewById<Button>(R.id.btnDeleteRecording).setOnClickListener {
                promptDelete(name)
            }
            recordingsContainer.addView(row)
        }
    }

    private fun promptRename(name: String) {
        val input = EditText(this).apply {
            setText(name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename recording")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() -> Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show()
                    newName == name -> Unit
                    storage.exists(newName) ->
                        Toast.makeText(this, "'$newName' already exists", Toast.LENGTH_SHORT).show()
                    else -> {
                        storage.rename(name, newName)
                        refreshRecordingsList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording")
            .setMessage("Delete '$name'? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                storage.delete(name)
                refreshRecordingsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        // Best-effort UI flag only; the service is the source of truth for whether it's alive.
        private var isServiceRunning = false
    }
}
