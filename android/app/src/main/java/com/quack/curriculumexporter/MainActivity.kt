package com.quack.curriculumexporter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
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
    private lateinit var fetchBtn: TextView
    private lateinit var rangeText: TextView
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

    /** 上一次见到的系统深浅色，用来判断 uiMode 是不是真的变了。 */
    private var nightMode = Configuration.UI_MODE_NIGHT_NO

    /** 抓取途中碰上了深浅色切换 → 记下来，等抓完再重建，别把半截结果丢掉。 */
    private var pendingThemeRecreate = false

    private val prefs by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.applyConfiguredTheme(this)
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        setContentView(R.layout.activity_main)
        SystemBars.install(this, findViewById(android.R.id.content), findViewById(R.id.topBar))

        userInput = findViewById(R.id.userInput)
        passInput = findViewById(R.id.passInput)
        fetchBtn = findViewById(R.id.fetchBtn)
        rangeText = findViewById(R.id.rangeText)
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

        // 单击按上次选定的范围直接开抓；想换范围就长按弹面板（面板里先选、再按「完成」）
        fetchBtn.setOnClickListener { if (running) requestCancel() else startFetchWithSavedRange() }
        fetchBtn.setOnLongClickListener {
            if (!running) showRangeMenu()
            // 抓取中按钮是「取消获取」，长按不弹菜单；无论如何都消费掉这次长按
            true
        }
        updateRangeLabel()

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

    /**
     * 系统切深色 / 浅色。
     *
     * uiMode 写在 configChanges 里（为了旋转屏幕、弹键盘时不重建页面），代价是系统不会
     * 再替我们重建 Activity，values-night 那套颜色也就不会换过来 —— 表现出来就是「切了
     * 深色，界面几乎没变」。所以这里自己判断：确认真的变了就重建一次。
     * 抓取中重建会丢掉半截结果，那种情况先记下来，等收尾时再补。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == nightMode) return
        nightMode = night
        if (running) pendingThemeRecreate = true else recreate()
    }

    override fun onResume() {
        super.onResume()
        if (::fetchBtn.isInitialized) {
            UiTheme.apply(this)
            // 周次会随时间往前跑，回到前台重新算一遍按钮下面写着的范围
            updateRangeLabel()
        }
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

                // 记下校历起点：下次按范围获取就能算出「现在第几周」，几秒完事
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

    /** 上次在长按面板里选的档位；下标对应 TermWeeks.ranges() 的固定顺序。 */
    private fun savedRangeIndex(): Int {
        val ranges = TermWeeks.ranges(TermWeeks.today(this))
        return SettingsStore.fetchRange(this).coerceIn(0, ranges.size - 1)
    }

    /**
     * 单击「获取课表」：直接按上次选定的范围开抓。
     *
     * 选定的是「近 N 周」这类要靠周次才算得出的档位，而校历起点还没拿到时，
     * 退成全面获取并说明原因 —— 总比默默抓错范围强。
     */
    private fun startFetchWithSavedRange() {
        val range = TermWeeks.ranges(TermWeeks.today(this))[savedRangeIndex()]
        if (range.needsFullFetch) {
            val note = getString(R.string.range_fallback_full)
            appendLog(note)
            showBanner(getString(R.string.banner_notice_title), note)
            startFetch(1, EduClient.DEFAULT_MAX_WEEK)
            return
        }
        startFetch(range.minWeek, range.maxWeek)
    }

    /** 把「单击会抓多少」写在按钮下面，否则用户没法知道按下去会发生什么。 */
    private fun updateRangeLabel() {
        val range = TermWeeks.ranges(TermWeeks.today(this))[savedRangeIndex()]
        rangeText.text = if (range.needsFullFetch) {
            getString(R.string.range_fallback_full)
        } else {
            getString(R.string.range_label, range.minWeek, range.maxWeek) +
                "\n" + getString(R.string.range_edit_hint)
        }
    }

    /**
     * 长按「获取课表」弹出的范围面板。
     *
     * 点一行只是把右边的圆形涂满，不会立刻开抓；按最下面的「完成」也只是把选中的档位
     * 记成默认值，真正开始获取仍然是按下面那颗「获取课表」——「设定范围」和「开跑」
     * 分成两步，免得选完就再也退不回去。
     */
    private fun showRangeMenu() {
        val current = TermWeeks.today(this)
        val ranges = TermWeeks.ranges(current)
        // 文案顺序与 TermWeeks.ranges() 的返回顺序一一对应
        val texts = listOf(
            R.string.range_full_retry to R.string.range_full_retry_hint,
            R.string.range_full_update to R.string.range_full_update_hint,
            R.string.range_5 to R.string.range_5_hint,
            R.string.range_3 to R.string.range_3_hint,
        )
        val items = ranges.mapIndexed { index, range ->
            val (label, desc) = texts[index]
            Sheet.Item(
                title = getString(label),
                desc = getString(desc),
                // 周次还不知道时后三档算不出来，直接禁用，比点了只弹一句提示更清楚
                enabled = !range.needsFullFetch,
            )
        }

        val saved = savedRangeIndex()
        val checked = if (items[saved].enabled) saved else 0

        Sheet.show(
            activity = this,
            title = getString(R.string.range_title),
            hint = if (current > 0) {
                getString(R.string.range_hint_known, current)
            } else {
                getString(R.string.range_hint_unknown)
            },
            items = items,
            checked = checked,
            doneText = getString(R.string.dlg_done),
            disabledHint = getString(R.string.range_need_full),
        ) { index ->
            // 只落默认范围，不在这里开抓：想抓的时候按下面的「获取课表」就好。
            SettingsStore.setFetchRange(this, index)
            updateRangeLabel()
        }
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
        // 抓取途中系统切过深浅色：现在没有正在跑的任务了，补上那次重建
        if (pendingThemeRecreate) {
            pendingThemeRecreate = false
            recreate()
        }
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
            setTextColor(getColor(R.color.mono_gray_text))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        // 堆栈可能很长，给它一个固定上限，别把对话框撑出屏幕
        val scroller = ScrollView(this).apply {
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.36f).toInt()
            )
        }

        DialogBox.show(
            activity = this,
            title = getString(R.string.crash_title),
            message = getString(R.string.crash_message),
            body = scroller,
            positive = getString(R.string.crash_copy),
            negative = getString(R.string.dlg_close),
        ) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return@show
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
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
