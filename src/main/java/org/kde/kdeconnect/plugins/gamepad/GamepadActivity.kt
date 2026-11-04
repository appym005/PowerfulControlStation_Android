/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.gamepad

import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Standalone Gamepad module (not part of Control Deck).
 * Android View receives simultaneous pointers directly, so two sticks and several buttons
 * work on phones and tablets without competing Compose gesture recognizers.
 */
class GamepadActivity : AppCompatActivity() {
    companion object { const val EXTRA_DEVICE_ID = "deviceId" }
    private lateinit var transport: GamepadTransport
    private lateinit var surface: GamepadSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        WindowCompat.setDecorFitsSystemWindows(window, false)

        surface = GamepadSurfaceView(this, { transport.updateState(it) }, { showHostDialog() })
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bounds = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        transport = GamepadTransport(this) {
            surface.connectionStatus = it
            surface.invalidate()
        }
    }

    private fun showHostDialog() {
        val field = EditText(this).apply {
            hint = "Windows PC IP (leave blank for discovery)"
            setSingleLine()
            setText(transport.getManualIp())
            setPadding(35, 18, 35, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("Gamepad connection")
            .setMessage("The Windows companion must be running on the same local network. Tap the status line to configure a manual address if discovery fails.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> transport.setManualIp(field.text.toString()) }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (::surface.isInitialized) surface.clearTouches()
        if (::transport.isInitialized) transport.updateState(PadState())
    }

    override fun onDestroy() {
        if (::transport.isInitialized) transport.close()
        super.onDestroy()
    }
}

internal class GamepadSurfaceView(
    context: android.content.Context,
    private val onState: (PadState) -> Unit,
    private val onConnectionTap: () -> Unit
) : View(context) {
    var connectionStatus: String = "Starting…"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val owners = mutableMapOf<Int, String>()
    private var state = PadState()
    private val ink = Color.rgb(232, 238, 247)
    private val muted = Color.rgb(139, 155, 180)
    private val body = Color.rgb(31, 39, 56)
    private val stroke = Color.rgb(76, 92, 116)
    private val bright = Color.rgb(58, 117, 193)

    private fun radius(): Float = height * 0.125f
    private fun atX(x: Float): Float = width * x
    private fun atY(y: Float): Float = height * y
    private fun distance(x: Float, y: Float, nx: Float, ny: Float) =
        hypot(x - atX(nx), y - atY(ny))
    private fun color(v: Int) { paint.color = v; paint.style = Paint.Style.FILL }
    private fun round(c: Canvas, x: Float, y: Float, w: Float, h: Float, fill: Int, corner: Float = h * .35f) {
        color(fill)
        c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), corner, corner, paint)
    }
    private fun disk(c: Canvas, x: Float, y: Float, r: Float, fill: Int) {
        color(fill); c.drawCircle(x, y, r, paint)
    }
    private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, colorValue: Int = ink) {
        color(colorValue)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = size
        c.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun face(c: Canvas, key: String, text: String, x: Float, y: Float, tint: Int, mask: Int) {
        val selected = state.buttons and mask != 0
        val r = height * .073f
        disk(c, atX(x), atY(y), r, if (selected) tint else body)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = height * .008f
        c.drawCircle(atX(x), atY(y), r, paint)
        paint.style = Paint.Style.FILL
        label(c, text, atX(x), atY(y), height * .073f, if (selected) Color.WHITE else tint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawColor(Color.rgb(13, 19, 31))
        val h = height.toFloat()
        color(muted)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = h * .039f
        canvas.drawText("GAMEPAD  ·  XBOX 360", atX(.025f), atY(.055f), paint)
        paint.textSize = h * .029f
        canvas.drawText(connectionStatus.take(56) + "   (tap to configure)", atX(.025f), atY(.095f), paint)

        // Shoulder buttons and digital / analog triggers.
        listOf(Triple("LT", .20f, .17f), Triple("LB", .20f, .285f),
            Triple("RT", .80f, .17f), Triple("RB", .80f, .285f)).forEach { (name, x, y) ->
            val pressed = when (name) {
                "LT" -> state.lt > 0f
                "RT" -> state.rt > 0f
                "LB" -> state.buttons and 0x0100 != 0
                else -> state.buttons and 0x0200 != 0
            }
            round(canvas, atX(x), atY(y), h * .42f, h * .085f, if (pressed) bright else body)
            label(canvas, name, atX(x), atY(y), h * .052f)
        }

        // Joysticks: full analog with independent pointer ownership.
        drawStick(canvas, .19f, .58f, state.lx, state.ly, "L")
        drawStick(canvas, .64f, .78f, state.rx, state.ry, "R")

        // D-pad supports diagonals from one finger.
        val dx = .40f
        val dy = .76f
        val dr = h * .069f
        val dirs = listOf(
            Triple("▲", 0f, -1f), Triple("▼", 0f, 1f),
            Triple("◀", -1f, 0f), Triple("▶", 1f, 0f)
        )
        for ((i, item) in dirs.withIndex()) {
            val mask = intArrayOf(0x0001, 0x0002, 0x0004, 0x0008)[i]
            val cx = atX(dx) + item.second * dr * 1.15f
            val cy = atY(dy) + item.third * dr * 1.15f
            round(canvas, cx, cy, dr * 1.1f, dr * 1.1f,
                if (state.buttons and mask != 0) bright else body, h * .015f)
            label(canvas, item.first, cx, cy, h * .041f)
        }

        // Xbox-style face button diamond.
        face(canvas, "Y", "Y", .83f, .45f, Color.rgb(240, 200, 55), 0x8000)
        face(canvas, "X", "X", .765f, .57f, Color.rgb(75, 148, 230), 0x4000)
        face(canvas, "B", "B", .895f, .57f, Color.rgb(235, 90, 91), 0x2000)
        face(canvas, "A", "A", .83f, .69f, Color.rgb(93, 197, 105), 0x1000)

        drawSmall(canvas, "VIEW", .44f, .48f, 0x0020)
        drawSmall(canvas, "MENU", .57f, .48f, 0x0010)
        drawSmall(canvas, "XBOX", .50f, .20f, 0x0400)
        label(canvas, "L3", atX(.19f), atY(.77f), h * .031f, muted)
        label(canvas, "R3", atX(.64f), atY(.96f), h * .031f, muted)
    }

    private fun drawSmall(c: Canvas, text: String, x: Float, y: Float, flag: Int) {
        round(c, atX(x), atY(y), height * .18f, height * .072f,
            if (state.buttons and flag != 0) bright else body)
        label(c, text, atX(x), atY(y), height * .030f)
    }

    private fun drawStick(c: Canvas, nx: Float, ny: Float, vx: Float, vy: Float, name: String) {
        val r = radius()
        val cx = atX(nx)
        val cy = atY(ny)
        disk(c, cx, cy, r, Color.rgb(24, 33, 48))
        paint.color = stroke; paint.style = Paint.Style.STROKE
        paint.strokeWidth = height * .013f
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        disk(c, cx + vx * r * .65f, cy - vy * r * .65f, r * .58f,
            if (abs(vx) + abs(vy) > .01f) bright else body)
        label(c, name, cx + vx * r * .65f, cy - vy * r * .65f, r * .40f)
    }

    private fun hit(x: Float, y: Float): String? {
        val h = height.toFloat()
        if (y < atY(.105f)) return "settings"
        listOf(Triple("lt", .20f, .17f), Triple("lb", .20f, .285f),
            Triple("rt", .80f, .17f), Triple("rb", .80f, .285f)).forEach { (id, nx, ny) ->
            if (abs(x - atX(nx)) < h * .24f && abs(y - atY(ny)) < h * .050f) return id
        }
        val faces = listOf("y" to Pair(.83f, .45f), "x" to Pair(.765f, .57f),
            "b" to Pair(.895f, .57f), "a" to Pair(.83f, .69f))
        for ((name, pos) in faces) if (distance(x, y, pos.first, pos.second) < h * .091f) return name
        if (distance(x, y, .19f, .58f) < radius() * 1.35f) return "left"
        if (distance(x, y, .64f, .78f) < radius() * 1.35f) return "right"
        if (distance(x, y, .40f, .76f) < h * .20f) return "dpad"
        for ((id, pos) in listOf("view" to Pair(.44f, .48f),
            "menu" to Pair(.57f, .48f), "guide" to Pair(.50f, .20f))) {
            if (abs(x - atX(pos.first)) < h * .12f && abs(y - atY(pos.second)) < h * .046f) return id
        }
        return null
    }

    private fun axis(px: Float, py: Float, x: Float, y: Float): Pair<Float, Float> {
        val dx = (px - atX(x)) / radius()
        val dy = (atY(y) - py) / radius()
        val length = hypot(dx, dy)
        val factor = if (length <= 1f) 1f else 1f / length
        // A small dead zone prevents resting tremor on larger tablet displays.
        return if (length < .08f) 0f to 0f else (dx * factor) to (dy * factor)
    }

    private fun update(event: MotionEvent, excludedPointer: Int = -1) {
        var flags = 0
        var lx = 0f; var ly = 0f
        var rx = 0f; var ry = 0f
        var lt = 0f; var rt = 0f
        for (i in 0 until event.pointerCount) {
            if (event.getPointerId(i) == excludedPointer) continue
            val x = event.getX(i); val y = event.getY(i)
            when (owners[event.getPointerId(i)]) {
                "left" -> { val p = axis(x, y, .19f, .58f); lx = p.first; ly = p.second }
                "right" -> { val p = axis(x, y, .64f, .78f); rx = p.first; ry = p.second }
                "dpad" -> {
                    val dx = x - atX(.40f); val dy = y - atY(.76f)
                    val dead = height * .027f
                    if (dx < -dead) flags = flags or 0x0004
                    if (dx > dead) flags = flags or 0x0008
                    if (dy < -dead) flags = flags or 0x0001
                    if (dy > dead) flags = flags or 0x0002
                }
                "lt" -> lt = 1f
                "rt" -> rt = 1f
                "lb" -> flags = flags or 0x0100
                "rb" -> flags = flags or 0x0200
                "a" -> flags = flags or 0x1000
                "b" -> flags = flags or 0x2000
                "x" -> flags = flags or 0x4000
                "y" -> flags = flags or 0x8000
                "view" -> flags = flags or 0x0020
                "menu" -> flags = flags or 0x0010
                "guide" -> flags = flags or 0x0400
            }
        }
        state = PadState(flags, lx, ly, rx, ry, lt, rt)
        onState(state)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val target = hit(event.getX(index), event.getY(index))
                if (target == "settings") {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onConnectionTap()
                } else if (target != null) owners[event.getPointerId(index)] = target
                update(event)
            }
            MotionEvent.ACTION_MOVE -> update(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                owners.remove(id)
                update(event, id)
            }
            MotionEvent.ACTION_CANCEL -> clearTouches()
        }
        return true
    }

    fun clearTouches() {
        owners.clear()
        state = PadState()
        onState(state)
        invalidate()
    }
}
