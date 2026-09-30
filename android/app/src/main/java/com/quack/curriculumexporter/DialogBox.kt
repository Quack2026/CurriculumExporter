package com.quack.curriculumexporter

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自绘圆角对话框。
 *
 * 全应用的通知/确认都走这里：圆角、间距、按钮形态跟界面其它部分一致，
 * 而且只用 platform 公开的对话框主题作 parent，不碰平台内部 style 名。
 *
 * 遮罩和卡片都在布局里，窗口透明且不带 dim —— 这样关掉的时候也能先播一段
 * 「卡片缩回去、遮罩淡开」再真正 dismiss，而不是啪一下消失。
 */
object DialogBox {

    /**
     * @param message  正文，为空则整块隐藏
     * @param body     额外内容（比如崩溃详情那种长文本），由调用方自己构造
     * @param negative 传空则只显示一个按钮
     */
    @Suppress("InflateParams") // 内容是塞进 Dialog 的，这里没有现成的 parent 可传
    fun show(
        activity: Activity,
        title: CharSequence,
        message: CharSequence? = null,
        body: View? = null,
        positive: CharSequence,
        negative: CharSequence? = null,
        cancelable: Boolean = true,
        onNegative: (() -> Unit)? = null,
        onPositive: (() -> Unit)? = null
    ): Dialog {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null) as FrameLayout
        val scrim = root.findViewById<View>(R.id.dialogScrim)
        val card = root.findViewById<LinearLayout>(R.id.dialogCard)

        card.findViewById<TextView>(R.id.dialogTitle).text = title

        val messageView = card.findViewById<TextView>(R.id.dialogMessage)
        if (message.isNullOrEmpty()) {
            messageView.visibility = View.GONE
        } else {
            messageView.text = message
        }

        val bodyHolder = card.findViewById<LinearLayout>(R.id.dialogBody)
        if (body == null) {
            bodyHolder.visibility = View.GONE
        } else {
            bodyHolder.addView(body)
        }

        // 卡片宽度：Alert 主题那种「按内容量宽度」在长文本下会贴边，统一压到屏宽 88%
        card.layoutParams = card.layoutParams.apply {
            width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
        }

        val dialog = Dialog(activity, R.style.AppDialogTheme)
        dialog.setContentView(root)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 窗口自己的 dim 关掉：遮罩由布局画，才有的淡入
            window.setDimAmount(0f)
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        var closing = false
        fun close(after: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            scrim.animate().alpha(0f).setDuration(160).start()
            card.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(160)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .withEndAction {
                    dialog.dismiss()
                    after?.invoke()
                }
                .start()
        }

        val negativeView = card.findViewById<TextView>(R.id.dialogNegative)
        if (negative.isNullOrEmpty()) {
            negativeView.visibility = View.GONE
        } else {
            negativeView.text = negative
            Anim.press(negativeView, pressed = 0.94f)
            negativeView.setOnClickListener { close(onNegative) }
        }

        val positiveView = card.findViewById<TextView>(R.id.dialogPositive)
        positiveView.text = positive
        Anim.press(positiveView, pressed = 0.94f)
        positiveView.setOnClickListener { close(onPositive) }

        if (cancelable) {
            scrim.setOnClickListener { close() }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (cancelable) close()
                true
            } else {
                false
            }
        }

        // 先藏起来：dialog.show() 到第一帧动画之间有一下的间隙，不预置成透明会闪一下
        card.alpha = 0f
        scrim.alpha = 0f
        dialog.show()

        root.post {
            scrim.animate().alpha(1f).setDuration(200).start()
            Anim.popIn(card, from = 0.9f)
        }
        return dialog
    }
}
