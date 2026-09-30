package com.quack.curriculumexporter

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * 兼容 Android 15+ 强制 edge-to-edge：状态栏区域由顶栏背景覆盖，
 * 页面内容同时让出状态栏和导航栏，避免标题与系统图标重叠。
 */
object SystemBars {

    fun install(activity: Activity, root: View, topBar: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val window = activity.window
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = activity.getColor(R.color.topbar)
        window.navigationBarColor = activity.getColor(R.color.app_background)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        val dark = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // 顶栏 / 状态栏始终是深色，系统图标要用浅色；导航栏跟着页面背景走 ——
        // 亮色页面是浅底，才需要深色图标。
        window.decorView.systemUiVisibility =
            if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val topHeight = activity.resources.getDimensionPixelSize(R.dimen.topbar_height)
        val topLeft = topBar.paddingLeft
        val topRight = topBar.paddingRight
        val topBottom = topBar.paddingBottom
        val rootLeft = root.paddingLeft
        val rootRight = root.paddingRight
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            val status = bars.top
            val navigation = bars.bottom

            // 顶栏整体延伸到状态栏下面，标题/返回按钮仍只占原来的 60dp 内容区。
            topBar.layoutParams = topBar.layoutParams.apply {
                height = topHeight + status
            }
            topBar.setPadding(topLeft, status, topRight, topBottom)
            root.setPadding(rootLeft, root.paddingTop, rootRight, navigation)
            insets
        }
        window.decorView.post { window.decorView.requestApplyInsets() }
    }
}
