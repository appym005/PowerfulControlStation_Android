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
}
