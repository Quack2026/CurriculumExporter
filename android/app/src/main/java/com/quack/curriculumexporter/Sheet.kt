package com.quack.curriculumexporter

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
 */
object Sheet {

    /** 面板里的一行；desc 为空则不显示副标题，enabled=false 时整行压暗、点了只提示。 */
    data class Item(
        val title: String,
        val desc: String? = null,
        val enabled: Boolean = true
    )

    /**
     * @param checked       初始选中的下标
     * @param secondaryText 可选的次要动作（如「创建广理课表」），显示在「完成」下面
     */
    @Suppress("InflateParams") // 面板是塞进 Dialog 的，这里没有现成的 parent 可传
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

        val content = LayoutInflater.from(activity).inflate(R.layout.sheet, null)
        val body = content.findViewById<LinearLayout>(R.id.sheetBody)
        val scroll = content.findViewById<ScrollView>(R.id.sheetScroll)
        val doneView = content.findViewById<TextView>(R.id.sheetConfirm)

        content.findViewById<TextView>(R.id.sheetTitle).text = title

        val hintView = content.findViewById<TextView>(R.id.sheetHint)
        if (hint.isNullOrEmpty()) {
            hintView.visibility = View.GONE
        } else {
            hintView.text = hint
        }
        doneView.text = doneText

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)

        val secondaryView = content.findViewById<TextView>(R.id.sheetSecondary)
        if (secondaryText.isNullOrEmpty() || onSecondary == null) {
            secondaryView.visibility = View.GONE
        } else {
            secondaryView.text = secondaryText
            secondaryView.setOnClickListener {
                dialog.dismiss()
                onSecondary()
            }
        }

        var selected = checked.coerceIn(0, items.size - 1)
        val rows = ArrayList<View>(items.size)

        items.forEachIndexed { index, item ->
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
                selected = index
                rows.forEachIndexed { i, view ->
                    val on = i == index
                    view.isSelected = on
                    view.findViewById<ImageView>(R.id.choiceDot).isSelected = on
                }
            }

            body.addView(row)
            rows.add(row)
        }

        doneView.setOnClickListener {
            dialog.dismiss()
            onDone(selected)
        }

        dialog.window?.let { window ->
            // 圆角是 bg_sheet 自己画的，窗口底色必须透明，否则四角会露出方块。
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.5f)
            window.setWindowAnimations(R.style.SheetAnim)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        // 选项多的时候（比如日历列表）别让面板顶到状态栏，超过屏幕 2/3 就在内部滚动。
        content.post {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.66f).toInt()
            if (scroll.height > maxHeight) {
                scroll.layoutParams.height = maxHeight
                scroll.requestLayout()
            }
        }
    }
}
