/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.os.SystemClock
import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Short, bounded IME mirror for Gboard word corrections.
 *
 * Windows does not expose its text field/cursor to us. Keep Android's whole
 * in-session Editable intact so Gboard selection/composition offsets stay
 * stable over multiple words. Limit remote deletions to the current word,
 * and discard all history when the trackpad changes the remote focus.
 */
internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    private var batchDepth = 0
    // Entire already-forwarded Android session buffer; never trim its prefix.
    private var sentShadow = ""
    private var lastEditAt = 0L
    private var lastWasSwipe = false
    // Some Gboard correction taps identify the previous word by explicitly
    // selecting it or setting a composing region around it.
    private var imeSelectedRecentWord = false
    private val correctionWindowMs = 12000L

    fun invalidateRemoteContext() {
        sentShadow = ""
        lastWasSwipe = false
        imeSelectedRecentWord = false
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
        val input = text.toString()
        val recentWord = recentSessionWord(sentShadow)
        val corrected = recentSwipeSuggestion(
            mirrored = recentWord?.text.orEmpty(),
            incoming = input,
            recentSwipe = lastWasSwipe,
            withinWindow = lastEditAt != 0L &&
                SystemClock.uptimeMillis() - lastEditAt <= correctionWindowMs,
            imeSelectedWord = imeSelectedRecentWord
        )
        imeSelectedRecentWord = false

        if (corrected != null && recentWord != null) {
            val buffer = editable ?: return false
            // Preserve all preceding Android text/selection indices; select ONLY
            // the current word for replacement. Clearing/trimming earlier text
            // used to desynchronize Gboard after the first successful correction.
            if (getComposingSpanStart(buffer) >= 0 || getComposingSpanEnd(buffer) >= 0) {
                super.finishComposingText()
            }
            Selection.setSelection(buffer, recentWord.start, buffer.length)
            val accepted = super.commitText(corrected, newCursorPosition)
            if (accepted) {
                lastWasSwipe = true
                flushCommittedText()
            }
            imeSelectedRecentWord = false
            return accepted
        }

        val committed = super.commitText(text, newCursorPosition)
        if (committed) {
            // Glide words arrive as multi-character commits; ordinary typing
            // generally arrives one character at a time.
            if (isSwipeCandidate(input)) lastWasSwipe = true
            else if (text.any { it.isWhitespace() } && sentShadow.isEmpty()) lastWasSwipe = false
            flushCommittedText()
        }
        imeSelectedRecentWord = false
        return committed
    }

    private fun selectionTargetsRecentWord(start: Int, end: Int): Boolean {
        if (!lastWasSwipe || start == end) return false
        if (SystemClock.uptimeMillis() - lastEditAt > correctionWindowMs) return false
        val word = recentSessionWord(sentShadow) ?: return false
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        // Selection indices must target the actual last word, not an earlier
        // piece of text in the same session. No fabricated/stale offset mapping.
        return lo >= word.start && lo < word.end &&
            hi <= word.end && hi > word.start
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (selectionTargetsRecentWord(start, end)) {
            imeSelectedRecentWord = true
        }
        return super.setComposingRegion(start, end)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (selectionTargetsRecentWord(start, end)) {
            imeSelectedRecentWord = true
        }
        return super.setSelection(start, end)
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
            recentSessionWord(localBefore)?.text.orEmpty(), beforeLength, afterLength,
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
        val recentWord = recentSessionWord(localBefore)
        val swipeLength = swipeDeleteLength(
            recentWord?.text.orEmpty(), beforeLength, afterLength,
            lastWasSwipe, SystemClock.uptimeMillis() - lastEditAt <= correctionWindowMs
        )
        val effectiveBefore = if (swipeLength > 0) swipeLength else beforeLength
        val result = super.deleteSurroundingTextInCodePoints(effectiveBefore, afterLength)
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
            val word = recentSessionWord(editable?.toString().orEmpty())
            val now = SystemClock.uptimeMillis()
            if (lastWasSwipe && now - lastEditAt <= correctionWindowMs && word != null) {
                val length = word.text.length
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
            // Expire only suggestion eligibility. Clearing the Editable here
            // invalidates Gboard's position indices for the rest of the session.
            lastWasSwipe = false
            imeSelectedRecentWord = false
        }
    }

    private fun flushCommittedText() {
        if (batchDepth != 0) return
        val localText = editable ?: return
        if (getComposingSpanStart(localText) >= 0 || getComposingSpanEnd(localText) >= 0) return
        val current = localText.toString()
        if (current == sentShadow) return

        // Older text can remain for Gboard's offsets, but it cannot be deleted
        // remotely. Return without sending anything if the IME tries to edit
        // farther back than the latest word.
        val delta = safeSessionTextDelta(sentShadow, current) ?: return
        if (delta.backspaces > 0) view.sendBackspaces(delta.backspaces)
        for (action in committedTextActions(delta.inserted)) {
            when (action) {
                is RemoteTextAction.Text -> view.sendChars(action.value)
                RemoteTextAction.Enter -> view.sendEnterKey()
            }
        }
        sentShadow = current
        lastEditAt = SystemClock.uptimeMillis()

        // Intentionally do NOT trim the Android Editable. Android's IME owns
        // the cursor offsets within that buffer until this keyboard is closed.
        // Remote correction safety is enforced by safeSessionTextDelta().
    }
}
