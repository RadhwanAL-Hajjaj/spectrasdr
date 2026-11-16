// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr

import android.app.Application
import android.content.Context
import io.github.radhwanalhajjaj.spectrasdr.radio.Radio

class SpectraApp : Application() {
    /** The one radio of the process: the screens and the service both use it. */
    val radio: Radio by lazy { Radio(this) }
}

val Context.radio: Radio get() = (applicationContext as SpectraApp).radio
