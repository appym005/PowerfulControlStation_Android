/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * The phone keyboard edits a *local* shadow of the remote text.
 *
 * Gboard's gesture input can change its composing word repeatedly before it
 * commits it. Sending every provisional word to the PC would duplicate text.
 * Instead, we mirror edits with BaseInputConnection and send only committed
 * changes through KDE Connect's existing text and Backspace packets.
 */
internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    private var lastSentText = ""
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
        // This also replaces any currently composing region in our shadow buffer.
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
        val result = super.deleteSurroundingText(beforeLength, afterLength)
        if (result) flushCommittedText()
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        if (result) flushCommittedText()
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_UP) {
            // Some IMEs emit physical Backspace events instead of
            // deleteSurroundingText. The view's existing onKeyUp handler sends
            // that key to Windows, so update the shadow without sending it twice.
            beginBatchEdit()
            super.deleteSurroundingText(1, 0)
            lastSentText = editable.toString()
            endBatchEdit()
        }
        return super.sendKeyEvent(event)
    }

    override fun closeConnection() {
        // Don't strand a word that the keyboard left in its composing state.
        finishComposingText()
        super.closeConnection()
    }

    private fun flushCommittedText() {
        if (batchDepth != 0) return

        val localText = editable ?: return
        if (getComposingSpanStart(localText) >= 0 || getComposingSpanEnd(localText) >= 0) {
            return // Gboard is still deciding the word (swipe/prediction).
        }

        val current = localText.toString()
        if (current == lastSentText) return

        val delta = committedTextDelta(lastSentText, current)
        if (delta.backspaces > 0) view.sendBackspaces(delta.backspaces)
        if (delta.inserted.isNotEmpty()) view.sendChars(delta.inserted)

        lastSentText = current
    }
}
