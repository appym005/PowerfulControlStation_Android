/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedTextDeltaTest {
    @Test
    fun swipeCommitSendsOneFinalWord() {
        assertEquals(CommittedTextDelta(0, "hello"), committedTextDelta("", "hello"))
    }

    @Test
    fun normalTypingAppendsOnlyNewCharacters() {
        assertEquals(CommittedTextDelta(0, " world"), committedTextDelta("hello", "hello world"))
    }

    @Test
    fun autocorrectReplacesAlreadyCommittedWordEnding() {
        assertEquals(CommittedTextDelta(2, "lp"), committedTextDelta("hepl", "help"))
    }

    @Test
    fun backspaceIsForwardedAsBackspace() {
        assertEquals(CommittedTextDelta(1, ""), committedTextDelta("hello", "hell"))
    }

    @Test
    fun unchangedTextDoesNotSendAnything() {
        assertEquals(CommittedTextDelta(0, ""), committedTextDelta("hello", "hello"))
    }

    @Test
    fun emojisAreDeletedAsFullCodePoints() {
        assertEquals(CommittedTextDelta(1, "🐕"), committedTextDelta("cat🐈", "cat🐕"))
        assertEquals(CommittedTextDelta(1, ""), committedTextDelta("cat🐈", "cat"))
    }

    @Test
    fun handlesNonLatinCharacters() {
        assertEquals(CommittedTextDelta(0, " दुनिया"), committedTextDelta("नमस्ते", "नमस्ते दुनिया"))
    }

    @Test
    fun gboardCommittedEnterBecomesRealHostEnter() {
        assertEquals(
            listOf(RemoteTextAction.Text("Get-Process"), RemoteTextAction.Enter),
            committedTextActions("Get-Process\n")
        )
    }

    @Test
    fun carriageReturnAndWindowsNewlineSendOneEnter() {
        assertEquals(listOf(RemoteTextAction.Enter), committedTextActions("\r"))
        assertEquals(listOf(RemoteTextAction.Enter), committedTextActions("\r\n"))
    }

    @Test
    fun consecutiveEntersAreNotDropped() {
        assertEquals(
            listOf(
                RemoteTextAction.Text("hello"),
                RemoteTextAction.Enter,
                RemoteTextAction.Enter,
                RemoteTextAction.Text("world")
            ),
            committedTextActions("hello\n\nworld")
        )
    }

    @Test
    fun ordinarySwipeTextAndUnicodeRemainUntouched() {
        assertEquals(
            listOf(RemoteTextAction.Text("दुनिया 🌍")),
            committedTextActions("दुनिया 🌍")
        )
        assertEquals(emptyList<RemoteTextAction>(), committedTextActions(""))
    }
    @Test
    fun remoteBackspaceIsAllowedOnlyForSingleDirectDeletion() {
        assertEquals(1, safeImeRemoteBackspaceCount(1, 0, false))
        assertEquals(0, safeImeRemoteBackspaceCount(1, 0, true))
        assertEquals(0, safeImeRemoteBackspaceCount(1, 1, false))
    }

    @Test
    fun staleImeSelectionsCannotDeleteRemoteParagraphs() {
        assertEquals(0, safeImeRemoteBackspaceCount(50, 0, false))
        assertEquals(0, safeImeRemoteBackspaceCount(2000, 0, false))
        assertEquals(0, safeImeRemoteBackspaceCount(0, 150, false))
    }

    @Test
    fun lastSwipeWordCanBeReplacedBySuggestedCorrection() {
        assertEquals(CommittedTextDelta(2, "lp"), boundedWordCorrection("hepl", "help"))
        assertEquals(CommittedTextDelta(3, "ord"), boundedWordCorrection("wrod", "word"))
    }

    @Test
    fun swipeBackspaceRemovesEntireRecentWord() {
        assertEquals(5, swipeDeleteLength("hello", 1, 0, true, true))
        assertEquals(6, swipeDeleteLength("hello ", 1, 0, true, true))
        assertEquals(0, swipeDeleteLength("hello", 1, 0, false, true))
        assertEquals(0, swipeDeleteLength("hello", 1, 0, true, false))
        assertEquals(0, swipeDeleteLength("hello", 2, 0, true, true))
    }

    @Test
    fun staleOrLargeWordsNeverTriggerMassDeletion() {
        val old = "x".repeat(120)
        val changed = "y".repeat(120)
        assertEquals(CommittedTextDelta(0, ""), boundedWordCorrection(old, changed))
        assertEquals(0, swipeDeleteLength(old, 1, 0, true, true))
    }

    @Test
    fun bufferKeepsOnlyMostRecentWordForGboardCorrection() {
        assertEquals(0, shadowTrimIndex("hello "))
        assertEquals(6, shadowTrimIndex("hello world"))
        assertEquals(6, shadowTrimIndex("hello world "))
        assertEquals(6, shadowTrimIndex("hello\n"))
        assertEquals(8, shadowTrimIndex("abc def\n"))
    }

    @Test
    fun regularSingleCharacterTypingIsNotMisclassifiedAsSwipe() {
        assertEquals(false, isSwipeCandidate("a"))
        assertEquals(false, isSwipeCandidate(" "))
        assertEquals(true, isSwipeCandidate("hello"))
        assertEquals(false, isSwipeCandidate("hello world"))
    }
}
