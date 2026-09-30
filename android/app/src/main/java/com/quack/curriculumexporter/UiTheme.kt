package com.quack.curriculumexporter

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/** 在保持黑白基础风格的前提下，按高级设置启用或关闭系统动态 accent。 */
object UiTheme {
    fun applyConfiguredTheme(activity: Activity) {
        activity.setTheme(
            if (SettingsStore.dynamicTheme(activity)) R.style.AppTheme
            else R.style.AppThemePlain
        )
    }

    fun accent(activity: Activity): Int {
        if (SettingsStore.dynamicTheme(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return activity.getColor(R.color.accent)
        }
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    fun onAccent(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) +
            0.114 * Color.blue(color)) / 255.0
        return if (luminance > 0.58) Color.BLACK else Color.WHITE
    }

    fun apply(activity: Activity) {
        val accent = accent(activity)
        val onAccent = onAccent(accent)
        val topBar = activity.findViewById<View?>(R.id.topBar) ?: return
        topBar.background = ColorDrawable(accent)
        if (topBar is ViewGroup) for (i in 0 until topBar.childCount) {
            when (val child = topBar.getChildAt(i)) {
                is TextView -> child.setTextColor(onAccent)
                is ImageView -> child.setColorFilter(onAccent)
            }
        }
        listOf(
            R.id.fetchBtn,
            R.id.writeBtn,
            R.id.saveSettingsBtn,
        ).forEach { id ->
            activity.findViewById<Button?>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(onAccent)
            }
        }
        activity.window.statusBarColor = accent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            var flags = 0
            if (onAccent == Color.BLACK) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            activity.window.decorView.systemUiVisibility = flags
        }
    }
}
