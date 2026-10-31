package com.remotemouse.input

import android.content.Context
import android.view.MotionEvent
import com.remotemouse.network.RmppClient
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touchpad Gesture Handler for Android Client.
 * Implements 1-finger mouse navigation with Pointer Ballistics,
 * 2-finger vertical and horizontal scrolling with noise deadzone,
 * kinetic scroll inertia with bounded exponential decay,
 * and Left-Handed mode button mapping.
 */
class GestureHandler(
    private val client: RmppClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    companion object {
        private const val PREFS_NAME = "rmpp_input_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_LEFT_HANDED = "left_handed"

        // Gesture thresholds
        const val TAP_MAX_DURATION_MS = 300L
        const val TAP_MAX_DISTANCE_PX = 15.0f
        const val DRAG_THRESHOLD_PX = 12.0f
        const val SCROLL_DEADZONE_PX = 3.5f

        // Scroll step scaling (15px swipe produces standard 120 Win32 wheel delta)
        const val SCROLL_SCALE_FACTOR = 8.0f

        // Inertia parameters
        const val INERTIA_MIN_VELOCITY = 80.0f   // px/s to trigger inertia
        const val INERTIA_STOP_VELOCITY = 15.0f  // px/s to terminate inertia
        const val INERTIA_FRICTION = 0.91f       // Decay factor per 16ms frame
        const val INERTIA_FRAME_MS = 16L         // 60Hz loop interval
        const val INERTIA_MAX_DURATION_MS = 1500L
    }

    val ballistics = PointerBallistics()

    var isNaturalScroll: Boolean = false
    var isLeftHanded: Boolean = false

    // Single-finger tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTimeMs = 0L
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var downTimeMs = 0L
    private var isDragging = false

    // Two-finger tracking
    private var isTwoFingerGesture = false
    private var lastScrollMidX = 0f
    private var lastScrollMidY = 0f
    private var lastScrollTimeMs = 0L
    private var scrollVelocityX = 0f
    private var scrollVelocityY = 0f

    // Inertia job
    private var inertiaJob: Job? = null

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ballistics.sensitivity = prefs.getFloat(KEY_SENSITIVITY, PointerBallistics.DEFAULT_SENSITIVITY)
        isNaturalScroll = prefs.getBoolean(KEY_NATURAL_SCROLL, false)
        isLeftHanded = prefs.getBoolean(KEY_LEFT_HANDED, false)
    }

    fun savePreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_SENSITIVITY, ballistics.sensitivity)
            .putBoolean(KEY_NATURAL_SCROLL, isNaturalScroll)
            .putBoolean(KEY_LEFT_HANDED, isLeftHanded)
            .apply()
    }

    /**
     * Primary click button ID: 1 (Left) in right-handed mode, 2 (Right) in left-handed mode.
     */
    val primaryButtonId: Byte
        get() = if (isLeftHanded) 2 else 1

    /**
     * Secondary click button ID: 2 (Right) in right-handed mode, 1 (Left) in left-handed mode.
     */
    val secondaryButtonId: Byte
        get() = if (isLeftHanded) 1 else 2

    fun onPrimaryButtonClick() {
        client.sendMouseClick(primaryButtonId, 1)
    }

    fun onSecondaryButtonClick() {
        client.sendMouseClick(secondaryButtonId, 1)
    }

    fun cancelInertia() {
        inertiaJob?.cancel()
        inertiaJob = null
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val now = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any ongoing kinetic scroll inertia when user touches down
                cancelInertia()

                lastTouchX = event.x
                lastTouchY = event.y
                lastTouchTimeMs = now
                downTouchX = event.x
                downTouchY = event.y
                downTimeMs = now
                isDragging = false
                isTwoFingerGesture = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    cancelInertia()
                    isTwoFingerGesture = true
                    isDragging = false

                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    lastScrollMidX = midX
                    lastScrollMidY = midY
                    lastScrollTimeMs = now
                    scrollVelocityX = 0f
                    scrollVelocityY = 0f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && event.pointerCount >= 2) {
                    // Two-finger scroll gesture
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dt = maxOf(0.004f, (now - lastScrollTimeMs) / 1000f)

                    val rawDx = midX - lastScrollMidX
                    val rawDy = midY - lastScrollMidY

                    // Filter resting finger jitter
                    if (abs(rawDx) >= SCROLL_DEADZONE_PX || abs(rawDy) >= SCROLL_DEADZONE_PX) {
                        scrollVelocityX = rawDx / dt
                        scrollVelocityY = rawDy / dt

                        // Determine scroll deltas
                        var vWheel = (rawDy * SCROLL_SCALE_FACTOR).roundToInt()
                        var hWheel = (rawDx * SCROLL_SCALE_FACTOR).roundToInt()

                        // Natural scrolling vs Traditional:
                        // In traditional Windows wheel: positive vWheel = scroll up (content moves down)
                        // In natural mode: swipe up scrolls down
                        if (!isNaturalScroll) {
                            // Traditional: swipe up (negative dy) scrolls down (negative vwheel)
                            vWheel = (rawDy * SCROLL_SCALE_FACTOR).roundToInt()
                            hWheel = (rawDx * SCROLL_SCALE_FACTOR).roundToInt()
                        } else {
                            // Natural: inverted
                            vWheel = -(rawDy * SCROLL_SCALE_FACTOR).roundToInt()
                            hWheel = -(rawDx * SCROLL_SCALE_FACTOR).roundToInt()
                        }

                        val clampedV = maxOf(-32768, minOf(32767, vWheel)).toShort()
                        val clampedH = maxOf(-32768, minOf(32767, hWheel)).toShort()

                        if (clampedV != 0.toShort() || clampedH != 0.toShort()) {
                            client.sendMouseScroll(clampedH, clampedV)
                        }

                        lastScrollMidX = midX
                        lastScrollMidY = midY
                        lastScrollTimeMs = now
                    }
                    return true
                }

                if (!isTwoFingerGesture && event.pointerCount == 1) {
                    // Single finger mouse movement
                    val rawDx = event.x - lastTouchX
                    val rawDy = event.y - lastTouchY
                    val dt = maxOf(0.004f, (now - lastTouchTimeMs) / 1000f)

                    val distFromDown = hypot((event.x - downTouchX).toDouble(), (event.y - downTouchY).toDouble())
                    if (distFromDown > DRAG_THRESHOLD_PX) {
                        isDragging = true
                    }

                    if (rawDx != 0f || rawDy != 0f) {
                        val (accelDx, accelDy) = ballistics.computeDisplacement(rawDx, rawDy, dt)
                        if (accelDx != 0.toShort() || accelDy != 0.toShort()) {
                            client.sendMouseMove(accelDx, accelDy)
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                        lastTouchTimeMs = now
                    }
                    return true
                }
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFingerGesture && event.pointerCount == 2) {
                    // Releasing 2 fingers -> start kinetic scroll inertia if velocity is high
                    startInertiaDecay(scrollVelocityX, scrollVelocityY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isTwoFingerGesture) {
                    startInertiaDecay(scrollVelocityX, scrollVelocityY)
                    isTwoFingerGesture = false
                    return true
                }

                val duration = now - downTimeMs
                val totalDistance = hypot((event.x - downTouchX).toDouble(), (event.y - downTouchY).toDouble())

                // Clean Tap detected -> Send primary click (button 1 or button 2 depending on Left-Handed Mode)
                if (!isDragging && duration < TAP_MAX_DURATION_MS && totalDistance < TAP_MAX_DISTANCE_PX) {
                    client.sendMouseClick(primaryButtonId, 1)
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelInertia()
                isDragging = false
                isTwoFingerGesture = false
                return true
            }

            else -> return false
        }
    }

    private fun startInertiaDecay(initialVx: Float, initialVy: Float) {
        cancelInertia()

        val speed = hypot(initialVx.toDouble(), initialVy.toDouble()).toFloat()
        if (speed < INERTIA_MIN_VELOCITY) {
            return
        }

        inertiaJob = coroutineScope.launch {
            var vx = initialVx
            var vy = initialVy
            val startTime = System.currentTimeMillis()

            while (isActive) {
                delay(INERTIA_FRAME_MS)

                val elapsed = System.currentTimeMillis() - startTime
                val currentSpeed = hypot(vx.toDouble(), vy.toDouble()).toFloat()

                if (currentSpeed < INERTIA_STOP_VELOCITY || elapsed > INERTIA_MAX_DURATION_MS) {
                    break
                }

                // Exponential decay by friction factor
                vx *= INERTIA_FRICTION
                vy *= INERTIA_FRICTION

                val dt = INERTIA_FRAME_MS / 1000f
                val stepX = vx * dt
                val stepY = vy * dt

                var vWheel = (stepY * SCROLL_SCALE_FACTOR).roundToInt()
                var hWheel = (stepX * SCROLL_SCALE_FACTOR).roundToInt()

                if (isNaturalScroll) {
                    vWheel = -vWheel
                    hWheel = -hWheel
                }

                val clampedV = maxOf(-32768, minOf(32767, vWheel)).toShort()
                val clampedH = maxOf(-32768, minOf(32767, hWheel)).toShort()

                if (clampedV != 0.toShort() || clampedH != 0.toShort()) {
                    client.sendMouseScroll(clampedH, clampedV)
                }
            }
        }
    }
}
