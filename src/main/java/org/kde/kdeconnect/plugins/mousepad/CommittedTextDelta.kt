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
