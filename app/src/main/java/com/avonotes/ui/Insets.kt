package com.avonotes.ui

import android.view.View
import android.view.WindowInsets

/** Pads [view] by the system bar / cutout insets (the app draws edge-to-edge). */
fun applySystemBarInsets(view: View, top: Boolean = true, bottom: Boolean = true) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(i.left, if (top) i.top else 0, i.right, if (bottom) i.bottom else 0)
        insets
    }
    view.requestApplyInsets()
}
