/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * This is a REMOTE keyboard, not an Android editor with the PC's text state.
 *
 * Gboard needs a temporary Editable for composing and swipe input. Keeping
 * previously sent paragraphs here is unsafe: PC focus can change independently
 * and comparing that old text to a new IME selection can delete an entirely
 * unrelated Windows field and replay old text.
 *
 * Forward each finished composition exactly once, then discard the shadow.
 * Never derive remote multi-character deletion from old local paragraphs.
 */
internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    private var batchDepth = 0

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
        val committed = super.commitText(text, newCursorPosition)
        if (committed) flushCommittedText()
        return committed
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        if (result) flushCommittedText()
        return result
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val hasBufferedText = editable?.isNotEmpty() == true
        val result = super.deleteSurroundingText(beforeLength, afterLength)
        if (result && safeImeRemoteBackspaceCount(beforeLength, afterLength, hasBufferedText) != 0) {
            view.sendBackspaces(1)
        }
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val hasBufferedText = editable?.isNotEmpty() == true
        val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        if (result && safeImeRemoteBackspaceCount(beforeLength, afterLength, hasBufferedText) != 0) {
            view.sendBackspaces(1)
        }
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // KeyListenerView already translates physical KeyUp to a remote packet.
        // Do not generate a second Backspace from the input connection.
        return super.sendKeyEvent(event)
    }

    override fun closeConnection() {
        // Do NOT auto-commit a stale provisional word when Android closes or
        // restarts the keyboard after the PC has moved to another text field.
        editable?.clear()
        super.closeConnection()
    }

    private fun flushCommittedText() {
        if (batchDepth != 0) return
        val localText = editable ?: return
        if (getComposingSpanStart(localText) >= 0 || getComposingSpanEnd(localText) >= 0) {
            return // Gboard is still deciding the provisional swipe/prediction.
        }
        val committed = localText.toString()
        if (committed.isEmpty()) return

        for (action in committedTextActions(committed)) {
            when (action) {
                is RemoteTextAction.Text -> view.sendChars(action.value)
                RemoteTextAction.Enter -> view.sendEnterKey()
            }
        }

        // Critical: PC caret/focus is NOT synchronized with this local Editable.
        localText.clear()
    }
}
