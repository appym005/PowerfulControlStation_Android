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
