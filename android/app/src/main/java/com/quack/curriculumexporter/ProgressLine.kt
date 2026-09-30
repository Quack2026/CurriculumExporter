package com.quack.curriculumexporter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 抓取进度条：浅色轨道 + 一块来回扫动的小段。
 *
 * 不用系统 ProgressBar —— 那个转圈在纯黑白主题下要么靠 tint 硬压、要么是彩色的，
 * 而且「还在跑」这件事，横向扫动比转圈更贴这个界面的直线条。
 * 动画在可见时才开始、不可见就停，不占后台帧。
 */
class ProgressLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var fraction = 0f
    private var animator: ValueAnimator? = null

    init {
        // 颜色在构造时取：跟随深浅色是靠 Activity 重建重解析资源，不是运行时切换
        trackPaint.color = context.getColor(R.color.app_surface_deep)
        barPaint.color = context.getColor(R.color.text_secondary)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val barWidth = w * 0.34f
        val left = (w - barWidth) * fraction
        rect.set(left, 0f, left + barWidth, h)
        canvas.drawRoundRect(rect, radius, radius, barPaint)
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        fraction = 0f
        invalidate()
    }
}
