package com.quack.curriculumexporter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：输入学号密码 → 抓整学期 → 跳到结果页。
 *
 * 这里只负责「抓」，落盘（写日历 / 存 .ics）都在结果页，用户能先看一眼课表对不对。
 */
class MainActivity : Activity() {

    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var fetchBtn: Button
    private lateinit var militarySwitch: Switch
    private lateinit var logView: TextView
    private lateinit var mainScroll: ScrollView
    private lateinit var busyBar: View
    private lateinit var cardInput: View
    private lateinit var cardOption: View
    private lateinit var settingsBtn: View
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerClose: ImageView

    /** 抓取线程和 UI 线程共享，所以都是 @Volatile。 */
    @Volatile
    private var running = false

    @Volatile
    private var cancelRequested = false

    /** 抓取结束时 App 不在前台 → 先记下来，回到前台再跳（Android 10+ 不许后台起 Activity）。 */
    private var pendingPreview = false

    private val prefs by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.applyConfiguredTheme(this)
        setContentView(R.layout.activity_main)
        SystemBars.install(this, findViewById(android.R.id.content), findViewById(R.id.topBar))

        userInput = findViewById(R.id.userInput)
        passInput = findViewById(R.id.passInput)
        fetchBtn = findViewById(R.id.fetchBtn)
        militarySwitch = findViewById(R.id.militarySwitch)
        logView = findViewById(R.id.logView)
        mainScroll = findViewById(R.id.mainScroll)
        busyBar = findViewById(R.id.busyBar)
        cardInput = findViewById(R.id.cardInput)
        cardOption = findViewById(R.id.cardOption)
        settingsBtn = findViewById(R.id.settingsBtn)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerText = findViewById(R.id.bannerText)
        bannerClose = findViewById(R.id.bannerClose)
        UiTheme.apply(this)

        userInput.setText(prefs.getString(KEY_USER, ""))
        if (SettingsStore.savePassword(this)) {
            passInput.setText(SecureCredentials.loadPassword(this).orEmpty())
        }
        militarySwitch.isChecked = prefs.getBoolean(KEY_MILITARY, true)

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        Anim.press(settingsBtn)
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_label, AppInfo.versionName(this))
        fetchBtn.setOnClickListener { if (running) requestCancel() else startFetch() }
        // 长按给一个「只抓近几周」的快捷入口：整学期 20 个请求要十秒上下，日常更新用不着那么久
        fetchBtn.setOnLongClickListener {
            if (!running) showRangeMenu()
            // 抓取中按钮是「取消获取」，长按不弹菜单；无论如何都消费掉这次长按
            true
        }
        bannerClose.setOnClickListener { Anim.hideBar(banner) }
        // 开始输入就把提示条收起来，别挡着人看输入框。
        // 刻意用「点击」而不是 onFocusChange：对话框一关，输入框会「重新获得焦点」，
        // 那个焦点回调会把刚弹出来的提示条又收掉。
        val hideOnTap = View.OnClickListener { hideBanner() }
        userInput.setOnClickListener(hideOnTap)
        passInput.setOnClickListener(hideOnTap)

        // 尽早装上：之后不管哪一步崩，都能留下堆栈
        CrashReporter.install(this)

        playIntro()
        showLastCrashIfAny()
    }

    override fun onResume() {
        super.onResume()
        if (::fetchBtn.isInitialized) UiTheme.apply(this)
        if (pendingPreview) {
            pendingPreview = false
            openResult()
        }
    }

    override fun onBackPressed() {
        // 抓取中按返回先当「取消」，免得手滑退出又把半截数据丢掉
        if (running) {
            requestCancel()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------ 抓取流程

    private fun startFetch(
        minWeek: Int = 1,
        maxWeek: Int = EduClient.DEFAULT_MAX_WEEK,
    ) {
        val userNo = userInput.text.toString().trim()
        val password = passInput.text.toString()
        val withMilitary = militarySwitch.isChecked

        if (userNo.isEmpty()) {
            showBanner(getString(R.string.banner_login_title), "先填学号。")
            userInput.requestFocus()
            return
        }
        if (password.isEmpty()) {
            showBanner(getString(R.string.banner_login_title), "先填密码。")
            passInput.requestFocus()
            return
        }

        prefs.edit()
            .putString(KEY_USER, userNo)
            .putBoolean(KEY_MILITARY, withMilitary)
            .apply()
        if (SettingsStore.savePassword(this)) {
            try {
                SecureCredentials.savePassword(this, password)
            } catch (e: Exception) {
                appendLog("密码保存失败，仍会继续获取：${e.javaClass.simpleName}")
            }
        } else {
            SecureCredentials.clearPassword(this)
        }

        hideKeyboard()
        hideBanner()

        val wholeTerm = minWeek <= 1 && maxWeek >= EduClient.DEFAULT_MAX_WEEK

        running = true
        cancelRequested = false
        fetchBtn.text = getString(R.string.btn_cancel)
        busyBar.visibility = View.VISIBLE
        setInputsEnabled(false)
        logView.text = ""
        appendLog("开始：$userNo")

        Thread {
            try {
                val client = EduClient()
                appendLog("正在登录教务系统…")
                client.login(userNo, password)

                val student = client.student
                val who = listOf(student.name, student.clsName, student.academy)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                appendLog("登录成功${if (who.isEmpty()) "" else "：$who"}")
                appendLog(
                    if (wholeTerm) "开始抓取整学期（共 $maxWeek 周）…"
                    else "开始抓取第 $minWeek~$maxWeek 周…"
                )

                val schedule = client.fetchAll(
                    minWeek = minWeek,
                    maxWeek = maxWeek,
                    onProgress = ::appendLog,
                    shouldStop = { cancelRequested },
                )

                // 记下校历起点：下次长按「获取课表」就能只抓近几周，几秒完事
                TermWeeks.remember(this, schedule)

                val events = IcsBuilder.events(
                    schedule = schedule,
                    includeMilitary = withMilitary,
                    onSkip = ::appendLog,
                )

                runOnUiThread { onFetched(schedule, events, withMilitary) }
            } catch (e: EduException) {
                runOnUiThread { onFailed(e.message ?: "获取失败") }
            } catch (e: Exception) {
                runOnUiThread { onFailed("出了点意外：${e.message ?: e.javaClass.simpleName}") }
            } finally {
                runOnUiThread { finishFetchingUi() }
            }
        }.start()
    }

    private fun requestCancel() {
        if (cancelRequested) return
        cancelRequested = true
        fetchBtn.text = getString(R.string.btn_cancelling)
        appendLog("收到取消，正在收尾…")
    }

    // ------------------------------------------------------------ 获取范围

    /**
     * 长按「获取课表」弹出的范围菜单。
     *
     * 用自定义视图而不是 `AlertDialog.setItems()`：平台 Material 主题下那份列表不渲染
     * （和选日历对话框同一个坑）。
     */
    private fun showRangeMenu() {
        val current = TermWeeks.today(this)
        val content = layoutInflater.inflate(R.layout.dialog_fetch_range, null)

        val hint = content.findViewById<TextView>(R.id.rangeHint)
        hint.text = if (current > 0) {
            getString(R.string.range_hint_known, current)
        } else {
            getString(R.string.range_hint_unknown)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.range_title)
            .setView(content)
            .setNegativeButton(R.string.dlg_cancel, null)
            .create()

        val list = content.findViewById<LinearLayout>(R.id.rangeList)
        // 文案顺序与 TermWeeks.ranges() 的返回顺序一一对应
        val texts = listOf(
            R.string.range_full_retry to R.string.range_full_retry_hint,
            R.string.range_full_update to R.string.range_full_update_hint,
            R.string.range_5 to R.string.range_5_hint,
            R.string.range_3 to R.string.range_3_hint,
        )
        texts.zip(TermWeeks.ranges(current)).forEach { (text, range) ->
            val (label, desc) = text
            val row = layoutInflater.inflate(R.layout.item_fetch_range, list, false)
            row.findViewById<TextView>(R.id.rangeLabel).text = getString(label)
            row.findViewById<TextView>(R.id.rangeDesc).text = getString(desc)
            row.setOnClickListener {
                dialog.dismiss()
                if (range.needsFullFetch) {
                    // 还不知道今天第几周：给个明确的下一步，而不是默默抓错范围
                    val note = getString(R.string.range_need_full)
                    appendLog(note)
                    showBanner(getString(R.string.banner_notice_title), note)
                    return@setOnClickListener
                }
                startFetch(range.minWeek, range.maxWeek)
            }
            list.addView(row)
        }

        dialog.show()
    }

    private fun onFetched(schedule: Schedule, events: List<IcsEvent>, withMilitary: Boolean) {
        if (schedule.weeks.isEmpty()) {
            onFailed("一周都没抓到。换个网络（或关掉代理）再试一次。")
            return
        }
        if (schedule.missingWeeks.isNotEmpty()) {
            appendLog("注意：第 ${schedule.missingWeeks.joinToString("、")} 周没抓到。")
        }
        appendLog("完成：${schedule.weeks.size} 周、${events.size} 个日历事件。")

        AppState.schedule = schedule
        AppState.events = events
        AppState.includeMilitary = withMilitary

        // 不在前台时不能起 Activity，回到前台由 onResume 补跳
        if (hasWindowFocus()) {
            appendLog("正在打开课表预览…")
            openResult()
        } else {
            pendingPreview = true
            appendLog("抓好了。回到本应用就会自动打开预览。")
        }
    }

    private fun onFailed(message: String) {
        appendLog("失败：$message")
        showBanner(getString(R.string.banner_fail_title), message)
    }

    private fun finishFetchingUi() {
        running = false
        cancelRequested = false
        fetchBtn.text = getString(R.string.btn_fetch)
        busyBar.visibility = View.GONE
        setInputsEnabled(true)
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold)
    }

    // ------------------------------------------------------------ 小工具

    private fun setInputsEnabled(enabled: Boolean) {
        userInput.isEnabled = enabled
        passInput.isEnabled = enabled
        militarySwitch.isEnabled = enabled
    }

    private fun appendLog(line: String) {
        // 崩溃时能知道停在哪一步（堆栈有时看不出上下文）
        CrashReporter.noteStep(this, line)
        runOnUiThread {
            if (logView.text.isNotEmpty()) logView.append("\n")
            logView.append(line)
            mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 上次运行崩过 → 把堆栈摊出来。
     *
     * 手机上加不了断点，这段文字就是唯一线索，所以做成可滚动 + 可选中，再给一个一键复制。
     */
    private fun showLastCrashIfAny() {
        val trace = CrashReporter.takeLast(this) ?: return

        val detail = TextView(this).apply {
            text = trace
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.mono_black))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setView(ScrollView(this).apply { addView(detail) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.crash_copy) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@setNeutralButton
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showBanner(title: String, text: String) {
        bannerTitle.text = title
        bannerText.text = text
        // 对话框刚 dismiss（或者刚 requestFocus）时，输入框会「重新」获得焦点，
        // 那个焦点回调会顺手把刚亮出来的提示条又收掉。推迟一帧再亮，稳定压在它后面。
        banner.post { Anim.revealBar(banner) }
        mainScroll.post { mainScroll.fullScroll(View.FOCUS_UP) }
    }

    private fun hideBanner() {
        if (banner.visibility == View.VISIBLE) Anim.hideBar(banner)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(userInput.windowToken, 0)
    }

    private fun playIntro() {
        // 日志会在抓取过程中持续增长，不参与 alpha/translation 动画。
        Anim.stagger(listOf(cardInput, cardOption, fetchBtn, settingsBtn))
    }

    private companion object {
        const val PREFS = "curriculum_prefs"
        const val KEY_USER = "last_user"
        const val KEY_MILITARY = "military_event"
    }
}
