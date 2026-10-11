/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.ui.compose.KdeTheme
import kotlin.math.roundToInt

private data class DeckButton(val id: String, val label: String, val span: Int)

private enum class SensitivityControl { TRACKPAD, SCROLL }

private val defaultButtons = listOf(
    DeckButton("ctrl", "Ctrl", 1),
    DeckButton("task_view", "Win + Tab", 1),
    DeckButton("terminal", "Terminal", 2),
    DeckButton("switch", "Alt + Tab", 2)
)

class ControlDeckActivity : AppCompatActivity() {
    companion object { const val EXTRA_DEVICE_ID = "deviceId" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java)
            ?: run {
                Toast.makeText(this, "Remote input isn't available", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        val preferences = getSharedPreferences("control_deck", MODE_PRIVATE)
        val storageKey = "buttons_$deviceId"
        val keepAwakeKey = "keep_screen_on"
        val trackpadKey = "trackpad_sensitivity_$deviceId"
        val scrollKey = "scroll_sensitivity_$deviceId"
        val defaultScroll = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(getString(R.string.mousepad_scroll_sensitivity), 100)
            .coerceIn(25, 300)
        setScreenAwake(preferences.getBoolean(keepAwakeKey, true))

        setContent {
            KdeTheme(this) {
                val buttons = remember(deviceId) {
                    mutableStateListOf<DeckButton>().apply {
                        addAll(loadDeck(preferences.getString(storageKey, null)))
                    }
                }
                var editing by remember { mutableStateOf(false) }
                var selectedId by remember { mutableStateOf<String?>(null) }
                var ctrlArmed by remember { mutableStateOf(false) }
                var keepAwake by remember { mutableStateOf(preferences.getBoolean(keepAwakeKey, true)) }
                var trackpadSensitivity by remember(deviceId) {
                    mutableStateOf(preferences.getInt(trackpadKey, 100).coerceIn(25, 300))
                }
                var scrollSensitivity by remember(deviceId) {
                    mutableStateOf(preferences.getInt(scrollKey, defaultScroll).coerceIn(25, 300))
                }
                var sensitivityPopup by remember { mutableStateOf<SensitivityControl?>(null) }
                var keyboardView by remember { mutableStateOf<KeyListenerView?>(null) }
                val bounds = remember { mutableStateMapOf<String, Rect>() }
                var draggingId by remember { mutableStateOf<String?>(null) }
                var dragVector by remember { mutableStateOf(Offset.Zero) }
                var dragPointer by remember { mutableStateOf(Offset.Zero) }

                fun persist() {
                    val json = JSONArray()
                    buttons.forEach {
                        json.put(JSONObject().put("id", it.id).put("span", it.span))
                    }
                    preferences.edit().putString(storageKey, json.toString()).apply()
                }

                fun runButton(button: DeckButton) {
                    val withCtrl = ctrlArmed
                    if (button.id == "ctrl") {
                        ctrlArmed = !ctrlArmed
                        return
                    }
                    ctrlArmed = false
                    if (!plugin.isKeyboardEnabled) {
                        Toast.makeText(this, R.string.mousepad_keyboard_input_not_supported, Toast.LENGTH_SHORT).show()
                        return
                    }
                    val tab = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_TAB)
                    when (button.id) {
                        "task_view" -> plugin.sendDeckShortcut(specialKey = tab, superKey = true, ctrl = withCtrl)
                        "switch" -> plugin.sendDeckShortcut(specialKey = tab, alt = true, ctrl = withCtrl)
                        "terminal" -> {
                            // Win+R, then 'wt' (Windows Terminal), then Enter.
                            // No Windows-side custom command needs to be configured.
                            lifecycleScope.launch {
                                plugin.sendDeckShortcut(key = "r", superKey = true)
                                delay(350)
                                plugin.sendText("wt")
                                delay(130)
                                plugin.sendDeckShortcut(
                                    specialKey = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ENTER)
                                )
                            }
                        }
                    }
                }

                val context = LocalContext.current
                val trackpad = remember(plugin) { ControlDeckTrackpad(context, plugin) }
                val scroll = remember(plugin) { ControlDeckScroll(context, plugin) }
                SideEffect {
                    trackpad.sensitivityPercent = trackpadSensitivity
                    scroll.sensitivityPercent = scrollSensitivity
                }
                val imeHeight = with(LocalDensity.current) {
                    WindowInsets.ime.getBottom(this).toDp()
                }

                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color(0xFF161616))
                        .safeDrawingPadding() // Includes cutouts and rounded landscape corners.
                ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(0.215f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "CONTROL DECK",
                            color = Color(0xFFCFCFCF),
                            fontSize = 11.sp,
                            letterSpacing = 1.3.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(buttons, key = { it.id }, span = { GridItemSpan(it.span) }) { button ->
                                val isSelected = editing && selectedId == button.id
                                val isDragged = draggingId == button.id
                                val dragModifier = if (editing) {
                                    Modifier.pointerInput(button.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { touchOffset ->
                                                selectedId = button.id
                                                draggingId = button.id
                                                dragVector = Offset.Zero
                                                dragPointer = (bounds[button.id]?.topLeft ?: Offset.Zero) + touchOffset
                                            },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                dragVector += delta
                                                dragPointer += delta
                                            },
                                            onDragEnd = {
                                                val targetId = bounds.entries.firstOrNull { (id, rect) ->
                                                    id != draggingId && rect.contains(dragPointer)
                                                }?.key
                                                if (targetId != null) {
                                                    val from = buttons.indexOfFirst { it.id == draggingId }
                                                    val to = buttons.indexOfFirst { it.id == targetId }
                                                    if (from >= 0 && to >= 0 && from != to) {
                                                        val moving = buttons.removeAt(from)
                                                        buttons.add(to, moving)
                                                        persist()
                                                    }
                                                }
                                                draggingId = null
                                                dragVector = Offset.Zero
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragVector = Offset.Zero
                                            }
                                        )
                                    }
                                } else Modifier

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(78.dp)
                                        .onGloballyPositioned { bounds[button.id] = it.boundsInRoot() }
                                        .graphicsLayer {
                                            if (isDragged) {
                                                translationX = dragVector.x
                                                translationY = dragVector.y
                                                shadowElevation = 15f
                                            }
                                        }
                                        .then(dragModifier)
                                        .background(
                                            if (button.id == "ctrl" && ctrlArmed && !editing) Color(0xFF6A6A6A)
                                            else Color(0xFF2A2A2A),
                                            RoundedCornerShape(24.dp)
                                        )
                                        .border(
                                            if (isSelected) 2.dp else 0.dp,
                                            if (isSelected) Color.White else Color.Transparent,
                                            RoundedCornerShape(24.dp)
                                        )
                                        .clickable(enabled = !isDragged) {
                                            if (editing) selectedId = button.id else runButton(button)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    DeckButtonFace(button = button, selected = isSelected)
                                    if (isSelected) {
                                        // Just like resize handles in a visual editor: white pill grips
                                        // centered on the left and right edges of the selected outline.
                                        // Drag outward to grow to 2 cells; inward to shrink to 1.
                                        // A tap also toggles size for convenient one-handed editing.
                                        EdgeResizeHandle(
                                            leftEdge = true,
                                            span = button.span,
                                            modifier = Modifier.align(Alignment.CenterStart)
                                        ) { newSpan ->
                                            val at = buttons.indexOfFirst { it.id == button.id }
                                            if (at >= 0 && buttons[at].span != newSpan) {
                                                buttons[at] = buttons[at].copy(span = newSpan)
                                                persist()
                                            }
                                        }
                                        EdgeResizeHandle(
                                            leftEdge = false,
                                            span = button.span,
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                        ) { newSpan ->
                                            val at = buttons.indexOfFirst { it.id == button.id }
                                            if (at >= 0 && buttons[at].span != newSpan) {
                                                buttons[at] = buttons[at].copy(span = newSpan)
                                                persist()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SensitivityAction(
                                label = "DPI",
                                icon = R.drawable.control_deck_pointer_speed,
                                modifier = Modifier.weight(1f),
                                onClick = { sensitivityPopup = SensitivityControl.TRACKPAD }
                            )
                            SensitivityAction(
                                label = "Scroll",
                                icon = R.drawable.control_deck_scroll_speed,
                                modifier = Modifier.weight(1f),
                                onClick = { sensitivityPopup = SensitivityControl.SCROLL }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    keepAwake = !keepAwake
                                    preferences.edit().putBoolean(keepAwakeKey, keepAwake).apply()
                                    setScreenAwake(keepAwake)
                                },
                                modifier = Modifier.size(42.dp)
                                    .background(
                                        if (keepAwake) Color(0xFF585858) else Color(0xFF282828),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(if (keepAwake) R.drawable.control_deck_awake else R.drawable.control_deck_sleep),
                                    tint = if (keepAwake) Color.White else Color.LightGray,
                                    contentDescription = if (keepAwake) "Keep screen on: enabled" else "Keep screen on: disabled",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Box(modifier = Modifier.width(7.dp))
                            IconButton(
                                onClick = {
                                    editing = !editing
                                    selectedId = null
                                    draggingId = null
                                    ctrlArmed = false
                                },
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xFF373737), CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (editing) Icons.Default.Check else Icons.Default.Edit,
                                    tint = Color.White,
                                    contentDescription = if (editing) "Finish editing" else "Edit button grid"
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.weight(0.785f).fillMaxHeight()
                            .background(Color(0xFF272727), RoundedCornerShape(28.dp))
                            .pointerInteropFilter { trackpad.onTouch(it) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "TRACKPAD",
                            color = Color(0xFF707070),
                            fontSize = 13.sp,
                            letterSpacing = 1.5.sp
                        )
                    }

                    Box(
                        modifier = Modifier.width(50.dp).fillMaxHeight()
                            .background(Color(0xFF323232), RoundedCornerShape(26.dp))
                            .pointerInteropFilter { scroll.onTouch(it) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "SCROLL", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                    }
                }

                // Tiny but focusable native text editor. Reuses the working
                // KeyListenerView / KeyInputConnection, including Gboard glide typing.
                AndroidView(
                    factory = { androidContext ->
                        KeyListenerView(androidContext, null).apply {
                            setDeviceId(deviceId)
                            keyboardView = this
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).size(1.dp)
                )

                IconButton(
                    onClick = {
                        if (!plugin.isKeyboardEnabled) {
                            Toast.makeText(
                                this@ControlDeckActivity,
                                R.string.mousepad_keyboard_input_not_supported,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val input = keyboardView
                            if (input != null) {
                                val manager = getSystemService(InputMethodManager::class.java)
                                input.requestFocus()
                                input.post {
                                    manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 74.dp, bottom = 20.dp)
                        .offset(y = -imeHeight)
                        .size(52.dp)
                        .background(Color(0xFF515151), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.control_deck_keyboard),
                        contentDescription = "Open phone keyboard for Windows",
                        tint = Color.White,
                        modifier = Modifier.size(27.dp)
                    )
                }

                when (sensitivityPopup) {
                    SensitivityControl.TRACKPAD -> SensitivityPopup(
                        title = "Trackpad DPI sensitivity",
                        description = "Adjust how far the Windows pointer moves per swipe.",
                        percent = trackpadSensitivity,
                        onValueChange = { value ->
                            trackpadSensitivity = value
                            trackpad.sensitivityPercent = value
                        },
                        onDismiss = {
                            preferences.edit().putInt(trackpadKey, trackpadSensitivity).apply()
                            sensitivityPopup = null
                        },
                        onReset = {
                            trackpadSensitivity = 100
                            trackpad.sensitivityPercent = 100
                            preferences.edit().putInt(trackpadKey, 100).apply()
                        }
                    )
                    SensitivityControl.SCROLL -> SensitivityPopup(
                        title = "Scroll sensitivity",
                        description = "Adjust the right-hand scroll strip speed.",
                        percent = scrollSensitivity,
                        onValueChange = { value ->
                            scrollSensitivity = value
                            scroll.sensitivityPercent = value
                        },
                        onDismiss = {
                            preferences.edit().putInt(scrollKey, scrollSensitivity).apply()
                            sensitivityPopup = null
                        },
                        onReset = {
                            scrollSensitivity = defaultScroll
                            scroll.sensitivityPercent = defaultScroll
                            preferences.edit().putInt(scrollKey, defaultScroll).apply()
                        }
                    )
                    null -> Unit
                }
                }
            }
        }
    }

    private fun setScreenAwake(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun SensitivityAction(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .background(Color(0xFF343434), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun SensitivityPopup(
    title: String,
    description: String,
    percent: Int,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, fontSize = 13.sp)
                Text(
                    text = "$percent%",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = percent.toFloat(),
                    onValueChange = { onValueChange(it.roundToInt().coerceIn(25, 300)) },
                    valueRange = 25f..300f
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("25%", fontSize = 12.sp)
                    Text("300%", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("Reset") }
        }
    )
}

private fun deckIcon(id: String): Int = when (id) {
    "ctrl" -> R.drawable.control_deck_ctrl
    "task_view" -> R.drawable.control_deck_windows
    "terminal" -> R.drawable.control_deck_terminal
    "switch" -> R.drawable.control_deck_switch
    else -> R.drawable.control_deck_ctrl
}

@Composable
private fun DeckButtonFace(button: DeckButton, selected: Boolean) {
    if (button.span == 1) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = if (selected) 9.dp else 4.dp)
        ) {
            Icon(
                painter = painterResource(deckIcon(button.id)),
                tint = Color.White,
                contentDescription = null,
                modifier = Modifier.size(23.dp)
            )
            Text(
                text = button.label,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                maxLines = 2
            )
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(horizontal = if (selected) 25.dp else 12.dp)
        ) {
            Icon(
                painter = painterResource(deckIcon(button.id)),
                tint = Color.White,
                contentDescription = null,
                modifier = Modifier.size(25.dp)
            )
            Text(
                text = button.label,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun EdgeResizeHandle(
    leftEdge: Boolean,
    span: Int,
    modifier: Modifier = Modifier,
    onSpanChange: (Int) -> Unit
) {
    var totalDrag = 0f
    Box(
        modifier = modifier
            .offset(x = if (leftEdge) (-7).dp else 7.dp)
            .width(30.dp)
            .height(46.dp)
            .pointerInput(leftEdge, span) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        totalDrag += amount
                    },
                    onDragEnd = {
                        val threshold = 17.dp.toPx()
                        val outward = if (leftEdge) totalDrag < -threshold else totalDrag > threshold
                        val inward = if (leftEdge) totalDrag > threshold else totalDrag < -threshold
                        if (outward) onSpanChange(2)
                        else if (inward) onSpanChange(1)
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f }
                )
            }
            .clickable { onSpanChange(if (span == 1) 2 else 1) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(9.dp)
                .height(29.dp)
                .background(Color.White, RoundedCornerShape(6.dp))
        )
    }
}

private fun loadDeck(json: String?): List<DeckButton> {
    if (json == null) return defaultButtons
    return try {
        val array = JSONArray(json)
        val saved = (0 until array.length()).mapNotNull { index ->
            val record = array.optJSONObject(index) ?: return@mapNotNull null
            val default = defaultButtons.firstOrNull { it.id == record.optString("id") }
                ?: return@mapNotNull null
            default.copy(span = record.optInt("span", default.span).coerceIn(1, 2))
        }
        if (saved.map { it.id }.toSet() == defaultButtons.map { it.id }.toSet()) saved
        else defaultButtons
    } catch (_: Exception) {
        defaultButtons
    }
}
