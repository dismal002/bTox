// SPDX-FileCopyrightText: 2026 bTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.settings

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.Toolbar
import android.view.View
import android.content.res.ColorStateList
import com.dismal.btox.R

object AppColorResolver {
    fun primary(context: Context, fallbackColorRes: Int = R.color.colorPrimary): Int {
        return Settings(context).appColorValue()
    }

    fun primaryDark(context: Context, fallbackColorRes: Int = R.color.colorPrimaryDark): Int {
        val base = primary(context, R.color.colorPrimary)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        // If it's already very dark, don't darken it further or handle black specially
        if (hsv[2] < 0.1f) return Color.BLACK
        hsv[2] = (hsv[2] * 0.78f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    fun applyToToolbar(toolbar: Toolbar) {
        val color = primary(toolbar.context)
        toolbar.setBackgroundColor(color)
    }

    fun applyToFab(view: View) {
        val color = primary(view.context)
        view.backgroundTintList = ColorStateList.valueOf(color)
    }

    fun resolve(context: Context, attr: Int, fallbackColorRes: Int): Int = when (attr) {
        androidx.appcompat.R.attr.colorPrimary,
        androidx.appcompat.R.attr.colorAccent,
        android.R.attr.colorAccent,
        -> primary(context, fallbackColorRes)
        androidx.appcompat.R.attr.colorPrimaryDark,
        android.R.attr.statusBarColor,
        -> primaryDark(context, fallbackColorRes)
        else -> resolveThemeColor(context, attr, fallbackColorRes)
    }

    private fun resolveThemeColor(context: Context, attr: Int, fallbackColorRes: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        } else {
            ContextCompat.getColor(context, fallbackColorRes)
        }
    }
}
