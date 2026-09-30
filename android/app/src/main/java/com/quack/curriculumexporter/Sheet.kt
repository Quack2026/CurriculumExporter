package com.quack.curriculumexporter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 底部弹出面板。
 *
 * 交互约定：点一行只是把右边的圆形涂满（选中态），不会立刻执行任何动作；
 * 只有按最下面的「完成」才把选中的下标交回调用方，避免误触直接开跑。
 * 范围菜单和日历选择都用它，保证全应用的选择交互一致。
 *
 * 开合动画全部自己做（不用 window 的进出动画）：
 * 因为遮罩也在同一个布局里，只有自己驱动才能让「页面变暗」和「面板升上来」同步；
 * 顺带还拿到了拖拽收起 —— 抓住顶部那块往下拉就能关掉。
 */
object Sheet {

    /** 面板里的一行；desc 为空则不显示副标题，enabled=false 时整行压暗、点了只提示。 */
    data class Item(
        val title: String,
        val desc: String? = null,
        val enabled: Boolean = true
    )

    private val slideOut = DecelerateInterpolator(1.6f)
    private val accelerate = AccelerateInterpolator(1.8f)

    /**
     * @param checked       初始选中的下标
     * @param secondaryText 可选的次要动作（如「创建广理课表」），显示在「完成」下面
     */
    @SuppressLint("InflateParams") // 面板是塞进 Dialog 的，这里没有现成的 parent 可传
    fun show(
        activity: Activity,
        title: String,
        hint: String?,
        items: List<Item>,
        checked: Int,
        doneText: String,
        disabledHint: String? = null,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
        onDone: (Int) -> Unit
    ) {
        if (items.isEmpty()) return

        val root = LayoutInflater.from(activity).inflate(R.layout.sheet, null) as FrameLayout
        val scrim = root.findViewById<View>(R.id.sheetScrim)
        val panel = root.findViewById<LinearLayout>(R.id.sheetPanel)
        val handle = root.findViewById<View>(R.id.sheetHandle)
        val body = root.findViewById<LinearLayout>(R.id.sheetBody)
        val scroll = root.findViewById<ScrollView>(R.id.sheetScroll)
        val titleView = root.findViewById<TextView>(R.id.sheetTitle)
        val doneView = root.findViewById<TextView>(R.id.sheetConfirm)

        titleView.text = title

        val hintView = root.findViewById<TextView>(R.id.sheetHint)
        if (hint.isNullOrEmpty()) {
            hintView.visibility = View.GONE
        } else {
            hintView.text = hint
        }
        doneView.text = doneText

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.let { window ->
            // 圆角是 bg_sheet 自己画的，窗口底色必须透明，否则四角会露出方块。
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 遮罩由布局里的 sheetScrim 画，窗口自己的 dim 必须关掉，不然会叠成两层黑
            window.setDimAmount(0f)
        }
        // 取消逻辑全部自己接管：外部点击、返回键、抓把手下滑都要先播完收起动画
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        var closing = false
        fun close(after: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            scrim.animate().alpha(0f).setDuration(180).setInterpolator(accelerate).start()
            panel.animate()
                .translationY(panel.height.toFloat())
                .setDuration(220)
                .setInterpolator(accelerate)
                .withEndAction {
                    dialog.dismiss()
                    after?.invoke()
                }
                .start()
        }

        val secondaryView = root.findViewById<TextView>(R.id.sheetSecondary)
        if (secondaryText.isNullOrEmpty() || onSecondary == null) {
            secondaryView.visibility = View.GONE
        } else {
            secondaryView.text = secondaryText
            Anim.press(secondaryView, pressed = 0.96f)
            secondaryView.setOnClickListener { close(onSecondary) }
        }

        var selected = checked.coerceIn(0, items.size - 1)
        val rows = ArrayList<View>(items.size)
        // 入场动画的对象：行 + 行之间的细线，一起错峰出现
        val entries = ArrayList<View>(items.size * 2)

        val dividerHeight = Anim.dp(body, 1).coerceAtLeast(1)
        val dividerInset = Anim.dp(body, 12)

        items.forEachIndexed { index, item ->
            // 行之间加一条细线：几行连读数的时候，有它才像一份清单，而不是一堆散字
            if (index > 0) {
                val divider = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dividerHeight
                    ).apply {
                        marginStart = dividerInset
                        marginEnd = dividerInset
                    }
                    setBackgroundColor(activity.getColor(R.color.app_divider))
                }
                body.addView(divider)
                entries.add(divider)
            }

            val row = LayoutInflater.from(activity).inflate(R.layout.item_choice, body, false)
            row.findViewById<TextView>(R.id.choiceTitle).text = item.title

            val descView = row.findViewById<TextView>(R.id.choiceDesc)
            if (item.desc.isNullOrEmpty()) {
                descView.visibility = View.GONE
            } else {
                descView.text = item.desc
            }

            val dot = row.findViewById<ImageView>(R.id.choiceDot)
            row.isEnabled = item.enabled
            row.alpha = if (item.enabled) 1f else 0.38f
            row.isSelected = index == selected
            dot.isSelected = index == selected

            row.setOnClickListener {
                if (!item.enabled) {
                    if (disabledHint != null) {
                        Toast.makeText(activity, disabledHint, Toast.LENGTH_SHORT).show()
                    }
                    return@setOnClickListener
                }
                if (selected == index) return@setOnClickListener
                selected = index
                rows.forEachIndexed { i, view ->
                    val on = i == index
                    val viewDot = view.findViewById<ImageView>(R.id.choiceDot)
                    view.isSelected = on
                    viewDot.isSelected = on
                    // 被选中的那个圆点弹一记：「选中了」这件事要看得见
                    if (on) Anim.pulse(viewDot)
                }
            }

            body.addView(row)
            rows.add(row)
            entries.add(row)
        }

        Anim.press(doneView, pressed = 0.96f)
        doneView.setOnClickListener { close { onDone(selected) } }
        scrim.setOnClickListener { close() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                close()
                true
            } else {
                false
            }
        }

        // 抓着把手（或标题那块）往下拽就能收起面板
        val dragToClose = { close() }
        attachDrag(root, panel, scrim, handle, dragToClose)
        attachDrag(root, panel, scrim, titleView, dragToClose)

        // 预置成透明：dialog.show() 到第一帧动画之间有一小段间隙，不预置会闪一下
        scrim.alpha = 0f
        panel.alpha = 0f
        dialog.show()

        // 入场：遮罩淡进来，面板从屏幕下沿升上来，行再依次跟上
        root.post {
            val from = if (panel.height > 0) panel.height.toFloat() else Anim.dp(root, 320f)
            panel.translationY = from
            panel.alpha = 1f
            panel.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(slideOut)
                .start()

            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(220).start()

            entries.forEachIndexed { i, entry ->
                entry.alpha = 0f
                entry.translationY = Anim.dp(entry, 14f)
                entry.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(100 + i * 40L)
                    .setDuration(260)
                    .setInterpolator(slideOut)
                    .start()
            }
        }

        // 选项多的时候（比如日历列表）别让面板顶到状态栏，超过屏幕 2/3 就在内部滚动。
        root.post {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.66f).toInt()
            if (scroll.height > maxHeight) {
                scroll.layoutParams.height = maxHeight
                scroll.requestLayout()
            }
        }
    }

    /**
     * 把某个控件变成「往下拽 = 收起面板」的把手。
     *
     * 只绑把手和标题，不绑整个面板 —— 面板里还有可滚动的列表，
     * 整块都能拖的话，手指在列表上往下一划就分不清是滚列表还是收起面板了。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(
        root: View,
        panel: View,
        scrim: View,
        target: View,
        close: () -> Unit,
    ) {
        val touchSlop = Anim.dp(root, 4).toFloat()
        target.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        dragging = false
                        panel.animate().cancel()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val distance = event.rawY - startY
                        if (distance > touchSlop) dragging = true
                        if (dragging) {
                            panel.translationY = distance.coerceAtLeast(0f)
                            val limit = panel.height.coerceAtLeast(1)
                            scrim.alpha = (1f - panel.translationY / limit).coerceIn(0.2f, 1f)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val limit = panel.height.coerceAtLeast(1)
                        if (dragging && panel.translationY > limit * 0.28f) {
                            close()
                        } else {
                            panel.animate()
                                .translationY(0f)
                                .setDuration(240)
                                .setInterpolator(OvershootInterpolator(1.4f))
                                .start()
                            scrim.animate().alpha(1f).setDuration(200).start()
                        }
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })
    }
}
