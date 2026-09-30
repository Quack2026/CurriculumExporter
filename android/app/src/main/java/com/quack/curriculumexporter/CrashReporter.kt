package com.quack.curriculumexporter

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常留痕。
 *
 * 真机闪退最难的地方是「崩了就没了」——手机上翻不到堆栈，连电脑抓 logcat 又要数据线和驱动。
 * 所以这里把异常写进应用自己的目录，下次启动时弹出来（可复制），
 * 用户把那段文字发过来就能定位，不用复现第二次。
 *
 * 注意：只记录，不吞异常。写完之后照常交给系统处理器，该弹「应用已停止」照弹。
 */
object CrashReporter {

    private const val CRASH_FILE = "last_crash.txt"

    /** 和 MainActivity 里那份是同一个文件名（同一个 App，共享一份设置）。 */
    private const val PREFS = "curriculum_prefs"
    private const val KEY_STEP = "last_step"

    private const val MAX_TRACE_CHARS = 60_000

    /** 只装一次。handler 是进程级的，装在入口 Activity 里就够。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 记录本身要是再抛异常，不能把原来的崩溃掩盖掉
            try {
                record(app, thread, error)
            } catch (ignored: Throwable) {
                // 忽略：留不下痕迹也比吞掉崩溃强
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 理论上到不了这里（系统默认会装一个），兜底保证进程真的退出
                Process.killProcess(Process.myPid())
            }
        }
    }

    /**
     * 记下「当前进行到哪一步」。
     * 崩溃堆栈有时看不出上下文（比如 OOM），配上最后一条进度就好判断了。
     */
    fun noteStep(context: Context, line: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STEP, line)
            .apply()
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        // PrintWriter 自带链式异常（Caused by）展开
        error.printStackTrace(PrintWriter(sw))

        val step = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STEP, null)

        val text = buildString {
            append("时间：").append(now()).append('\n')
            append("线程：").append(thread.name).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            if (!step.isNullOrBlank()) append("崩溃前最后一步：").append(step).append('\n')
            append("─── 堆栈 ───\n")
            val trace = sw.toString()
            append(if (trace.length > MAX_TRACE_CHARS) trace.take(MAX_TRACE_CHARS) + "\n…（已截断）" else trace)
        }

        File(context.filesDir, CRASH_FILE).writeText(text)
    }

    /** 取走上一次的崩溃记录；取到就删，免得每次启动都弹。 */
    fun takeLast(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            null
        }
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
