package com.remotemouse.input

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Non-linear Pointer Ballistics & Sensitivity Calculator for Android Client.
 *
 * Architecture Decision (ADR-Phase-3.3):
 * The Mobile Client calculates acceleration and sensitivity curves locally
 * to ensure responsive, zero-latency cursor control without double acceleration.
 * The Desktop Host receives pixel-displacement (dx, dy) directly.
 */
class PointerBallistics(
    sensitivity: Float = DEFAULT_SENSITIVITY
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 1.0f
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3.0f

        // Velocity thresholds in pixels per second
        const val V_MIN = 100.0f  // Slow precision targeting below 100 px/s (1.0x acceleration)
        const val V_MAX = 1500.0f // Fast swipe threshold above 1500 px/s (capped at 2.8x)
        const val A_MAX = 2.8f    // Maximum acceleration multiplier
        const val EXPONENT = 1.35f

        const val DEFAULT_DT = 0.016f // 16.6ms for ~60Hz touch sampling
    }

    var sensitivity: Float = clampSensitivity(sensitivity)
        set(value) {
            field = clampSensitivity(value)
        }

    fun clampSensitivity(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT_SENSITIVITY
        return max(MIN_SENSITIVITY, min(MAX_SENSITIVITY, value))
    }

    fun calculateAcceleration(velocityPxS: Float): Float {
        if (velocityPxS <= V_MIN) return 1.0f
        if (velocityPxS >= V_MAX) return A_MAX

        val u = (velocityPxS - V_MIN) / (V_MAX - V_MIN)
        return 1.0f + (A_MAX - 1.0f) * u.pow(EXPONENT)
    }

    /**
     * Computes accelerated displacement (outDx, outDy) from raw touch displacement and time delta (dt in seconds).
     * Output is clamped to Short boundaries for RMPP CompactFrame serialization.
     */
    fun computeDisplacement(dx: Float, dy: Float, dt: Float = DEFAULT_DT): Pair<Short, Short> {
        if (dx == 0f && dy == 0f) return Pair(0, 0)
        if (dx.isNaN() || dy.isNaN() || dx.isInfinite() || dy.isInfinite()) return Pair(0, 0)

        val effectiveDt = if (dt <= 0f || dt.isNaN() || dt.isInfinite()) {
            DEFAULT_DT
        } else {
            max(0.004f, min(0.200f, dt))
        }

        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance == 0f) return Pair(0, 0)

        val velocity = distance / effectiveDt
        val accel = calculateAcceleration(velocity)
        val multiplier = accel * sensitivity

        val outDx = (dx * multiplier).roundToInt()
        val outDy = (dy * multiplier).roundToInt()

        val clampedDx = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), outDx)).toShort()
        val clampedDy = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), outDy)).toShort()

        return Pair(clampedDx, clampedDy)
    }
}
