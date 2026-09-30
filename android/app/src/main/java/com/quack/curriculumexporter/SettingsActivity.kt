package com.quack.curriculumexporter

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var calendarName: EditText
    private lateinit var usePicker: Switch
    private lateinit var showAll: Switch
    private lateinit var confirmWrite: Switch
    private lateinit var confirmReplace: Switch
    private lateinit var savePassword: Switch
    private lateinit var dynamicTheme: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.applyConfiguredTheme(this)
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

        findViewById<android.view.View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.saveSettingsBtn).setOnClickListener { save() }
        Anim.press(findViewById(R.id.backBtn))
        Anim.press(findViewById(R.id.saveSettingsBtn))
        Anim.stagger(listOf(findViewById(R.id.settingsContent)))
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
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
