package com.quack.curriculumexporter

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
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

    fun onAccent(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) +
            0.114 * Color.blue(color)) / 255.0
        return if (luminance > 0.58) Color.BLACK else Color.WHITE
    }

    fun apply(activity: Activity) {
        // 顶栏（连同状态栏）刻意不跟 accent 走：暗色模式下 accent 是浅色（白胶囊 + 黑字），
        // 顶栏要是也涂成浅色，就会变成横在深色界面顶上的一条白带。它用独立的 topbar 资源，
        // 亮色近黑、暗色深灰，两边都是「深底浅字」。
        val topBarColor = activity.getColor(R.color.topbar)
        val onTopBar = onAccent(topBarColor)
        val topBar = activity.findViewById<View?>(R.id.topBar) ?: return
        topBar.background = ColorDrawable(topBarColor)
        if (topBar is ViewGroup) for (i in 0 until topBar.childCount) {
            when (val child = topBar.getChildAt(i)) {
                is TextView -> child.setTextColor(onTopBar)
                is ImageView -> child.setColorFilter(onTopBar)
            }
        }
        // 按钮背景（drawable）和文字色（color state list）都跟着主题的 colorAccent 走，
        // 所以这里不再运行时涂色 —— 一旦用 backgroundTint 上色，
        // 胶囊形状会被涂平，禁用态和涟漪也一起没了。
        activity.window.statusBarColor = topBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            var flags = 0
            if (onTopBar == Color.BLACK) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            activity.window.decorView.systemUiVisibility = flags
        }
    }
}
