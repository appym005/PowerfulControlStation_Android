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
        assertEquals(true, isSwipeCandidate("hello "))
        assertEquals(false, isSwipeCandidate("hello world"))
    }

    @Test
    fun gboardSuggestionReplacesLastSwipedWordInsteadOfAppending() {
        assertEquals(
            "hello ",
            recentSwipeSuggestion("helo ", "hello", true, true, false)
        )
        assertEquals(
            "word",
            recentSwipeSuggestion("wrod", "word", true, true, false)
        )
    }

    @Test
    fun explicitlySelectedSuggestionSupportsMoreDistantCorrections() {
        assertEquals(
            "completing ",
            recentSwipeSuggestion("compellingleting ", "completing ", true, true, true)
        )
    }

    @Test
    fun nextGlideWordAndNormalTypingMustNotReplacePreviousWord() {
        assertEquals(null, recentSwipeSuggestion("hello ", "world ", true, true, false))
        assertEquals(null, recentSwipeSuggestion("hello ", "world", true, true, false))
        assertEquals(null, recentSwipeSuggestion("hello ", "a", true, true, false))
        assertEquals(null, recentSwipeSuggestion("hello ", "hello", true, true, false))
    }

    @Test
    fun aStaleSwipeCannotTriggerSuggestionReplacementInAnotherField() {
        assertEquals(null, recentSwipeSuggestion("helo ", "hello", true, false, true))
        assertEquals(null, recentSwipeSuggestion("helo ", "hello", false, true, true))
        assertEquals(null, recentSwipeSuggestion("x".repeat(65), "hello", true, true, true))
    }

    @Test
    fun suggestionWithSelectedWordMayCarryAnAutoSpace() {
        assertEquals(
            "hello ",
            recentSwipeSuggestion("helo", "hello ", true, true, true)
        )
    }


    @Test
    fun repeatedSuggestionsInSingleSessionRetainCorrectAbsoluteOffsets() {
        // Gboard has already sent a first swipe followed by a tap on "hello".
        val first = "helo "
        val firstSpan = recentSessionWord(first)!!
        assertEquals(0, firstSpan.start)
        assertEquals("hello ", recentSwipeSuggestion(firstSpan.text, "hello", true, true, false))
        assertEquals(CommittedTextDelta(2, "lo "), safeSessionTextDelta(first, "hello "))

        // Gboard continues composing without closing/reopening the keyboard.
        val second = "hello wrod "
        val secondSpan = recentSessionWord(second)!!
        assertEquals(6, secondSpan.start)
        assertEquals("wrod ", secondSpan.text)
        assertEquals("word ", recentSwipeSuggestion(secondSpan.text, "word", true, true, false))
        assertEquals(CommittedTextDelta(4, "ord "), safeSessionTextDelta(second, "hello word "))

        // Third correction in the SAME IME session.
        val third = "hello word teh "
        val thirdSpan = recentSessionWord(third)!!
        assertEquals(11, thirdSpan.start)
        assertEquals("the ", recentSwipeSuggestion(thirdSpan.text, "the", true, true, false))
        assertEquals(CommittedTextDelta(3, "he "), safeSessionTextDelta(third, "hello word the "))
    }

    @Test
    fun sessionPrefixCannotBeDeletedByStaleGboardSelection() {
        assertEquals(null, safeSessionTextDelta("hello world ", "world "))
        assertEquals(null, safeSessionTextDelta("first second third", "first "))
        assertEquals(null, safeSessionTextDelta("abc def", "xyz def"))
    }

    @Test
    fun appendingAfterManyWordsStillForwardsText() {
        assertEquals(
            CommittedTextDelta(0, "next "),
            safeSessionTextDelta("hello word ", "hello word next ")
        )
        assertEquals(
            CommittedTextDelta(0, "more"),
            safeSessionTextDelta("hello word next ", "hello word next more")
        )
    }

    @Test
    fun fullSwipeBackspaceTargetsOnlyLatestSessionWord() {
        val current = "hello word swipe "
        val span = recentSessionWord(current)!!
        assertEquals("swipe ", span.text)
        assertEquals(6, swipeDeleteLength(span.text, 1, 0, true, true))
        assertEquals(CommittedTextDelta(6, ""), safeSessionTextDelta(current, "hello word "))
    }


    @Test
    fun autoSpacedSuggestionsAreNotMistakenForAdditionalText() {
        assertEquals(
            "hello ",
            recentSwipeSuggestion("helo ", "hello ", true, true, false)
        )
        assertEquals(
            "world ",
            recentSwipeSuggestion("wrold ", "world ", true, true, false)
        )
        assertEquals(
            "world ",
            recentSwipeSuggestion("wrold ", "world ", true, true, true)
        )
    }

    @Test
    fun newGboardCompositionsCannotCorrectAnEarlierWord() {
        assertEquals(
            null,
            recentSwipeSuggestion("hello ", "hell ", true, true, false, newWordComposing = true)
        )
        assertEquals(
            null,
            recentSwipeSuggestion("hello ", "hello", true, true, false, newWordComposing = true)
        )
        assertEquals(
            "completing ",
            recentSwipeSuggestion(
                "compellingleting ", "completing ", true, true, true,
                newWordComposing = true
            )
        )
    }

    @Test
    fun aNewSwipeCanBeCorrectedMoreThanOnceWithoutReopeningIme() {
        val first = "helo "
        val corrected1 = "hello "
        assertEquals(CommittedTextDelta(2, "lo "), safeSessionTextDelta(first, corrected1))
        val nextSwipe = "$corrected1" + "wrold "
        val nextCorrection = "$corrected1" + "world "
        assertEquals(
            CommittedTextDelta(5, "orld "),
            safeSessionTextDelta(nextSwipe, nextCorrection)
        )
        val thirdSwipe = "$nextCorrection" + "teh "
        val thirdCorrection = "$nextCorrection" + "the "
        assertEquals(CommittedTextDelta(3, "he "), safeSessionTextDelta(thirdSwipe, thirdCorrection))
    }


    @Test
    fun ctrlShortcutFromGboardNeedsOnlyOneAsciiLetter() {
        assertEquals("c", RemoteModifierKey.ctrlLetter("C"))
        assertEquals("a", RemoteModifierKey.ctrlLetter("a"))
        assertEquals("v", RemoteModifierKey.ctrlLetter("v"))
        assertEquals("z", RemoteModifierKey.ctrlLetter("Z"))
        assertEquals(null, RemoteModifierKey.ctrlLetter("hello"))
        assertEquals(null, RemoteModifierKey.ctrlLetter(" "))
        assertEquals(null, RemoteModifierKey.ctrlLetter("\n"))
        assertEquals(null, RemoteModifierKey.ctrlLetter("🌍"))
    }


    @Test
    fun invalidEarlierWordCorrectionRebasesWithoutTouchingLiveEditor() {
        assertEquals(
            RemoteImeDecision.Rebase,
            decideRemoteImeEdit("hello world ", "world ", 0)
        )
        // After rebase, Gboard's next commit still reaches Windows.
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(0, "next")),
            decideRemoteImeEdit("world ", "world next", 6)
        )
    }

    @Test
    fun remoteFocusChangeCannotBackspacePreviousWindowsField() {
        assertEquals(
            RemoteImeDecision.Rebase,
            decideRemoteImeEdit("oldtext ", "oldtex ", 8)
        )
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(0, "new ")),
            decideRemoteImeEdit("oldtext ", "oldtext new ", 8)
        )
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(2, "d ")),
            decideRemoteImeEdit("oldtext new ", "oldtext ned ", 8)
        )
    }

    @Test
    fun recentSuggestionCorrectionsStillForwardAfterRecoveryFix() {
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(2, "lo ")),
            decideRemoteImeEdit("helo ", "hello ", 0)
        )
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(5, "orld ")),
            decideRemoteImeEdit("hello wrold ", "hello world ", 0)
        )
    }

    @Test
    fun invalidSelectionWithinGboardHistoryDoesNotBlockLaterTyping() {
        val previous = "hello word first "
        assertEquals(
            RemoteImeDecision.Rebase,
            decideRemoteImeEdit(previous, "hello first ", 0)
        )
        val accepted = "hello first "
        assertEquals(
            RemoteImeDecision.Forward(CommittedTextDelta(0, "again")),
            decideRemoteImeEdit(accepted, "$accepted" + "again", accepted.length)
        )
    }

}
