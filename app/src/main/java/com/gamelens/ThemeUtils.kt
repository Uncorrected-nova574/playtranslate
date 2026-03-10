package com.gamelens

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes

/** Resolves a theme colour attribute to an ARGB int. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/** Returns the correct full-screen dialog theme for the user's selected palette. */
fun fullScreenDialogTheme(context: Context): Int = when (Prefs(context).themeIndex) {
    1    -> R.style.Theme_PlayTranslate_White_FullScreenDialog
    2    -> R.style.Theme_PlayTranslate_Rainbow_FullScreenDialog
    3    -> R.style.Theme_PlayTranslate_Purple_FullScreenDialog
    else -> R.style.Theme_PlayTranslate_FullScreenDialog
}
