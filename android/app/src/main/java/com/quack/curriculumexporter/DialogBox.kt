package com.quack.curriculumexporter

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自绘圆角对话框。
 *
 * 全应用的通知/确认都走这里：圆角、间距、按钮形态跟界面其它部分一致，
 * 而且只用 platform 公开的对话框主题作 parent，不碰平台内部 style 名。
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
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
        content.findViewById<TextView>(R.id.dialogTitle).text = title

        val messageView = content.findViewById<TextView>(R.id.dialogMessage)
        if (message.isNullOrEmpty()) {
            messageView.visibility = View.GONE
        } else {
            messageView.text = message
        }

        val bodyHolder = content.findViewById<LinearLayout>(R.id.dialogBody)
        if (body == null) {
            bodyHolder.visibility = View.GONE
        } else {
            bodyHolder.addView(body)
        }

        val dialog = Dialog(activity, R.style.AppDialogTheme)
        dialog.setContentView(content)

        val negativeView = content.findViewById<TextView>(R.id.dialogNegative)
        if (negative.isNullOrEmpty()) {
            negativeView.visibility = View.GONE
        } else {
            negativeView.text = negative
            negativeView.setOnClickListener {
                dialog.dismiss()
                onNegative?.invoke()
            }
        }

        val positiveView = content.findViewById<TextView>(R.id.dialogPositive)
        positiveView.text = positive
        positiveView.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }

        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.show()

        // Alert 主题按内容量宽度，统一压到屏宽 88%，两侧留白才正常。
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }
}
