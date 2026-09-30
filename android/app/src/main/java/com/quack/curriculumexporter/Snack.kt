package com.quack.curriculumexporter

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * 底部浮出来的提示条，代替系统 Toast。
 *
 * 用 Toast 的毛病：Android 12+ 会给它加个应用图标、位置固定在最下面，
 * 而且样式完全不受本应用控制 —— 一个纯黑白灰的界面里冒出一块系统黄/蓝，
 * 整个观感就散了。这里自己画：反相底（亮色近黑、暗色近白）、圆角、
 * 从下往上浮出来，停两秒多再收回去。
 */
object Snack {

    private const val DURATION = 2600L

    private val shown = WeakHashMap<Activity, TextView>()
    private val timers = WeakHashMap<Activity, Runnable>()

    fun show(activity: Activity, message: CharSequence) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        dismiss(activity)

        val density = activity.resources.displayMetrics.density
        val padH = (18 * density).toInt()
        val padV = (13 * density).toInt()
        val accent = themeAccent(activity)

        val view = TextView(activity).apply {
            text = message
            setTextColor(UiTheme.onAccent(accent))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            setLineSpacing(3 * density, 1f)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(accent)
            }
            elevation = 8 * density
            alpha = 0f
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = (16 * density).toInt()
            marginStart = (24 * density).toInt()
            marginEnd = (24 * density).toInt()
        }
        root.addView(view, params)

        view.translationY = 48 * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()

        val timer = Runnable { dismiss(activity) }
        timers[activity] = timer
        shown[activity] = view
        view.postDelayed(timer, DURATION)
    }

    /** 收起当前这条（新提示要顶掉旧的，或者页面要走的时候）。 */
    fun dismiss(activity: Activity) {
        val view = shown.remove(activity) ?: return
        timers.remove(activity)?.let { view.removeCallbacks(it) }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(view.resources.displayMetrics.density * 32f)
            .setDuration(160)
            .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
            .start()
    }

    /**
     * 取当前主题实际在用的强调色。
     *
     * **别直接读 `R.color.accent`**：那个资源在 `values-v31` / `values-night-v31` 里指向系统动态色
     * （壁纸色系，实机上是淡紫）。「高级设置 → 外观」关掉动态色时走的是 `AppThemePlain`，
     * 按钮取 `@color/plain_accent`（黑白），而直接读资源会绕开主题 —— 于是纯黑白界面里
     * 冒出一条紫底提示条。改读主题属性，才跟按钮用的是同一个颜色。
     */
    private fun themeAccent(activity: Activity): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return activity.getColor(R.color.plain_accent)
        }
        return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
    }
}
