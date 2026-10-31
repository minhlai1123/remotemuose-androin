package com.remotemouse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.remotemouse.input.GestureHandler
import com.remotemouse.input.KeyboardManager
import com.remotemouse.network.ConnectionStatus
import com.remotemouse.network.RmppClient
import com.remotemouse.security.SecureTokenStorage
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.UUID

class MainActivity : Activity() {

    private val clientUuid = UUID.randomUUID().toString()
    private lateinit var client: RmppClient
    private lateinit var tokenStorage: SecureTokenStorage
    private lateinit var gestureHandler: GestureHandler
    private lateinit var keyboardManager: KeyboardManager

    private lateinit var tvStatus: TextView
    private lateinit var etHostIp: EditText
    private lateinit var etPin: EditText
    private lateinit var etFingerprint: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var touchpadView: View
    private lateinit var btnLeftClick: Button
    private lateinit var btnRightClick: Button

    private lateinit var tvSensitivityLabel: TextView
    private lateinit var sbSensitivity: SeekBar
    private lateinit var cbNaturalScroll: CheckBox
    private lateinit var cbLeftHanded: CheckBox

    // Phase 3.5 Keyboard UI components
    private lateinit var btnToggleKeyboard: Button
    private lateinit var btnModCtrl: Button
    private lateinit var btnModAlt: Button
    private lateinit var btnModShift: Button
    private lateinit var btnModWin: Button
    private lateinit var btnKeyEsc: Button
    private lateinit var btnKeyTab: Button
    private lateinit var btnKeyEnter: Button
    private lateinit var btnKeyBackspace: Button
    private lateinit var btnKeyDel: Button
    private lateinit var btnKeyF5: Button
    private lateinit var btnKeyF11: Button
    private lateinit var etImeBuffer: EditText

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Throwable) {}
        setContentView(R.layout.activity_main)

        tokenStorage = SecureTokenStorage(this)

        tvStatus = findViewById(R.id.tvStatus)
        etHostIp = findViewById(R.id.etHostIp)
        etPin = findViewById(R.id.etPin)
        etFingerprint = findViewById(R.id.etFingerprint)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        touchpadView = findViewById(R.id.touchpadView)
        btnLeftClick = findViewById(R.id.btnLeftClick)
        btnRightClick = findViewById(R.id.btnRightClick)

        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel)
        sbSensitivity = findViewById(R.id.sbSensitivity)
        cbNaturalScroll = findViewById(R.id.cbNaturalScroll)
        cbLeftHanded = findViewById(R.id.cbLeftHanded)

        // Phase 3.5 Keyboard UI View Binding
        btnToggleKeyboard = findViewById(R.id.btnToggleKeyboard)
        btnModCtrl = findViewById(R.id.btnModCtrl)
        btnModAlt = findViewById(R.id.btnModAlt)
        btnModShift = findViewById(R.id.btnModShift)
        btnModWin = findViewById(R.id.btnModWin)
        btnKeyEsc = findViewById(R.id.btnKeyEsc)
        btnKeyTab = findViewById(R.id.btnKeyTab)
        btnKeyEnter = findViewById(R.id.btnKeyEnter)
        btnKeyBackspace = findViewById(R.id.btnKeyBackspace)
        btnKeyDel = findViewById(R.id.btnKeyDel)
        btnKeyF5 = findViewById(R.id.btnKeyF5)
        btnKeyF11 = findViewById(R.id.btnKeyF11)
        etImeBuffer = findViewById(R.id.etImeBuffer)

        client = RmppClient(
            onStatusChanged = { status, error ->
                runOnUiThread {
                    if (status != ConnectionStatus.CONNECTED_CONTROLLER) {
                        gestureHandler.cancelInertia()
                        if (::keyboardManager.isInitialized) {
                            keyboardManager.releaseAllModifiers()
                        }
                    }
                    when (status) {
                        ConnectionStatus.DISCONNECTED -> {
                            tvStatus.text = "Status: Disconnected"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                        ConnectionStatus.CONNECTING -> {
                            tvStatus.text = "Status: Connecting TCP..."
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                        }
                        ConnectionStatus.TLS_HANDSHAKE -> {
                            tvStatus.text = "Status: TLS 1.3 & Fingerprint Pinning..."
                        }
                        ConnectionStatus.AUTHENTICATING -> {
                            tvStatus.text = "Status: Authenticating Session..."
                        }
                        ConnectionStatus.CONNECTED_CONTROLLER -> {
                            tvStatus.text = "Status: Connected (Active Controller)"
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                        }
                        ConnectionStatus.CONNECTED_PASSIVE -> {
                            tvStatus.text = "Status: Connected (Passive Standby)"
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                        }
                        ConnectionStatus.RECONNECT_WAIT -> {
                            tvStatus.text = "Status: Reconnecting in background..."
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                        }
                        ConnectionStatus.PAIRING_REQUIRED -> {
                            tvStatus.text = "Status: Pairing Required (Re-enter PIN)"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                        ConnectionStatus.ERROR -> {
                            tvStatus.text = "Status: Error - ${error ?: "Unknown"}"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                    }
                }
            },
            onPairingTokenReceived = { serverHost, token ->
                val fp = etFingerprint.text.toString().trim()
                tokenStorage.savePairing(serverHost, clientUuid, token, fp)
            }
        )

        btnConnect.setOnClickListener {
            val ip = etHostIp.text.toString().trim()
            val pin = etPin.text.toString().trim()
            val fp = etFingerprint.text.toString().trim()

            if (ip.isEmpty()) {
                tvStatus.text = "Error: Host IP is required"
                return@setOnClickListener
            }

            // Check if saved pairing exists for auto-reconnect
            val savedPairing = tokenStorage.getPairing(ip)
            if (savedPairing != null) {
                val effectiveFp = if (fp.isNotEmpty()) fp else savedPairing.hostFingerprint
                if (effectiveFp.isNotEmpty()) {
                    client.connectWithToken(ip, 54321, savedPairing.clientUuid, savedPairing.pairingToken, effectiveFp)
                    return@setOnClickListener
                }
            }

            // Fallback to initial PIN pairing
            if (pin.length != 6) {
                tvStatus.text = "Invalid PIN (must be 6 digits for first-time pairing)"
                return@setOnClickListener
            }
            if (fp.isEmpty()) {
                tvStatus.text = "Error: Host certificate fingerprint is mandatory"
                return@setOnClickListener
            }
            client.connect(ip, 54321, clientUuid, pin, fp)
        }

        gestureHandler = GestureHandler(client).apply {
            loadPreferences(this@MainActivity)
        }

        // Initialize Sensitivity Slider [0.5x, 3.0x] (step 0.1x: progress 0..25)
        val initialSens = gestureHandler.ballistics.sensitivity
        val initialProgress = ((initialSens - 0.5f) / 0.1f).toInt().coerceIn(0, 25)
        sbSensitivity.progress = initialProgress
        tvSensitivityLabel.text = String.format("Sensitivity: %.1fx", initialSens)

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSens = 0.5f + (progress * 0.1f)
                gestureHandler.ballistics.sensitivity = newSens
                tvSensitivityLabel.text = String.format("Sensitivity: %.1fx", newSens)
                gestureHandler.savePreferences(this@MainActivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Initialize Natural Scroll Toggle
        cbNaturalScroll.isChecked = gestureHandler.isNaturalScroll
        cbNaturalScroll.setOnCheckedChangeListener { _, isChecked ->
            gestureHandler.isNaturalScroll = isChecked
            gestureHandler.savePreferences(this@MainActivity)
        }

        // Initialize Left-Handed Mode Toggle
        fun updateClickButtonLabels(leftHanded: Boolean) {
            if (leftHanded) {
                btnLeftClick.text = "Right Click (Secondary)"
                btnRightClick.text = "Left Click (Primary)"
            } else {
                btnLeftClick.text = "Left Click (Primary)"
                btnRightClick.text = "Right Click (Secondary)"
            }
        }
        cbLeftHanded.isChecked = gestureHandler.isLeftHanded
        updateClickButtonLabels(gestureHandler.isLeftHanded)
        cbLeftHanded.setOnCheckedChangeListener { _, isChecked ->
            gestureHandler.isLeftHanded = isChecked
            updateClickButtonLabels(isChecked)
            gestureHandler.savePreferences(this@MainActivity)
        }

        btnDisconnect.setOnClickListener {
            gestureHandler.cancelInertia()
            client.disconnect()
        }

        btnLeftClick.setOnClickListener {
            gestureHandler.onPrimaryButtonClick()
        }

        btnRightClick.setOnClickListener {
            gestureHandler.onSecondaryButtonClick()
        }

        touchpadView.setOnTouchListener { _, event ->
            gestureHandler.onTouchEvent(event)
        }

        // Initialize KeyboardManager & Sticky Modifiers
        keyboardManager = KeyboardManager(client) { state ->
            runOnUiThread {
                val activeColor = Color.parseColor("#0284C7")
                val idleColor = Color.parseColor("#334155")
                btnModCtrl.setBackgroundColor(if (state.isCtrlActive) activeColor else idleColor)
                btnModAlt.setBackgroundColor(if (state.isAltActive) activeColor else idleColor)
                btnModShift.setBackgroundColor(if (state.isShiftActive) activeColor else idleColor)
                btnModWin.setBackgroundColor(if (state.isWinActive) activeColor else idleColor)
            }
        }

        btnModCtrl.setOnClickListener { keyboardManager.toggleCtrl() }
        btnModAlt.setOnClickListener { keyboardManager.toggleAlt() }
        btnModShift.setOnClickListener { keyboardManager.toggleShift() }
        btnModWin.setOnClickListener { keyboardManager.toggleWin() }

        btnKeyEsc.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_ESCAPE) }
        btnKeyTab.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_TAB) }
        btnKeyEnter.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_RETURN) }
        btnKeyBackspace.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_BACK) }
        btnKeyDel.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_DELETE) }
        btnKeyF5.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_F5) }
        btnKeyF11.setOnClickListener { keyboardManager.sendKeyPress(KeyboardManager.VK_F11) }

        btnToggleKeyboard.setOnClickListener {
            etImeBuffer.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etImeBuffer, InputMethodManager.SHOW_IMPLICIT)
        }

        etImeBuffer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty() && count > 0) {
                    val textInserted = s.subSequence(start, start + count).toString()
                    keyboardManager.sendText(textInserted)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrEmpty()) {
                    etImeBuffer.removeTextChangedListener(this)
                    s.clear()
                    etImeBuffer.addTextChangedListener(this)
                }
            }
        })

        etImeBuffer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        keyboardManager.sendKeyPress(KeyboardManager.VK_BACK)
                        true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        keyboardManager.sendKeyPress(KeyboardManager.VK_RETURN)
                        true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        keyboardManager.sendKeyPress(KeyboardManager.VK_TAB)
                        true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        keyboardManager.sendKeyPress(KeyboardManager.VK_ESCAPE)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::gestureHandler.isInitialized) {
            gestureHandler.cancelInertia()
        }
        if (::keyboardManager.isInitialized) {
            keyboardManager.releaseAllModifiers()
        }
        client.disconnect()
    }
}
