package com.connorb.omnitv

import android.content.Context
import android.graphics.Typeface

object Fonts {
    private var regularCache: Typeface? = null
    private var extraLightCache: Typeface? = null

    /** Para títulos y cabeceras. */
    fun regular(context: Context): Typeface =
        regularCache ?: Typeface.createFromAsset(
            context.applicationContext.assets, "PulpDisplay-Regular.ttf"
        ).also { regularCache = it }

    /** Para nombres de canal y labels normales. */
    fun extraLight(context: Context): Typeface =
        extraLightCache ?: Typeface.createFromAsset(
            context.applicationContext.assets, "PulpDisplay-ExtraLight.ttf"
        ).also { extraLightCache = it }
}
