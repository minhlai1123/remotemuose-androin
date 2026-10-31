package com.remotemouse.input

import com.remotemouse.network.RmppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureHandlerTest {

    private lateinit var client: RmppClient
    private lateinit var gestureHandler: GestureHandler

    @Before
    fun setUp() {
        client = RmppClient()
        gestureHandler = GestureHandler(client)
    }

    @Test
    fun testDefaultRightHandedButtonMapping() {
        gestureHandler.isLeftHanded = false
        // Normal right-handed: Primary is Left Click (1), Secondary is Right Click (2)
        assertEquals(1.toByte(), gestureHandler.primaryButtonId)
        assertEquals(2.toByte(), gestureHandler.secondaryButtonId)
    }

    @Test
    fun testLeftHandedModeButtonMapping() {
        gestureHandler.isLeftHanded = true
        // Left-handed mode: Primary is Right Click (2), Secondary is Left Click (1)
        assertEquals(2.toByte(), gestureHandler.primaryButtonId)
        assertEquals(1.toByte(), gestureHandler.secondaryButtonId)
    }

    @Test
    fun testNaturalScrollToggle() {
        assertFalse(gestureHandler.isNaturalScroll)
        gestureHandler.isNaturalScroll = true
        assertTrue(gestureHandler.isNaturalScroll)
    }

    @Test
    fun testSensitivityAdjustmentThroughHandler() {
        gestureHandler.ballistics.sensitivity = 2.0f
        assertEquals(2.0f, gestureHandler.ballistics.sensitivity, 0.001f)

        gestureHandler.ballistics.sensitivity = 0.2f
        assertEquals(0.5f, gestureHandler.ballistics.sensitivity, 0.001f)
    }

    @Test
    fun testCancelInertiaDoesNotThrow() {
        gestureHandler.cancelInertia()
        // Must succeed safely without exception
        assertTrue(true)
    }
}
