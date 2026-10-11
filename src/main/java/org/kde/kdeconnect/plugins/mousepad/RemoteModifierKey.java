/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad;

/** Printable Gboard key that may be sent as a real Windows Ctrl shortcut. */
final class RemoteModifierKey {
    private RemoteModifierKey() {}

    static String ctrlLetter(CharSequence text) {
        if (text == null || text.length() != 1) return null;
        final char key = text.charAt(0);
        if (key >= 'a' && key <= 'z') return String.valueOf(key);
        if (key >= 'A' && key <= 'Z') return String.valueOf((char) (key - 'A' + 'a'));
        return null;
    }
}
