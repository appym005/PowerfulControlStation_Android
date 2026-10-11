/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.os.SystemClock
import android.text.Selection
import android.util.Log
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
    private var composingWasGlide = false
    private var newWordComposing = false
    private var shortcutEcho: String? = null
    private var shortcutEchoAt = 0L
    private val correctionWindowMs = 20000L
    // Text prior to this local position is not guaranteed to exist in the
    // current Windows field (e.g. after switching focus with the trackpad).
    private var remoteSafeFrom = 0
    private val logTag = "PCSRemoteIme"

    fun invalidateRemoteContext() {
        // Never clear an Editable while Gboard holds a live InputConnection.
        // Doing so invalidates selection/composition indices and can leave
        // Gboard unable to send any more text until the keyboard is reopened.
        val current = editable?.toString().orEmpty()
        sentShadow = current
        remoteSafeFrom = current.length
        lastWasSwipe = false
        imeSelectedRecentWord = false
        composingWasGlide = false
        newWordComposing = false
        lastEditAt = 0L
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

    private fun interceptControlShortcut(text: CharSequence): Boolean {
        val input = text.toString()
        val now = SystemClock.uptimeMillis()
        if (view.sendCtrlShortcutIfArmed(input)) {
            // Ctrl shortcuts act on Windows without inserting local text. A new
            // IME connection is needed so subsequent text doesn't inherit the
            // old editor's selection (e.g. after Ctrl+A or Ctrl+V).
            shortcutEcho = input
            shortcutEchoAt = now
            invalidateRemoteContext()
            view.restartImeAfterShortcut()
            return true
        }
        // Some IMEs issue a duplicate commit after the composing callback.
        if (input == shortcutEcho && now - shortcutEchoAt in 0L..300L) {
            shortcutEcho = null
            return true
        }
        shortcutEcho = null
        return false
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (interceptControlShortcut(text)) return true
        val buffer = editable
        if (buffer != null) {
            val hadComposition = getComposingSpanStart(buffer) >= 0
            if (!hadComposition && isSwipeCandidate(text.toString())) {
                // Glide input can finish via finishComposingText(), without
                // ever calling commitText() for the completed swipe.
                composingWasGlide = true
            }
            val previousWord = recentSessionWord(sentShadow)
            val selectionStart = Selection.getSelectionStart(buffer)
            if (previousWord != null && selectionStart >= previousWord.end &&
                !imeSelectedRecentWord
            ) {
                newWordComposing = true
            }
        }
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (interceptControlShortcut(text)) return true
        expirePriorWordIfNeeded()
        val input = text.toString()
        val recentWord = recentSessionWord(sentShadow)
            ?.takeIf { it.start >= remoteSafeFrom }
        val corrected = recentSwipeSuggestion(
            mirrored = recentWord?.text.orEmpty(),
            incoming = input,
            recentSwipe = lastWasSwipe,
            withinWindow = lastEditAt != 0L &&
                SystemClock.uptimeMillis() - lastEditAt <= correctionWindowMs,
            imeSelectedWord = imeSelectedRecentWord,
            newWordComposing = newWordComposing
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
            composingWasGlide = false
            newWordComposing = false
            return accepted
        }

        val committed = super.commitText(text, newCursorPosition)
        if (committed) {
            // Glide words arrive as multi-character commits; ordinary typing
            // generally arrives one character at a time.
            if (isSwipeCandidate(input) || composingWasGlide) lastWasSwipe = true
            else if (input.any { it.isWhitespace() } && sentShadow.isEmpty()) lastWasSwipe = false
            flushCommittedText()
        }
        imeSelectedRecentWord = false
        composingWasGlide = false
        newWordComposing = false
        return committed
    }

    private fun selectionTargetsRecentWord(start: Int, end: Int): Boolean {
        if (!lastWasSwipe || start == end) return false
        if (SystemClock.uptimeMillis() - lastEditAt > correctionWindowMs) return false
        val word = recentSessionWord(sentShadow)
            ?.takeIf { it.start >= remoteSafeFrom } ?: return false
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
        val glideCompleted = composingWasGlide
        val result = super.finishComposingText()
        if (result) {
            flushCommittedText()
            if (glideCompleted && recentSessionWord(sentShadow) != null) {
                lastWasSwipe = true
            }
        }
        composingWasGlide = false
        newWordComposing = false
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
            if (!imeSelectedRecentWord) lastWasSwipe = false
            newWordComposing = false
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
            if (!imeSelectedRecentWord) lastWasSwipe = false
            newWordComposing = false
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
        val delta = safeSessionTextDelta(sentShadow, current)
        if (delta == null) {
            // IME changed more than the latest remote word. Don't send unsafe
            // Backspaces to Windows, but ALWAYS accept the new Android baseline
            // without rewriting live composing text/selection. Otherwise Gboard
            // can get stuck repeating the rejected edit forever.
            Log.w(logTag, "Resync after out-of-range edit; oldLength=" +
                sentShadow.length + " newLength=" + current.length)
            sentShadow = current
            remoteSafeFrom = current.length
            lastWasSwipe = false
            imeSelectedRecentWord = false
            composingWasGlide = false
            newWordComposing = false
            lastEditAt = 0L
            return
        }
        if (delta.backspaces > 0) {
            val safeWord = recentSessionWord(sentShadow)
            if (safeWord == null || safeWord.start < remoteSafeFrom) {
                Log.w(logTag, "Ignored deletion touching unsynchronized remote history")
                sentShadow = current
                remoteSafeFrom = current.length
                lastWasSwipe = false
                imeSelectedRecentWord = false
                composingWasGlide = false
                newWordComposing = false
                lastEditAt = 0L
                return
            }
        }
        if (delta.backspaces == 0 && delta.inserted.length == 1 &&
            view.sendCtrlShortcutIfArmed(delta.inserted)
        ) {
            invalidateRemoteContext()
            view.restartImeAfterShortcut()
            return
        }
        if (delta.backspaces > 0) view.sendBackspaces(delta.backspaces)
        for (action in committedTextActions(delta.inserted)) {
            when (action) {
                is RemoteTextAction.Text -> view.sendChars(action.value)
                RemoteTextAction.Enter -> view.sendEnterKey()
            }
        }
        sentShadow = current
        lastEditAt = SystemClock.uptimeMillis()
        // At a fresh word boundary after a context reset, subsequent
        // words can be safely corrected without touching the older prefix.
        if (remoteSafeFrom > 0 && current.length > remoteSafeFrom &&
            current.substring(remoteSafeFrom).any { it.isWhitespace() }
        ) {
            remoteSafeFrom = current.indexOfFirstFrom(remoteSafeFrom) { it.isWhitespace() } + 1
        }

        // Intentionally do NOT trim the Android Editable. Android's IME owns
        // the cursor offsets within that buffer until this keyboard is closed.
        // Remote correction safety is enforced by safeSessionTextDelta().
    }
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (i in start until length) if (predicate(this[i])) return i
    return -1
}
