// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreInfoTest {
    @Test
    fun nameAndVersion() {
        assertEquals("SpectraSDR", CoreInfo.NAME)
        assertEquals("0.1.0", CoreInfo.VERSION)
    }
}
