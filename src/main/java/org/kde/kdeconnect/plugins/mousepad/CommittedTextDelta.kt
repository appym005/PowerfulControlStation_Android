/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

/**
 * Converts changes in the Android IME's committed text into operations
 * supported by KDE Connect's existing mousepad protocol: Backspace and text.
 *
 * Comparing Unicode code points avoids cutting a surrogate pair when Gboard
 * replaces an emoji or corrects the end of an already committed word.
 */
internal data class CommittedTextDelta(val backspaces: Int, val inserted: String)

internal fun committedTextDelta(previous: String, current: String): CommittedTextDelta {
    var commonUnits = 0
    while (commonUnits < previous.length && commonUnits < current.length) {
        val oldPoint = Character.codePointAt(previous, commonUnits)
        val newPoint = Character.codePointAt(current, commonUnits)
        if (oldPoint != newPoint) break
        commonUnits += Character.charCount(oldPoint)
    }

    return CommittedTextDelta(
        backspaces = previous.codePointCount(commonUnits, previous.length),
        inserted = current.substring(commonUnits)
    )
}

/**
 * IMEs can submit Enter as a text newline rather than a hardware key event.
 * Windows KDE Connect then inserts text instead of simulating VK_RETURN.
 * Split committed text into regular text and physical Enter operations.
 */
internal sealed class RemoteTextAction {
    data class Text(val value: String) : RemoteTextAction()
    object Enter : RemoteTextAction()
}

internal fun committedTextActions(inserted: String): List<RemoteTextAction> {
    if (inserted.isEmpty()) return emptyList()
    val actions = mutableListOf<RemoteTextAction>()
    var start = 0
    var index = 0
    while (index < inserted.length) {
        val char = inserted[index]
        if (char == '\r' || char == '\n') {
            if (index > start) {
                actions.add(RemoteTextAction.Text(inserted.substring(start, index)))
            }
            actions.add(RemoteTextAction.Enter)
            if (char == '\r' && index + 1 < inserted.length && inserted[index + 1] == '\n') {
                index++
            }
            start = index + 1
        }
        index++
    }
    if (start < inserted.length) actions.add(RemoteTextAction.Text(inserted.substring(start)))
    return actions
}


/**
 * Allow only an explicit single Backspace from an IME with no unsent local text.
 * A large deleteSurroundingText range is commonly caused by Gboard's outdated
 * editor selection and MUST NOT erase a separate Windows field.
 */
internal fun safeImeRemoteBackspaceCount(
    beforeLength: Int,
    afterLength: Int,
    hasBufferedText: Boolean
): Int = if (!hasBufferedText && beforeLength == 1 && afterLength == 0) 1 else 0


/** Guard remote corrections against replaying or deleting arbitrary historical text. */
internal fun boundedWordCorrection(previous: String, current: String): CommittedTextDelta {
    if (previous.length > 64 || current.length > 256) return CommittedTextDelta(0, "")
    val delta = committedTextDelta(previous, current)
    return if (delta.backspaces <= 64) delta else CommittedTextDelta(0, "")
}

/** Gboard generally commits glide words as a single multi-character segment. */
internal fun isSwipeCandidate(text: String): Boolean {
    val word = text.trimEnd(' ')
    return text.length in 2..65 && word.length in 2..64 &&
        word.all { it.isLetter() || it == '\'' || it == '-' } &&
        text.substring(word.length).all { it == ' ' }
}

/** A backspace immediately after a glide commit should remove that whole word. */
internal fun swipeDeleteLength(
    mirrored: String,
    requestedBefore: Int,
    requestedAfter: Int,
    recentSwipe: Boolean,
    withinWindow: Boolean
): Int = if (
    recentSwipe && withinWindow && requestedBefore == 1 && requestedAfter == 0 &&
    mirrored.length in 2..64 && mirrored.trimEnd().all { it.isLetter() || it == '\'' || it == '-' }
) mirrored.length else 0

/** Keep only the final word, or clear local context after an Enter. */
internal fun shadowTrimIndex(text: String): Int {
    val lastNewline = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
    if (lastNewline >= 0) return lastNewline + 1
    val end = text.indexOfLast { !it.isWhitespace() }
    if (end < 0) return 0
    return text.lastIndexOf(' ', end).let { if (it >= 0) it + 1 else 0 }
}


/**
 * Gboard's correction-suggestion tap can arrive as commitText("correctWord")
 * even though the previous swiped word has already been sent to Windows.
 * Without a host selection, the IME may append the suggestion locally.
 *
 * Only reinterpret it as a replacement while the latest short swipe is active,
 * and either Gboard selected that word or the suggestion is a plausible spelling
 * correction. A different subsequent swiped word must not erase the previous one.
 */
internal fun recentSwipeSuggestion(
    mirrored: String,
    incoming: String,
    recentSwipe: Boolean,
    withinWindow: Boolean,
    imeSelectedWord: Boolean
): String? {
    if (!recentSwipe || !withinWindow || mirrored.isEmpty()) return null
    val existing = mirrored.trimEnd(' ')
    val suggestion = incoming.trimEnd(' ')
    if (!isSwipeCandidate(existing) || !isSwipeCandidate(incoming) || suggestion == existing) {
        return null
    }
    if (!imeSelectedWord) {
        // When Gboard commits a *new* glide word it generally includes or
        // establishes a separator. Do not reinterpret an auto-spaced glide.
        if (incoming.endsWith(' ')) return null
        val maximumEdits = maxOf(2, existing.length / 3)
        if (shortWordEditDistance(existing, suggestion) > maximumEdits) return null
    }
    // Retain Gboard's auto-space, if the original word had one, otherwise the
    // next plain keystroke would end up glued to the corrected word.
    val separator = if (incoming.endsWith(' ') || mirrored.endsWith(' ')) " " else ""
    return suggestion + separator
}

/** Bounded adjacent-transposition-aware edit distance for short IME words. */
private fun shortWordEditDistance(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return maxOf(a.length, b.length)
    val distances = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) distances[i][0] = i
    for (j in 0..b.length) distances[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) 0 else 1
            var candidate = minOf(
                distances[i - 1][j] + 1,
                distances[i][j - 1] + 1,
                distances[i - 1][j - 1] + cost
            )
            if (i > 1 && j > 1 &&
                a[i - 1].equals(b[j - 2], ignoreCase = true) &&
                a[i - 2].equals(b[j - 1], ignoreCase = true)
            ) {
                candidate = minOf(candidate, distances[i - 2][j - 2] + 1)
            }
            distances[i][j] = candidate
        }
    }
    return distances[a.length][b.length]
}


/**
 * The recent word including a possible Gboard auto-space, while preserving
 * its *absolute* index in the Android Editable for subsequent IME selections.
 * Prior words remain in the session buffer; only this range can be corrected.
 */
internal data class RecentSessionWord(val start: Int, val end: Int, val text: String)

internal fun recentSessionWord(session: String): RecentSessionWord? {
    if (session.isEmpty()) return null
    var end = session.length
    while (end > 0 && session[end - 1] == ' ') end--
    if (end == 0) return null
    var start = end
    while (start > 0 && (
        session[start - 1].isLetter() ||
        session[start - 1] == '\'' ||
        session[start - 1] == '-'
    )) start--
    if (start == end || end - start > 64) return null
    if (start > 0 && !session[start - 1].isWhitespace()) return null
    if (session.length - end > 1) return null
    return RecentSessionWord(start, session.length, session.substring(start))
}

/**
 * Allows new text to append freely, but forbids Gboard from sending deletions
 * extending into previously committed words on Windows. Unlike the old
 * boundedWordCorrection(), the Android session prefix remains untouched.
 */
internal fun safeSessionTextDelta(previous: String, current: String): CommittedTextDelta? {
    val delta = committedTextDelta(previous, current)
    if (delta.backspaces == 0) return delta
    val latestWord = recentSessionWord(previous) ?: return null
    val allowedCodePoints = latestWord.text.codePointCount(0, latestWord.text.length)
    return if (delta.backspaces <= allowedCodePoints) delta else null
}
