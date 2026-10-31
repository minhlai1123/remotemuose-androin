package com.remotemouse.input

import com.remotemouse.network.ConnectionStatus
import com.remotemouse.network.RmppClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.Normalizer

class KeyboardManagerTest {

    private lateinit var client: RmppClient
    private lateinit var keyboardManager: KeyboardManager
    private var lastModifierState: KeyboardManager.ModifierState? = null

    @Before
    fun setUp() {
        client = RmppClient()
        keyboardManager = KeyboardManager(client) { state ->
            lastModifierState = state
        }
    }

    @Test
    fun testNfcNormalizationVietnamese() {
        // NFD decomposed form (letter followed by combining diacritical marks)
        val nfdString = Normalizer.normalize("Cộng hòa Xã hội Chủ nghĩa Việt Nam - 2026", Normalizer.Form.NFD)
        assertNotEquals(nfdString, Normalizer.normalize(nfdString, Normalizer.Form.NFC))

        // Normalizing via KeyboardManager must yield canonical NFC precomposed form
        val nfcResult = KeyboardManager.normalizeToNfc(nfdString)
        assertEquals(Normalizer.Form.NFC, if (Normalizer.isNormalized(nfcResult, Normalizer.Form.NFC)) Normalizer.Form.NFC else Normalizer.Form.NFD)

        // Verify specific Vietnamese letters
        val testWordNFD = "t" + "\u0069\u00ea\u0301\u0074" // t - i - e - circumflex/acute - t
        val testWordNFC = KeyboardManager.normalizeToNfc(testWordNFD)
        assertTrue(Normalizer.isNormalized(testWordNFC, Normalizer.Form.NFC))
    }

    @Test
    fun testVirtualKeyConstantsMatchWin32() {
        assertEquals(0x08.toShort(), KeyboardManager.VK_BACK)
        assertEquals(0x09.toShort(), KeyboardManager.VK_TAB)
        assertEquals(0x0D.toShort(), KeyboardManager.VK_RETURN)
        assertEquals(0x10.toShort(), KeyboardManager.VK_SHIFT)
        assertEquals(0x11.toShort(), KeyboardManager.VK_CONTROL)
        assertEquals(0x12.toShort(), KeyboardManager.VK_MENU)
        assertEquals(0x1B.toShort(), KeyboardManager.VK_ESCAPE)
        assertEquals(0x20.toShort(), KeyboardManager.VK_SPACE)
        assertEquals(0x2E.toShort(), KeyboardManager.VK_DELETE)
        assertEquals(0x5B.toShort(), KeyboardManager.VK_LWIN)
        assertEquals(0x70.toShort(), KeyboardManager.VK_F1)
        assertEquals(0x7B.toShort(), KeyboardManager.VK_F12)
    }

    @Test
    fun testStickyModifiersToggle() {
        assertFalse(keyboardManager.currentModifierState.isCtrlActive)

        // Toggle Ctrl ON
        val ctrl1 = keyboardManager.toggleCtrl()
        assertTrue(ctrl1)
        assertTrue(keyboardManager.currentModifierState.isCtrlActive)
        assertEquals(true, lastModifierState?.isCtrlActive)

        // Toggle Alt ON
        val alt1 = keyboardManager.toggleAlt()
        assertTrue(alt1)
        assertTrue(keyboardManager.currentModifierState.isAltActive)

        // Toggle Shift ON
        val shift1 = keyboardManager.toggleShift()
        assertTrue(shift1)
        assertTrue(keyboardManager.currentModifierState.isShiftActive)

        // Toggle Win ON
        val win1 = keyboardManager.toggleWin()
        assertTrue(win1)
        assertTrue(keyboardManager.currentModifierState.isWinActive)

        // Toggle Ctrl OFF
        val ctrl2 = keyboardManager.toggleCtrl()
        assertFalse(ctrl2)
        assertFalse(keyboardManager.currentModifierState.isCtrlActive)
    }

    @Test
    fun testReleaseAllModifiers() {
        keyboardManager.toggleCtrl()
        keyboardManager.toggleAlt()
        keyboardManager.toggleShift()
        keyboardManager.toggleWin()

        assertTrue(keyboardManager.currentModifierState.isCtrlActive)
        assertTrue(keyboardManager.currentModifierState.isAltActive)
        assertTrue(keyboardManager.currentModifierState.isShiftActive)
        assertTrue(keyboardManager.currentModifierState.isWinActive)

        // Release all
        keyboardManager.releaseAllModifiers()

        assertFalse(keyboardManager.currentModifierState.isCtrlActive)
        assertFalse(keyboardManager.currentModifierState.isAltActive)
        assertFalse(keyboardManager.currentModifierState.isShiftActive)
        assertFalse(keyboardManager.currentModifierState.isWinActive)
        assertEquals(false, lastModifierState?.isCtrlActive)
    }

    @Test
    fun testOsAdaptiveLabels() {
        keyboardManager.targetOs = KeyboardManager.TargetOs.WINDOWS
        assertEquals("Ctrl", keyboardManager.getCtrlLabel())
        assertEquals("Alt", keyboardManager.getAltLabel())
        assertEquals("Shift", keyboardManager.getShiftLabel())
        assertEquals("Win", keyboardManager.getWinLabel())

        keyboardManager.targetOs = KeyboardManager.TargetOs.MACOS
        assertEquals("Control", keyboardManager.getCtrlLabel())
        assertEquals("Option", keyboardManager.getAltLabel())
        assertEquals("Shift", keyboardManager.getShiftLabel())
        assertEquals("Cmd", keyboardManager.getWinLabel())
    }
}
