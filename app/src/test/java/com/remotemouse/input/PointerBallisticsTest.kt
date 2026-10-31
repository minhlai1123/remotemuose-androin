package com.remotemouse.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PointerBallisticsTest {

    private lateinit var ballistics: PointerBallistics

    @Before
    fun setUp() {
        ballistics = PointerBallistics(1.0f)
    }

    @Test
    fun testZeroDisplacementProducesZero() {
        val (dx, dy) = ballistics.computeDisplacement(0f, 0f)
        assertEquals(0.toShort(), dx)
        assertEquals(0.toShort(), dy)
    }

    @Test
    fun testLowVelocityPixelPerfectPrecision() {
        // 1px in 16ms -> v = 62.5 px/s (< 100 px/s threshold)
        val (dx, dy) = ballistics.computeDisplacement(1f, 0f, 0.016f)
        assertEquals(1.toShort(), dx)
        assertEquals(0.toShort(), dy)

        // Acceleration factor must be exactly 1.0f
        val accel = ballistics.calculateAcceleration(62.5f)
        assertEquals(1.0f, accel, 0.001f)
    }

    @Test
    fun testMediumVelocityMonotonicAcceleration() {
        val accel1 = ballistics.calculateAcceleration(500f)
        val accel2 = ballistics.calculateAcceleration(1000f)

        assertTrue(accel1 > 1.0f)
        assertTrue(accel2 > accel1)
        assertTrue(accel2 < 2.8f)

        val (dx1, _) = ballistics.computeDisplacement(10f, 0f, 0.016f)
        val (dx2, _) = ballistics.computeDisplacement(20f, 0f, 0.016f)
        // Non-linear response: dx2 / dx1 > 2.0
        assertTrue(dx2.toFloat() / dx1.toFloat() > 2.0f)
    }

    @Test
    fun testHighVelocityCappedAtMax() {
        val accel1500 = ballistics.calculateAcceleration(1500f)
        val accel3000 = ballistics.calculateAcceleration(3000f)

        assertEquals(2.8f, accel1500, 0.001f)
        assertEquals(2.8f, accel3000, 0.001f)

        // 50px in 16ms -> v = 3125 px/s -> 50 * 2.8 = 140
        val (dx, dy) = ballistics.computeDisplacement(50f, 0f, 0.016f)
        assertEquals(140.toShort(), dx)
        assertEquals(0.toShort(), dy)
    }

    @Test
    fun testSensitivityScaling() {
        ballistics.sensitivity = 0.5f
        val (dxLow, _) = ballistics.computeDisplacement(10f, 0f, 0.016f)

        ballistics.sensitivity = 1.0f
        val (dxBase, _) = ballistics.computeDisplacement(10f, 0f, 0.016f)

        ballistics.sensitivity = 2.5f
        val (dxHigh, _) = ballistics.computeDisplacement(10f, 0f, 0.016f)

        assertTrue(dxBase > dxLow)
        assertTrue(dxHigh > dxBase)
        assertEquals(5.0f, dxHigh.toFloat() / dxLow.toFloat(), 1.0f)
    }

    @Test
    fun testSensitivityClamping() {
        ballistics.sensitivity = 0.1f
        assertEquals(0.5f, ballistics.sensitivity, 0.001f)

        ballistics.sensitivity = 10.0f
        assertEquals(3.0f, ballistics.sensitivity, 0.001f)

        ballistics.sensitivity = Float.NaN
        assertEquals(1.0f, ballistics.sensitivity, 0.001f)
    }

    @Test
    fun testLargeInputClampingToShort() {
        val (dx, dy) = ballistics.computeDisplacement(50000f, -50000f, 0.016f)
        assertEquals(Short.MAX_VALUE, dx)
        assertEquals(Short.MIN_VALUE, dy)
    }

    @Test
    fun testInvalidInputsDoNotCrash() {
        val (dx1, dy1) = ballistics.computeDisplacement(Float.NaN, 10f)
        assertEquals(0.toShort(), dx1)
        assertEquals(0.toShort(), dy1)

        val (dx2, dy2) = ballistics.computeDisplacement(10f, Float.POSITIVE_INFINITY)
        assertEquals(0.toShort(), dx2)
        assertEquals(0.toShort(), dy2)

        val (dx3, dy3) = ballistics.computeDisplacement(10f, 10f, -1.0f)
        assertTrue(dx3 != 0.toShort())
    }
}
