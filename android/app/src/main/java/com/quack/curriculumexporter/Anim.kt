package com.quack.curriculumexporter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import java.util.WeakHashMap

/**
 * 界面动效都走系统自带的 ViewPropertyAnimator / ValueAnimator，
 * 没有引入任何动画库 —— 这个 App 一共就 0 个第三方运行时依赖。
 *
 * 手感约定：
 * - 按下 120ms 压到 95%，抬手 300ms 带一点过冲地弹回（OvershootInterpolator）。
 *   只有回弹带过冲，按下去才像按在实心按钮上，而不是凭空缩小又长回来。
 * - 入场固定「淡入 + 上浮 + 微缩放」，多个元素错峰出现。
 * - 所有缩放/位移只作用在小控件上。大容器（整页滚动内容）不做 alpha/translation
 *   动画：View 一旦 alpha<1 或 translationY!=0，绘制会走离屏合成，Skia 按控件完整
 *   尺寸开一张位图，几千像素高的容器会直接 OOM。所以设置页按卡片逐个入场，
 *   而不是给整块内容做一次淡入。
 */
object Anim {

    /** 按下 */
    private const val PRESS_MS = 120L

    /** 抬手回弹（带过冲） */
    private const val RELEASE_MS = 300L

    /** 单个元素入场 */
    private const val ENTER_MS = 320L

    private val easeOut = DecelerateInterpolator(1.8f)
    private val swiftOut = DecelerateInterpolator(2.6f)
    private val springBack = OvershootInterpolator(3.2f)

    // ------------------------------------------------------------ 入场

    /** 一组控件依次淡入上浮，错开一点时间，比整块出现有层次。 */
    fun stagger(views: List<View>, startDelay: Long = 0L, step: Long = 65L) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(view, 16f)
            view.scaleX = 0.98f
            view.scaleY = 0.98f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(startDelay + index * step)
                .setDuration(ENTER_MS)
                .setInterpolator(easeOut)
                .start()
        }
    }

    /**
     * 对容器里的每个直接子 View 依次入场。
     *
     * 设置页那种「一张卡片包住所有内容」的结构不能用 [stagger] 直接动整块，
     * 拆成按子项入场既好看，又避开大控件离屏合成的坑。
     */
    fun staggerChildren(group: ViewGroup, startDelay: Long = 0L, step: Long = 65L) {
        stagger((0 until group.childCount).map { group.getChildAt(it) }, startDelay, step)
    }

    // ------------------------------------------------------------ 反馈

    /**
     * 按压反馈：按下压一点点，抬手弹回来。
     *
     * 这里刻意不调 performClick()：监听器返回 false，View 自己会在抬手时触发点击，
     * 再补一次就会让同一个按钮被响应两次。返回 false 是既定行为，不是漏写。
     *
     * @param pressed 按下时缩到多少。整行卡片别缩太多（0.98），胶囊按钮可以到 0.94。
     * @param haptic  是否给一次轻触感。系统里关掉了触感反馈就不会震。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun press(view: View, pressed: Float = 0.95f, haptic: Boolean = true) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressed)
                        .scaleY(pressed)
                        .setDuration(PRESS_MS)
                        .setInterpolator(swiftOut)
                        .start()
                    if (haptic) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(RELEASE_MS)
                        .setInterpolator(springBack)
                        .start()
                }
            }
            false
        }
    }

    /** 状态刚变的那一下：弹一记，让「选中了」被看见（选项右边的圆点、开关之类）。 */
    fun pulse(view: View, peak: Float = 1.2f) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(peak)
            .scaleY(peak)
            .setDuration(130L)
            .setInterpolator(easeOut)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(RELEASE_MS)
                    .setInterpolator(springBack)
                    .start()
            }
            .start()
    }

    /** 浮层内容出现：从略小一点点放大到原尺寸。 */
    fun popIn(view: View, from: Float = 0.92f, startDelay: Long = 0L) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = from
        view.scaleY = from
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(startDelay)
            .setDuration(260)
            .setInterpolator(easeOut)
            .start()
    }

    /** 提示条从上方滑入。 */
    fun revealBar(view: View) {
        // 正在收起时又被要求亮出来：先停掉那次动画 —— 它的收尾回调会把 visibility 设回 GONE，
        // 所以必须排在下面这行之前。
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = -dp(view, 10f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(easeOut)
            .start()
    }

    fun hideBar(view: View) {
        view.animate()
            .alpha(0f)
            .translationY(-dp(view, 10f))
            .setDuration(170)
            .setInterpolator(swiftOut)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------ 忙闲提示

    private val breathers = WeakHashMap<View, ValueAnimator>()

    /** 正在忙：亮度来回走。用在「取消同步」这种长时间动作的按钮上。 */
    fun breathe(view: View) {
        stopBreathe(view)
        val animator = ValueAnimator.ofFloat(1f, 0.55f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
        breathers[view] = animator
    }

    fun stopBreathe(view: View) {
        breathers.remove(view)?.cancel()
        view.alpha = 1f
    }

    // ------------------------------------------------------------ 页面转场

    /** 进下一页：新页从右边推进来，当前页轻轻左移淡出。 */
    @Suppress("DEPRECATION")
    fun pageForward(activity: Activity) {
        activity.overridePendingTransition(R.anim.page_in, R.anim.page_out)
    }

    /** 回上一页：反着来。 */
    @Suppress("DEPRECATION")
    fun pageBack(activity: Activity) {
        activity.overridePendingTransition(R.anim.page_in_back, R.anim.page_out_back)
    }

    fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
