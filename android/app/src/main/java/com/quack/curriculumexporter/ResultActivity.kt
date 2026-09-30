package com.quack.curriculumexporter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView

/**
 * 结果页：先让用户看一眼课表对不对，再决定往哪落。
 *
 * 两个出口都保留了 —— 直接写系统日历（手机上的最短路径），或者导出 .ics 自己导/发给同学。
 */
class ResultActivity : Activity() {

    private lateinit var infoTitle: TextView
    private lateinit var infoStats: TextView
    private lateinit var previewText: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var writeBtn: TextView
    private lateinit var exportBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var segmented: SegmentedControl

    private lateinit var schedule: Schedule

    private var savedUri: Uri? = null
    private var savedName: String = ""

    /** 走存储权限时用户点的是「保存」还是「保存并分享」，授权回来后接着做完。 */
    private var pendingShareAfterGrant = false

    /** 上一次见到的系统深浅色，用来判断 uiMode 是不是真的变了。 */
    private var nightMode = Configuration.UI_MODE_NIGHT_NO

    /** 同 MainActivity：uiMode 被 configChanges 拦住了，系统不会重建，深浅色得自己换一次。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == nightMode) return
        nightMode = night
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.applyConfiguredTheme(this)
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val current = AppState.schedule
        if (current == null) {
            // 进程被回收过，静态状态没了：回主界面重抓
            finish()
            return
        }
        schedule = current
        setContentView(R.layout.activity_result)
        SystemBars.install(this, findViewById(android.R.id.content), findViewById(R.id.topBar))
        UiTheme.apply(this)

        infoTitle = findViewById(R.id.infoTitle)
        infoStats = findViewById(R.id.infoStats)
        previewText = findViewById(R.id.previewText)
        resultScroll = findViewById(R.id.resultScroll)
        writeBtn = findViewById(R.id.writeBtn)
        exportBtn = findViewById(R.id.exportBtn)
        shareBtn = findViewById(R.id.shareBtn)

        findViewById<ImageView>(R.id.backBtn).setOnClickListener { finish() }

        segmented = SegmentedControl(
            findViewById(R.id.segMode),
            findViewById(R.id.segModeThumb),
            listOf(findViewById(R.id.segWeek), findViewById(R.id.segCourse)),
        )
        segmented.onSelect { index -> switchMode(byCourse = index == 1) }

        renderSummary()
        previewText.text = SchedulePreview.byWeek(this, schedule)

        writeBtnLabel = writeBtn.text
        writeBtn.setOnClickListener { onWriteButton() }
        exportBtn.setOnClickListener { saveIcs(shareAfter = false) }
        // 已经从这边分享过就复用那份文件；没分享过就先存一份再弹系统分享面板
        shareBtn.setOnClickListener {
            val uri = savedUri
            if (uri != null) share(savedName, uri) else saveIcs(shareAfter = true)
        }

        Anim.press(findViewById(R.id.backBtn))
        Anim.press(writeBtn)
        Anim.press(exportBtn)
        Anim.press(shareBtn)

        playIntro()
    }

    override fun finish() {
        super.finish()
        Anim.pageBack(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                doSaveIcs(pendingShareAfterGrant)
            } else {
                notice("没拿到存储权限，文件存不下来。可以改用「写入系统日历」。")
            }
            return
        }
        if (requestCode != REQ_CALENDAR) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            chooseCalendar()
        } else {
            notice("没拿到日历权限。可以改用「保存 .ics」，再自己导进日历。")
        }
    }

    // ------------------------------------------------------------ 展示

    private fun renderSummary() {
        val who = listOf(schedule.student.name, schedule.student.clsName)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        infoTitle.text = who.ifEmpty { getString(R.string.result_title) }

        val lines = ArrayList<String>()
        lines += IcsBuilder.CALENDAR_NAME
        val courseCount = schedule.weeks.sumOf { it.courses.size }
        lines += "${schedule.weeks.size} 周 · $courseCount 条课程 · ${AppState.events.size} 个日历事件"
        if (schedule.missingWeeks.isNotEmpty()) {
            lines += "第 ${schedule.missingWeeks.joinToString("、")} 周没抓到"
        }
        if (!wholeTermFetched()) {
            lines += getString(R.string.partial_fetch_note, schedule.minWeek, schedule.maxWeek)
        }
        infoStats.text = lines.joinToString("\n")
    }

    /**
     * 是不是整学期都请求了（长按「获取课表」可以只抓近几周）。
     *
     * 看的是**请求范围**而不是抓到的周：军训周、开学前后那种空课表周本来就不会出现在
     * [Schedule.weeks] 里，拿 weeks 判断会把整学期获取误判成"只抓了一部分"。
     */
    private fun wholeTermFetched(): Boolean {
        if (schedule.maxWeek <= 0) return true
        return schedule.minWeek <= 1 && schedule.maxWeek >= EduClient.DEFAULT_MAX_WEEK
    }

    private fun switchMode(byCourse: Boolean) {
        // 这里刻意不给 previewText 做淡入淡出：previewText 里装着整学期课表（上千行、
        // 几万像素高），对它做 alpha 动画等于让滚动容器每帧整块重绘 —— View 一旦
        // alpha<1 或 translationY!=0，绘制就走离屏合成，会照控件尺寸开一张大位图。
        // 与其担这个渲染风险，不如把「翻页感」交给滚动：换完内容平滑滚回顶部，
        // 这一步只动滚动偏移，不碰任何 View 的绘制属性。
        previewText.text =
            if (byCourse) SchedulePreview.byCourse(this, schedule)
            else SchedulePreview.byWeek(this, schedule)
        resultScroll.post { resultScroll.smoothScrollTo(0, 0) }
    }

    private fun playIntro() {
        // 注意：previewText 刻意不参与入场动画。
        // 它装满一整学期后有一千多行、好几万像素高，对它做每帧 alpha/translationY 动画
        // 会让滚动容器反复整块重绘，在部分机型/系统上会直接触发渲染层崩溃。
        // 少这一个淡入，看不出差别。
        Anim.stagger(
            listOf(
                findViewById<View>(R.id.infoArea),
                findViewById<View>(R.id.segMode),
                findViewById<View>(R.id.actionArea),
            )
        )
    }

    // ------------------------------------------------------------ 写系统日历

    private fun startWriteToCalendar() {
        if (AppState.events.isEmpty()) {
            notice("没有可写入的事件。")
            return
        }
        if (!CalendarWriter.hasPermission(this)) {
            DialogBox.show(
                activity = this,
                title = getString(R.string.perm_calendar_title),
                message = getString(R.string.perm_calendar_message),
                positive = getString(R.string.perm_calendar_ok),
                negative = getString(R.string.perm_calendar_no),
                onNegative = { saveIcs(shareAfter = false) },
            ) {
                @Suppress("DEPRECATION")
                requestPermissions(CalendarWriter.PERMISSIONS, REQ_CALENDAR)
            }
            return
        }
        if (SettingsStore.useCalendarPicker(this)) chooseCalendar()
        else autoWriteCalendar()
    }

    private fun autoWriteCalendar() {
        val writer = CalendarWriter(this)
        setActionsEnabled(false)
        Thread {
            try {
                val calendar = writer.createCourseCalendar(SettingsStore.calendarName(this))
                runOnUiThread {
                    setActionsEnabled(true)
                    confirmAndWrite(writer, calendar)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionsEnabled(true)
                    showCalendarError("自动创建或更新日历失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun chooseCalendar() {
        val writer = CalendarWriter(this)
        val showAll = SettingsStore.showAllCalendars(this)
        val calendars = try {
            if (showAll) writer.allCalendarsForPicker()
            else writer.writableCalendars()
        } catch (e: Exception) {
            notice("读不到手机里的日历：${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (calendars.isEmpty()) {
            DialogBox.show(
                activity = this,
                title = getString(R.string.dlg_calendar_title),
                message = getString(R.string.dlg_no_calendar),
                positive = getString(R.string.dlg_create_calendar),
                negative = getString(R.string.dlg_cancel),
            ) { createAndWriteCalendar(writer) }
            return
        }

        showCalendarPicker(writer, calendars, showAll)
    }

    /**
     * 日历列表用同一套底部面板：左边日历名，右边圆形选择点，选完按「完成」才写。
     *
     * 刻意不走 `AlertDialog.setItems()`：平台 Material 主题下那份列表在真机上根本不渲染，
     * 对话框只剩标题和两个按钮，用户一个日历都看不到也没得选，
     * 「启用系统日历选择」这个设置等于白开。
     */
    private fun showCalendarPicker(
        writer: CalendarWriter,
        calendars: List<CalendarAccount>,
        showAll: Boolean,
    ) {
        Sheet.show(
            activity = this,
            title = getString(R.string.dlg_calendar_title),
            hint = getString(
                if (showAll) R.string.dlg_calendar_hint_all else R.string.dlg_calendar_hint
            ),
            items = calendars.map { Sheet.Item(title = it.label()) },
            checked = 0,
            doneText = getString(R.string.dlg_done),
            secondaryText = getString(R.string.dlg_create_calendar),
            onSecondary = { createAndWriteCalendar(writer) },
        ) { index ->
            confirmAndWrite(writer, calendars[index])
        }
    }

    private fun createAndWriteCalendar(writer: CalendarWriter) {
        setActionsEnabled(false)
        val name = SettingsStore.calendarName(this)
        notice("正在创建「$name」…")
        Thread {
            try {
                val calendar = writer.createCourseCalendar(name)
                runOnUiThread {
                    setActionsEnabled(true)
                    confirmAndWrite(writer, calendar)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionsEnabled(true)
                    showCalendarError("创建课表日历失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun confirmAndWrite(writer: CalendarWriter, calendar: CalendarAccount) {
        // 只跟当前目标日历的上次导入对齐；旧版本写进其他日历的事件保留不动。
        val record = CalendarWriter.loadRecord(this)
            ?.takeIf { it.calendarId == calendar.id }
        val update = record != null
        val write = { writeToCalendar(writer, calendar) }
        if (!update && !SettingsStore.confirmBeforeWrite(this)) {
            write()
            return
        }
        if (update && !SettingsStore.confirmBeforeReplace(this)) {
            write()
            return
        }
        if (!update) {
            DialogBox.show(
                activity = this,
                title = getString(R.string.dlg_write_confirm_title),
                message = getString(R.string.dlg_write_confirm_message, calendar.label()),
                positive = getString(R.string.dlg_write_confirm_ok),
                negative = getString(R.string.dlg_cancel),
            ) { write() }
            return
        }
        DialogBox.show(
            activity = this,
            title = getString(R.string.dlg_dup_title),
            message = getString(
                R.string.dlg_dup_message,
                record?.calendarName.orEmpty(),
                record?.events?.size ?: 0,
            ),
            positive = getString(R.string.dlg_dup_delete),
            negative = getString(R.string.dlg_cancel),
        ) { write() }
    }

    /**
     * 增量对齐：只动跟上次比起来有变化的那部分。
     * 已经过去的日程按约定不动；写入期间这个按钮会变成「取消同步」。
     */
    private fun writeToCalendar(writer: CalendarWriter, calendar: CalendarAccount) {
        val events = AppState.events
        if (events.isEmpty()) {
            notice("没有可写入的事件。")
            return
        }
        setSyncing(true)

        Thread {
            try {
                // 只抓了部分周（长按「获取课表」选了近几周）时一律不删：
                // 范围外那些"这次没出现"的未来课程，会被误当成"教务系统里删了"而清掉
                val plan = writer.planSync(
                    calendar.id,
                    events,
                    System.currentTimeMillis(),
                    allowDelete = wholeTermFetched(),
                )
                val record = writer.applySync(
                    calendar.id,
                    plan,
                    isCancelled = { cancelRequested },
                    onStage = { stage -> runOnUiThread { notice(stage) } },
                )
                CalendarWriter.saveRecord(this, calendar.id, calendar.label(), record)

                val cancelled = cancelRequested
                runOnUiThread {
                    when {
                        cancelled -> notice("已停下。已经对齐的部分留着了，下次同步接着来。")
                        plan.changes == 0 -> notice("日历已经是最新的：${plan.keep} 条没变化。")
                        else -> notice(summaryOf(plan, calendar))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showCalendarError("写日历失败：${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                runOnUiThread { setSyncing(false) }
            }
        }.start()
    }

    /** 写入期间这个按钮是「取消同步」，平时才是「写入系统日历」。 */
    private fun onWriteButton() {
        if (syncing) {
            cancelRequested = true
            writeBtn.text = "正在停下…"
        } else {
            startWriteToCalendar()
        }
    }

    private fun setSyncing(on: Boolean) {
        syncing = on
        if (on) cancelRequested = false
        // 同步期间这个按钮要留着能点，点了就是取消
        writeBtn.isEnabled = true
        writeBtn.text = if (on) getString(R.string.btn_cancel_sync) else writeBtnLabel
        // 同步中让按钮「呼吸」：这件事要跑一阵子，按钮得自己说明它还在动
        if (on) Anim.breathe(writeBtn) else Anim.stopBreathe(writeBtn)
        exportBtn.isEnabled = !on
        shareBtn.isEnabled = !on
    }

    private fun summaryOf(plan: CalendarWriter.SyncPlan, calendar: CalendarAccount): String =
        buildString {
            append("已对齐「").append(calendar.label()).append("」：新增 ").append(plan.insert.size)
            if (plan.update.isNotEmpty()) append("，更新 ").append(plan.update.size)
            if (plan.delete.isNotEmpty()) append("，移除 ").append(plan.delete.size)
            if (plan.keep > 0) append("，保留 ").append(plan.keep)
            if (plan.past > 0) append("（已过去的 ").append(plan.past).append(" 条没动）")
        }

    // ------------------------------------------------------------ 导出 .ics

    private fun saveIcs(shareAfter: Boolean) {
        if (AppState.events.isEmpty()) {
            notice("没有可导出的事件。")
            return
        }
        // Android 10+ 走 MediaStore 不需要任何权限；9 及以下写公共 Download 目录必须先拿到存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingShareAfterGrant = shareAfter
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        doSaveIcs(shareAfter)
    }

    private fun doSaveIcs(shareAfter: Boolean) {
        setActionsEnabled(false)

        Thread {
            val content = IcsBuilder.build(AppState.events)
            val name = Exporter.fileName()
            try {
                val uri = Exporter.save(this, name, content)
                savedUri = uri
                savedName = name
                runOnUiThread {
                    setActionsEnabled(true)
                    shareBtn.isEnabled = true
                    if (shareAfter) {
                        share(name, uri)
                    } else {
                        notice("已保存到「下载/${Exporter.SUB_DIR}/$name」。")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionsEnabled(true)
                    showCalendarError("保存失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun showCalendarError(message: String) {
        DialogBox.show(
            activity = this,
            title = getString(R.string.banner_fail_title),
            message = message,
            positive = getString(R.string.dlg_ok),
        )
    }

    private fun share(name: String, uri: Uri) {
        val intent = Exporter.shareIntent(uri, name)
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    // ------------------------------------------------------------ 小工具

    /** 正在对齐日历时为 true；此时 writeBtn 就是「取消同步」按钮。 */
    private var syncing = false

    @Volatile
    private var cancelRequested = false

    /** writeBtn 本来的文案，退出同步状态时还原。 */
    private var writeBtnLabel: CharSequence = ""

    private fun setActionsEnabled(enabled: Boolean) {
        writeBtn.isEnabled = enabled
        exportBtn.isEnabled = enabled
        // 「分享」不再依赖「先保存 .ics」：没存过就现存一份再分享，少一步操作
        shareBtn.isEnabled = enabled
    }

    private fun notice(message: String) {
        Snack.show(this, message)
    }

    private companion object {
        const val REQ_CALENDAR = 1001
        const val REQ_STORAGE = 1002
    }
}
