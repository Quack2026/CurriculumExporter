package com.quack.curriculumexporter

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var calendarName: EditText
    private lateinit var usePicker: Switch
    private lateinit var showAll: Switch
    private lateinit var confirmWrite: Switch
    private lateinit var confirmReplace: Switch
    private lateinit var savePassword: Switch
    private lateinit var dynamicTheme: Switch

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
        setContentView(R.layout.activity_settings)
        SystemBars.install(this, findViewById(android.R.id.content), findViewById(R.id.topBar))
        UiTheme.apply(this)

        calendarName = findViewById(R.id.calendarNameInput)
        usePicker = findViewById(R.id.usePickerSwitch)
        showAll = findViewById(R.id.showAllSwitch)
        confirmWrite = findViewById(R.id.confirmWriteSwitch)
        confirmReplace = findViewById(R.id.confirmReplaceSwitch)
        savePassword = findViewById(R.id.savePasswordSwitch)
        dynamicTheme = findViewById(R.id.dynamicThemeSwitch)

        calendarName.setText(SettingsStore.calendarName(this))
        usePicker.isChecked = SettingsStore.useCalendarPicker(this)
        showAll.isChecked = SettingsStore.showAllCalendars(this)
        confirmWrite.isChecked = SettingsStore.confirmBeforeWrite(this)
        confirmReplace.isChecked = SettingsStore.confirmBeforeReplace(this)
        savePassword.isChecked = SettingsStore.savePassword(this)
        dynamicTheme.isChecked = SettingsStore.dynamicTheme(this)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.saveSettingsBtn).setOnClickListener { save() }
        Anim.press(findViewById(R.id.backBtn))
        Anim.press(findViewById(R.id.saveSettingsBtn), pressed = 0.97f)

        // 每行整行可点：手指点在文字上也该生效，只有右边那个小开关能点太别扭了
        listOf(
            R.id.calendarPickerRow to usePicker,
            R.id.showAllRow to showAll,
            R.id.confirmWriteRow to confirmWrite,
            R.id.confirmReplaceRow to confirmReplace,
            R.id.savePasswordRow to savePassword,
            R.id.dynamicThemeRow to dynamicTheme,
        ).forEach { (rowId, target) ->
            val row = findViewById<View>(rowId)
            row.setOnClickListener {
                target.toggle()
                row.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }

        // 逐张卡片入场，而不是把整块内容做一次淡入 —— 那玩意儿几千像素高，
        // 一 alpha 就要离屏开一张大位图，够了 OOM 的门槛。
        Anim.staggerChildren(findViewById(R.id.settingsContent), startDelay = 40L)
    }

    override fun finish() {
        super.finish()
        Anim.pageBack(this)
    }

    private fun save() {
        val name = calendarName.text.toString().trim()
        if (name.isEmpty()) {
            calendarName.error = getString(R.string.settings_calendar_name_required)
            calendarName.requestFocus()
            return
        }
        SettingsStore.save(
            context = this,
            calendarName = name,
            useCalendarPicker = usePicker.isChecked,
            showAllCalendars = showAll.isChecked,
            confirmBeforeWrite = confirmWrite.isChecked,
            confirmBeforeReplace = confirmReplace.isChecked,
            savePassword = savePassword.isChecked,
            dynamicTheme = dynamicTheme.isChecked,
        )
        if (!savePassword.isChecked) SecureCredentials.clearPassword(this)
        // 提示留给主界面显示：这里马上 finish，自己弹出来也看不见
        AppState.pendingNotice = getString(R.string.settings_saved)
        finish()
    }
}
