package com.remotemouse.input

import com.remotemouse.network.RmppClient
import java.text.Normalizer

/**
 * KeyboardManager handles keyboard input, virtual key dispatching,
 * sticky modifiers state tracking, and Unicode text normalization for Android client.
 */
class KeyboardManager(
    private val client: RmppClient,
    private val onModifierStateChanged: ((ModifierState) -> Unit)? = null
) {
    data class ModifierState(
        val isCtrlActive: Boolean = false,
        val isAltActive: Boolean = false,
        val isShiftActive: Boolean = false,
        val isWinActive: Boolean = false
    )

    enum class TargetOs {
        WINDOWS,
        MACOS
    }

    var targetOs: TargetOs = TargetOs.WINDOWS
        set(value) {
            field = value
            notifyModifierChanged()
        }

    private var modifierState = ModifierState()

    val currentModifierState: ModifierState
        get() = modifierState

    companion object {
        // Standard Win32 Virtual-Key Codes
        const val VK_BACK: Short = 0x08
        const val VK_TAB: Short = 0x09
        const val VK_RETURN: Short = 0x0D
        const val VK_SHIFT: Short = 0x10
        const val VK_CONTROL: Short = 0x11
        const val VK_MENU: Short = 0x12       // Alt key
        const val VK_PAUSE: Short = 0x13
        const val VK_CAPITAL: Short = 0x14
        const val VK_ESCAPE: Short = 0x1B
        const val VK_SPACE: Short = 0x20
        const val VK_PRIOR: Short = 0x21      // Page Up
        const val VK_NEXT: Short = 0x22       // Page Down
        const val VK_END: Short = 0x23
        const val VK_HOME: Short = 0x24
        const val VK_LEFT: Short = 0x25
        const val VK_UP: Short = 0x26
        const val VK_RIGHT: Short = 0x27
        const val VK_DOWN: Short = 0x28
        const val VK_SNAPSHOT: Short = 0x2C   // Print Screen
        const val VK_INSERT: Short = 0x2D
        const val VK_DELETE: Short = 0x2E

        // Windows / Command Key
        const val VK_LWIN: Short = 0x5B
        const val VK_RWIN: Short = 0x5C

        // Function Keys F1 - F12
        const val VK_F1: Short = 0x70
        const val VK_F2: Short = 0x71
        const val VK_F3: Short = 0x72
        const val VK_F4: Short = 0x73
        const val VK_F5: Short = 0x74
        const val VK_F6: Short = 0x75
        const val VK_F7: Short = 0x76
        const val VK_F8: Short = 0x77
        const val VK_F9: Short = 0x78
        const val VK_F10: Short = 0x79
        const val VK_F11: Short = 0x7A
        const val VK_F12: Short = 0x7B

        /**
         * Normalizes a string to Unicode NFC (Canonical Composition).
         */
        fun normalizeToNfc(text: String): String {
            return Normalizer.normalize(text, Normalizer.Form.NFC)
        }
    }

    /**
     * Sends a full text string to the host with NFC normalization.
     * Safe logging: Never logs keystroke text.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeToNfc(text)
        client.sendTextInput(normalized)
    }

    /**
     * Sends a single Virtual Key tap (down followed by up).
     */
    fun sendKeyPress(vkCode: Short) {
        client.sendKeyClick(vkCode)
    }

    /**
     * Toggles the Ctrl modifier.
     */
    fun toggleCtrl(): Boolean {
        val newState = !modifierState.isCtrlActive
        modifierState = modifierState.copy(isCtrlActive = newState)
        if (newState) {
            client.sendKeyDown(VK_CONTROL)
        } else {
            client.sendKeyUp(VK_CONTROL)
        }
        notifyModifierChanged()
        return newState
    }

    /**
     * Toggles the Alt modifier (Option on macOS).
     */
    fun toggleAlt(): Boolean {
        val newState = !modifierState.isAltActive
        modifierState = modifierState.copy(isAltActive = newState)
        if (newState) {
            client.sendKeyDown(VK_MENU)
        } else {
            client.sendKeyUp(VK_MENU)
        }
        notifyModifierChanged()
        return newState
    }

    /**
     * Toggles the Shift modifier.
     */
    fun toggleShift(): Boolean {
        val newState = !modifierState.isShiftActive
        modifierState = modifierState.copy(isShiftActive = newState)
        if (newState) {
            client.sendKeyDown(VK_SHIFT)
        } else {
            client.sendKeyUp(VK_SHIFT)
        }
        notifyModifierChanged()
        return newState
    }

    /**
     * Toggles the Windows / Super modifier (Cmd on macOS).
     */
    fun toggleWin(): Boolean {
        val newState = !modifierState.isWinActive
        modifierState = modifierState.copy(isWinActive = newState)
        if (newState) {
            client.sendKeyDown(VK_LWIN)
        } else {
            client.sendKeyUp(VK_LWIN)
        }
        notifyModifierChanged()
        return newState
    }

    /**
     * Releases all currently held sticky modifiers.
     */
    fun releaseAllModifiers() {
        if (modifierState.isCtrlActive) {
            client.sendKeyUp(VK_CONTROL)
        }
        if (modifierState.isAltActive) {
            client.sendKeyUp(VK_MENU)
        }
        if (modifierState.isShiftActive) {
            client.sendKeyUp(VK_SHIFT)
        }
        if (modifierState.isWinActive) {
            client.sendKeyUp(VK_LWIN)
        }
        modifierState = ModifierState()
        notifyModifierChanged()
    }

    /**
     * Returns OS-adaptive labels for modifiers.
     */
    fun getCtrlLabel(): String = if (targetOs == TargetOs.MACOS) "Control" else "Ctrl"
    fun getAltLabel(): String = if (targetOs == TargetOs.MACOS) "Option" else "Alt"
    fun getShiftLabel(): String = "Shift"
    fun getWinLabel(): String = if (targetOs == TargetOs.MACOS) "Cmd" else "Win"

    private fun notifyModifierChanged() {
        onModifierStateChanged?.invoke(modifierState)
    }
}
