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
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.ui.compose.KdeTheme

private data class DeckButton(val id: String, val label: String, val span: Int)

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

                Row(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF161616)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(0.43f).fillMaxHeight(),
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
                                    Text(
                                        text = button.label,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = if (button.span == 1) 14.sp else 17.sp,
                                        maxLines = 2,
                                        modifier = Modifier.padding(horizontal = if (isSelected) 30.dp else 10.dp)
                                    )
                                    if (isSelected) {
                                        SpanControl(
                                            text = "−",
                                            enabled = button.span != 1,
                                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 3.dp)
                                        ) {
                                            val at = buttons.indexOfFirst { it.id == button.id }
                                            if (at >= 0) {
                                                buttons[at] = button.copy(span = 1)
                                                persist()
                                            }
                                        }
                                        SpanControl(
                                            text = "+",
                                            enabled = button.span != 2,
                                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp)
                                        ) {
                                            val at = buttons.indexOfFirst { it.id == button.id }
                                            if (at >= 0) {
                                                buttons[at] = button.copy(span = 2)
                                                persist()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                        modifier = Modifier.weight(0.57f).fillMaxHeight()
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
            }
        }
    }
}

@Composable
private fun SpanControl(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.size(28.dp)
            .background(if (enabled) Color(0xFF505050) else Color(0xFF252525), CircleShape)
            .border(1.dp, if (enabled) Color.White else Color.DarkGray, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Color.Gray, fontSize = 19.sp)
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
