/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.preference.PreferenceManager
import org.kde.kdeconnect_tp.R
import kotlin.math.abs
import kotlin.math.pow

/** Independent gestures for the deck's trackpad: tap, double-tap and two-finger tap. */
internal class ControlDeckTrackpad(
    context: Context,
    private val plugin: MousePadPlugin
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var lastX = 0f
    private var lastY = 0f
    private var firstDown = 0L
    private var maxDistance = 0f
    private var hadTwoFingers = false
    private var doubleTapPending = false
    private var holdingLeft = false
    private var secondTapDistance = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!hadTwoFingers && !doubleTapPending && maxDistance < slop) {
                plugin.sendLeftClick()
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapPending = true
            secondTapDistance = 0f
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!hadTwoFingers && !holdingLeft) {
                plugin.sendSingleHold()
                holdingLeft = true
            }
        }
    })

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                firstDown = SystemClock.uptimeMillis()
                maxDistance = 0f
                hadTwoFingers = false
                secondTapDistance = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                hadTwoFingers = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !hadTwoFingers) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    maxDistance += abs(dx) + abs(dy)
                    if (doubleTapPending && !holdingLeft) {
                        secondTapDistance += abs(dx) + abs(dy)
                        if (secondTapDistance > slop) {
                            plugin.sendSingleHold()
                            holdingLeft = true
                        }
                    }
                    if (dx != 0f || dy != 0f) {
                        plugin.sendMouseDelta(dx * 1.2f, dy * 1.2f)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (hadTwoFingers && event.pointerCount == 2 &&
                    SystemClock.uptimeMillis() - firstDown <= ViewConfiguration.getTapTimeout() + 160L &&
                    maxDistance < slop * 2f
                ) {
                    plugin.sendRightClick()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (holdingLeft) {
                    plugin.sendSingleRelease()
                    holdingLeft = false
                } else if (doubleTapPending && !hadTwoFingers) {
                    if (secondTapDistance <= slop) plugin.sendDoubleClick()
                }
                doubleTapPending = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (holdingLeft) plugin.sendSingleRelease()
                holdingLeft = false
                doubleTapPending = false
            }
        }
        // GestureDetector runs for delayed single-tap and long-press recognition.
        if (event.pointerCount == 1 && !hadTwoFingers) gestures.onTouchEvent(event)
        return true
    }
}

/** Dedicated vertical scroll area, matching the existing Remote Input wheel preference. */
internal class ControlDeckScroll(
    context: Context,
    private val plugin: MousePadPlugin
) {
    private val direction: Int
    private val coefficient: Double
    private var previousY = 0f
    private var pending = 0.0

    init {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        direction = if (preferences.getBoolean(context.getString(R.string.mousepad_scroll_direction), false)) -1 else 1
        val sensitivity = preferences.getInt(context.getString(R.string.mousepad_scroll_sensitivity), 100)
        coefficient = (sensitivity.coerceAtLeast(1) / 100.0).pow(1.5)
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousY = event.y
                pending = 0.0
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    pending += (previousY - event.y) * coefficient
                    previousY = event.y
                    if (abs(pending) >= 2.5) {
                        plugin.sendScroll(0.0, direction * pending)
                        pending = 0.0
                    }
                } else {
                    previousY = event.y
                    pending = 0.0
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> pending = 0.0
        }
        return true
    }
}
