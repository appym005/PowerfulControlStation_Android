/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Short, bounded IME mirror for Gboard word corrections.
 *
 * We cannot know the Windows field's entire text or cursor. We mirror only
 * the most recent word (max 64 UTF-16 units) for a short correction window.
 * As soon as the user touches the remote trackpad or opens a new keyboard
 * session, discard this mirror so old text cannot be replayed in a new field.
 */
internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    private var batchDepth = 0
    private var sentShadow = ""
    private var lastEditAt = 0L
    private var lastWasSwipe = false
    private val correctionWindowMs = 12000L

    fun invalidateRemoteContext() {
        sentShadow = ""
        lastWasSwipe = false
        editable?.clear()
    }

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) flushCommittedText()
        return batchDepth > 0
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        expirePriorWordIfNeeded()
        val committed = super.commitText(text, newCursorPosition)
        if (committed) {
            // Gboard usually commits glide words as a single multi-character
            // segment, unlike regular typing which commits one character.
            if (isSwipeCandidate(text.toString())) lastWasSwipe = true
            else if (text.any { it.isWhitespace() } && sentShadow.isEmpty()) lastWasSwipe = false
            flushCommittedText()
        }
        return committed
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        if (result) flushCommittedText()
        return result
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        expirePriorWordIfNeeded()
        val localBefore = editable?.toString().orEmpty()
        val deleteLength = swipeDeleteLength(
            localBefore, beforeLength, afterLength,
            lastWasSwipe, SystemClock.uptimeMillis() - lastEditAt <= correctionWindowMs
        )
        val effectiveBefore = if (deleteLength > 0) deleteLength else beforeLength
        val result = super.deleteSurroundingText(effectiveBefore, afterLength)
        if (result) {
            if (localBefore.isEmpty() && safeImeRemoteBackspaceCount(beforeLength, afterLength, false) > 0) {
                view.sendBackspaces(1)
            } else {
                flushCommittedText()
            }
            lastWasSwipe = false
        }
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        expirePriorWordIfNeeded()
        val localBefore = editable?.toString().orEmpty()
        val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        if (result) {
            if (localBefore.isEmpty() && safeImeRemoteBackspaceCount(beforeLength, afterLength, false) > 0) {
                view.sendBackspaces(1)
            } else {
                flushCommittedText()
            }
            lastWasSwipe = false
        }
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_DEL) {
            val word = editable?.toString().orEmpty()
            val now = SystemClock.uptimeMillis()
            if (lastWasSwipe && now - lastEditAt <= correctionWindowMs && word.isNotEmpty()) {
                val length = word.length
                super.deleteSurroundingText(length, 0)
                flushCommittedText()
                lastWasSwipe = false
                return true // Already sent full remote deletion; don't send Backspace twice
            }
        }
        // Physical key events are handled by KeyListenerView.
        return super.sendKeyEvent(event)
    }

    override fun closeConnection() {
        invalidateRemoteContext()
        super.closeConnection()
    }

    private fun expirePriorWordIfNeeded() {
        val now = SystemClock.uptimeMillis()
        if (lastEditAt != 0L && now - lastEditAt > correctionWindowMs) {
            invalidateRemoteContext()
        }
    }

    private fun flushCommittedText() {
        if (batchDepth != 0) return
        val localText = editable ?: return
        if (getComposingSpanStart(localText) >= 0 || getComposingSpanEnd(localText) >= 0) return
        val current = localText.toString()
        if (current == sentShadow) return

        // Do not allow an IME to delete beyond the last short, locally mirrored
        // word. In particular, never replay an unrelated paragraph.
        val delta = boundedWordCorrection(sentShadow, current)
        if (delta.backspaces > 0) view.sendBackspaces(delta.backspaces)
        for (action in committedTextActions(delta.inserted)) {
            when (action) {
                is RemoteTextAction.Text -> view.sendChars(action.value)
                RemoteTextAction.Enter -> view.sendEnterKey()
            }
        }
        sentShadow = current
        lastEditAt = SystemClock.uptimeMillis()

        // Restrict the buffer to the newest word (plus any trailing space).
        // Keep the latest completed swipe available for suggestion replacement.
        val trimAt = shadowTrimIndex(current)
        if (trimAt > 0) {
            localText.delete(0, trimAt)
            sentShadow = localText.toString()
            lastWasSwipe = isSwipeCandidate(sentShadow.trimEnd())
        }
        if (sentShadow.length > 64) {
            invalidateRemoteContext()
        }
    }
}
