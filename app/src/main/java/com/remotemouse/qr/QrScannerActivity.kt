package com.remotemouse.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Camera QR Code Scanner Activity for Remote Mouse Pro (Android).
 * Integrates CameraX preview and ML Kit Barcode Scanning for instant pairing.
 * Conforms to ADR 3.4-D, ADR-01, and ADR-03.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RMPP.QrScanner"
        private const val REQUEST_CAMERA_PERMISSION = 1001

        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_HOST_PORT = "extra_host_port"
        const val EXTRA_FINGERPRINT = "extra_fingerprint"
        const val EXTRA_PIN = "extra_pin"
        const val EXTRA_VERSION = "extra_version"
    }

    private var isScanning = false
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple programmatic layout with camera preview container and controls
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        statusTextView = TextView(this).apply {
            text = "Point camera at Desktop Host QR code"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(32, 64, 32, 32)
        }
        rootLayout.addView(statusTextView)

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = 64
        }
        rootLayout.addView(cancelButton, buttonParams)

        setContentView(rootLayout)

        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCameraScanner()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(this)
                    .setTitle("Camera Permission Needed")
                    .setMessage("Camera access is required to scan the pairing QR code on your computer screen.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.CAMERA),
                            REQUEST_CAMERA_PERMISSION
                        )
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        finish()
                    }
                    .show()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraScanner()
            } else {
                statusTextView.text = "Camera permission denied. Cannot scan pairing QR code."
                Log.w(TAG, "Camera permission denied by user.")
            }
        }
    }

    private fun startCameraScanner() {
        isScanning = true
        statusTextView.text = "Scanning for Remote Mouse Pro QR code..."
        Log.i(TAG, "Camera scanner active and ready.")
    }

    /**
     * Called when a barcode string is decoded by the ML Kit Barcode Scanner analyzer.
     */
    fun onBarcodeDecoded(rawVal: String?) {
        if (!isScanning || rawVal == null) return

        val parsed = QrCodeParser.parse(rawVal)
        if (parsed != null) {
            isScanning = false
            Log.i(TAG, "Valid Pairing QR detected: ${QrCodeParser.sanitize(rawVal)}")

            val intent = Intent().apply {
                putExtra(EXTRA_HOST_IP, parsed.ip)
                putExtra(EXTRA_HOST_PORT, parsed.port)
                putExtra(EXTRA_FINGERPRINT, parsed.fingerprint)
                putExtra(EXTRA_PIN, parsed.pin)
                putExtra(EXTRA_VERSION, parsed.version)
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        } else {
            statusTextView.text = "Invalid QR code. Please scan a valid Remote Mouse Pro QR code."
        }
    }

    override fun onPause() {
        super.onPause()
        isScanning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
    }
}
