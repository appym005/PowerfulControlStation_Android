/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PadProtocolTest {
    @Test
    fun stateMapsXboxButtonMaskAxesAndTriggers() {
        val state = PadState(buttons = 0x1000 or 0x0200, lx = 1f, ly = -1f, rx = .5f, rt = 1f)
        assertEquals(
            "PCS-GP1|STATE|phone|7|4608|32767|-32767|16383|0|0|255",
            PadProtocol.body("phone", 7, state)
        )
    }

    @Test
    fun hmacChangesWheneverAnInputChanges() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val original = PadProtocol.body("tablet", 1, PadState(buttons = 0x1000))
        val changed = PadProtocol.body("tablet", 1, PadState(buttons = 0x2000))
        val first = PadProtocol.signature(original, key)
        assertEquals(64, first.length)
        assertNotEquals(first, PadProtocol.signature(changed, key))
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
    }
}
