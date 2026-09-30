package com.quack.curriculumexporter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var writeBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var segmented: SegmentedControl

    private lateinit var schedule: Schedule

    private var savedUri: Uri? = null
    private var savedName: String = ""

    /** 走存储权限时用户点的是「保存」还是「保存并分享」，授权回来后接着做完。 */
    private var pendingShareAfterGrant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.applyConfiguredTheme(this)

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
        shareBtn.setOnClickListener { saveIcs(shareAfter = true) }

        Anim.press(findViewById(R.id.backBtn))
        Anim.press(writeBtn)
        Anim.press(exportBtn)
        Anim.press(shareBtn)

        playIntro()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down)
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
                toast("没拿到存储权限，文件存不下来。可以改用「写入系统日历」。")
            }
            return
        }
        if (requestCode != REQ_CALENDAR) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            chooseCalendar()
        } else {
            toast("没拿到日历权限。可以改用「保存 .ics」，再自己导进日历。")
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
        // 这里刻意不用 Anim.crossfade：previewText 里装着整学期课表（上千行、几万像素高），
        // 对它做 alpha 淡入淡出等于要求滚动容器每帧整块重绘。
        // 与其担这个渲染风险，不如直接把内容换掉 —— 顺带把滚动位置归零，观感同样是「翻了一页」。
        previewText.text =
            if (byCourse) SchedulePreview.byCourse(this, schedule)
            else SchedulePreview.byWeek(this, schedule)
        resultScroll.post { resultScroll.scrollTo(0, 0) }
    }

    private fun playIntro() {
        // 注意：previewText 刻意不参与入场动画。
        // 它装满一整学期后有一千多行、好几万像素高，对它做每帧 alpha/translationY 动画
        // 会让滚动容器反复整块重绘，在部分机型/系统上会直接触发渲染层崩溃。
        // 少这一个淡入，看不出差别。
        Anim.stagger(
            listOf(
                findViewById(R.id.infoArea),
                findViewById(R.id.segMode),
                findViewById(R.id.actionArea),
            )
        )
    }

    // ------------------------------------------------------------ 写系统日历

    private fun startWriteToCalendar() {
        if (AppState.events.isEmpty()) {
            toast("没有可写入的事件。")
            return
        }
        if (!CalendarWriter.hasPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_calendar_title)
                .setMessage(R.string.perm_calendar_message)
                .setPositiveButton(R.string.perm_calendar_ok) { _, _ ->
                    @Suppress("DEPRECATION")
                    requestPermissions(CalendarWriter.PERMISSIONS, REQ_CALENDAR)
                }
                .setNegativeButton(R.string.perm_calendar_no) { _, _ -> saveIcs(shareAfter = false) }
                .show()
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
            toast("读不到手机里的日历：${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (calendars.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_calendar_title)
                .setMessage(R.string.dlg_no_calendar)
                .setPositiveButton(R.string.dlg_create_calendar) { _, _ ->
                    createAndWriteCalendar(writer)
                }
                .setNegativeButton(R.string.dlg_cancel, null)
                .show()
            return
        }

        showCalendarPicker(writer, calendars, showAll)
    }

    /**
     * 日历列表自己渲染，不走 `AlertDialog.setItems()`。
     *
     * 平台 Material 主题下 `setItems()` 那份列表在真机上根本不渲染：对话框只剩标题、提示和两个按钮，
     * 用户一个日历都看不到也没得选，「启用系统日历选择」这个设置等于白开。
     * 换成 `setView` + 逐行 `addView` 之后就正常了。
     */
    private fun showCalendarPicker(
        writer: CalendarWriter,
        calendars: List<CalendarAccount>,
        showAll: Boolean,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_calendar_picker, null)
        content.findViewById<TextView>(R.id.pickerHint).text = getString(
            if (showAll) R.string.dlg_calendar_hint_all else R.string.dlg_calendar_hint
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_calendar_title)
            .setView(content)
            .setPositiveButton(R.string.dlg_create_calendar) { _, _ ->
                createAndWriteCalendar(writer)
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .create()

        val list = content.findViewById<LinearLayout>(R.id.pickerList)
        calendars.forEach { calendar ->
            val row = layoutInflater.inflate(R.layout.item_calendar_choice, list, false) as TextView
            row.text = calendar.label()
            row.setOnClickListener {
                dialog.dismiss()
                confirmAndWrite(writer, calendar)
            }
            list.addView(row)
        }

        dialog.show()

        // 开了「显示全部日历」时列表可能很长：超过半屏就让它自己滚，别把对话框撑出屏幕。
        val scroll = content.findViewById<ScrollView>(R.id.pickerScroll)
        scroll.post {
            val limit = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            if (scroll.height > limit) {
                scroll.layoutParams = scroll.layoutParams.apply { height = limit }
            }
        }
    }

    private fun createAndWriteCalendar(writer: CalendarWriter) {
        setActionsEnabled(false)
        val name = SettingsStore.calendarName(this)
        toast("正在创建「$name」…")
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
                    AlertDialog.Builder(this)
                        .setTitle(R.string.banner_fail_title)
                        .setMessage("创建课表日历失败：${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
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
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_write_confirm_title)
                .setMessage(getString(R.string.dlg_write_confirm_message, calendar.label()))
                .setPositiveButton(R.string.dlg_write_confirm_ok) { _, _ -> write() }
                .setNegativeButton(R.string.dlg_cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_dup_title)
            .setMessage(getString(R.string.dlg_dup_message, record.calendarName, record.events.size))
            .setPositiveButton(R.string.dlg_dup_delete) { _, _ -> write() }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    /**
     * 增量对齐：只动跟上次比起来有变化的那部分。
     * 已经过去的日程按约定不动；写入期间这个按钮会变成「取消同步」。
     */
    private fun writeToCalendar(writer: CalendarWriter, calendar: CalendarAccount) {
        val events = AppState.events
        if (events.isEmpty()) {
            toast("没有可写入的事件。")
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
                    onStage = { stage -> runOnUiThread { toast(stage) } },
                )
                CalendarWriter.saveRecord(this, calendar.id, calendar.label(), record)

                val cancelled = cancelRequested
                runOnUiThread {
                    when {
                        cancelled -> toast("已停下。已经对齐的部分留着了，下次同步接着来。")
                        plan.changes == 0 -> toast("日历已经是最新的：${plan.keep} 条没变化。")
                        else -> toast(summaryOf(plan, calendar))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.banner_fail_title)
                        .setMessage("写日历失败：${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
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
        exportBtn.isEnabled = !on
        shareBtn.isEnabled = !on && savedUri != null
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
            toast("没有可导出的事件。")
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
                        toast("已保存到「下载/${Exporter.SUB_DIR}/$name」。")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionsEnabled(true)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.banner_fail_title)
                        .setMessage("保存失败：${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showCalendarError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.banner_fail_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        shareBtn.isEnabled = enabled && savedUri != null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQ_CALENDAR = 1001
        const val REQ_STORAGE = 1002
    }
}
