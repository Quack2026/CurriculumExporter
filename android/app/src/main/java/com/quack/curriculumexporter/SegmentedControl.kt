package com.quack.curriculumexporter

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 自写的分段控件：浅灰圆角轨道 + 一块白色滑块平移，配文字颜色渐变。
 *
 * 不用 RadioButton —— 系统那套在黑白主题下会渲染成两个小圆点，又丑又占地方，
 * 而且很难调成"整块滑动"的观感。
 */
class SegmentedControl(
    private val track: FrameLayout,
    private val thumb: View,
    private val labels: List<TextView>,
) {

    var selected: Int = 0
        private set

    private var listener: ((Int) -> Unit)? = null
    private var lastWidth = -1
    private val argb = ArgbEvaluator()
    private val activeColor = track.context.getColor(R.color.text_primary)
    private val idleColor = track.context.getColor(R.color.text_secondary)

    init {
        labels.forEachIndexed { index, label ->
            label.setOnClickListener { select(index) }
        }
        // 滑块宽度得等轨道量完才知道，所以挂在布局监听上；lastWidth 去重，避免反复 requestLayout
        track.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val width = segmentWidth()
                    if (width <= 0 || width == lastWidth) return
                    lastWidth = width
                    syncThumbWidth(width)
                    moveThumb(selected, animate = false)
                }
            }
        )
        paint(selected, animate = false)
    }

    fun onSelect(action: (Int) -> Unit) {
        listener = action
    }

    fun select(index: Int, animate: Boolean = true, notify: Boolean = true) {
        if (index !in labels.indices) return
        selected = index
        moveThumb(index, animate)
        paint(index, animate)
        if (notify) listener?.invoke(index)
    }

    private fun segmentWidth(): Int {
        if (labels.isEmpty()) return 0
        val usable = track.width - track.paddingLeft - track.paddingRight
        return usable / labels.size
    }

    private fun syncThumbWidth(width: Int) {
        val params = thumb.layoutParams
        if (params.width == width) return
        params.width = width
        thumb.layoutParams = params
        thumb.post { moveThumb(selected, animate = false) }
    }

    private fun moveThumb(index: Int, animate: Boolean) {
        val target = (index * segmentWidth()).toFloat()
        if (animate) {
            thumb.animate()
                .translationX(target)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            thumb.translationX = target
        }
    }

    private fun paint(index: Int, animate: Boolean) {
        labels.forEachIndexed { i, label ->
            val to = if (i == index) activeColor else idleColor
            if (!animate) {
                label.setTextColor(to)
                return@forEachIndexed
            }
            val from = label.currentTextColor
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    label.setTextColor(argb.evaluate(fraction, from, to) as Int)
                }
                start()
            }
        }
    }
}
