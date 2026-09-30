package com.quack.curriculumexporter

import android.annotation.SuppressLint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 界面动效都走系统自带的 ViewPropertyAnimator / ValueAnimator，
 * 没有引入任何动画库 —— 这个 App 一共就 0 个第三方运行时依赖。
 */
object Anim {

    private const val ENTER_MS = 260L

    /** 一组控件依次淡入上浮，错开一点时间，比整块出现有层次。 */
    fun stagger(views: List<View>, startDelay: Long = 0L, step: Long = 55L) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(view, 10f)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + index * step)
                .setDuration(ENTER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * 按压反馈只改小控件的缩放，不触碰大文本内容。
     *
     * 这里刻意不调 performClick()：监听器返回 false，View 自己会在抬手时触发点击，
     * 再补一次就会让同一个按钮被响应两次。返回 false 是既定行为，不是漏写。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun press(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.98f).scaleY(0.98f).setDuration(80L).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
            false
        }
    }

    /** 提示条从上方滑入。 */
    fun revealBar(view: View) {
        // 正在收起时又被要求亮出来：先停掉那次动画 —— 它的收尾回调会把 visibility 设回 GONE，
        // 所以必须排在下面这行之前。
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = -dp(view, 6f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun hideBar(view: View) {
        view.animate()
            .alpha(0f)
            .translationY(-dp(view, 6f))
            .setDuration(160)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density
}
