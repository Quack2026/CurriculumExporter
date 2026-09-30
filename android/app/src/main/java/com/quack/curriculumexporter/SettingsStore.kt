package com.quack.curriculumexporter

import android.content.Context

/** 应用偏好设置。密码正文不放在这里，密码由 SecureCredentials 加密保存。 */
object SettingsStore {
    private const val PREFS = "curriculum_settings"
    private const val KEY_CALENDAR_NAME = "calendar_name"
    private const val KEY_USE_PICKER = "use_calendar_picker"
    private const val KEY_SHOW_ALL_CALENDARS = "show_all_calendars"
    private const val KEY_CONFIRM_WRITE = "confirm_before_write"
    private const val KEY_CONFIRM_REPLACE = "confirm_before_replace"
    private const val KEY_SAVE_PASSWORD = "save_password"
    private const val KEY_DYNAMIC_THEME = "dynamic_theme"
    private const val KEY_FETCH_RANGE = "fetch_range"

    /** 长按面板里的档位下标，对应 TermWeeks.ranges() 的固定顺序；默认「全面更新」。 */
    private const val DEFAULT_FETCH_RANGE = 1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun calendarName(context: Context): String =
        prefs(context).getString(KEY_CALENDAR_NAME, CalendarWriter.COURSE_CALENDAR_NAME)
            .orEmpty()
            .trim()
            .ifEmpty { CalendarWriter.COURSE_CALENDAR_NAME }

    fun useCalendarPicker(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_PICKER, false)

    fun showAllCalendars(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ALL_CALENDARS, false)

    fun confirmBeforeWrite(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_WRITE, false)

    fun confirmBeforeReplace(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_REPLACE, false)

    fun savePassword(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVE_PASSWORD, false)

    /**
     * 是否用 Android 12+ 的系统主题色做强调色。
     *
     * 默认关：这个应用的视觉基调是纯黑白灰，层级靠圆角和明度差表达；
     * 想跟着系统换个强调色的可以在「高级设置 → 外观」里打开。
     */
    fun dynamicTheme(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_THEME, false)

    /** 长按面板里选定的获取范围档位，单击「获取课表」时按它开抓。 */
    fun fetchRange(context: Context): Int =
        prefs(context).getInt(KEY_FETCH_RANGE, DEFAULT_FETCH_RANGE)

    fun setFetchRange(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_FETCH_RANGE, index).apply()
    }

    fun save(
        context: Context,
        calendarName: String,
        useCalendarPicker: Boolean,
        showAllCalendars: Boolean,
        confirmBeforeWrite: Boolean,
        confirmBeforeReplace: Boolean,
        savePassword: Boolean,
        dynamicTheme: Boolean,
    ) {
        prefs(context).edit()
            .putString(KEY_CALENDAR_NAME, calendarName.trim())
            .putBoolean(KEY_USE_PICKER, useCalendarPicker)
            .putBoolean(KEY_SHOW_ALL_CALENDARS, showAllCalendars)
            .putBoolean(KEY_CONFIRM_WRITE, confirmBeforeWrite)
            .putBoolean(KEY_CONFIRM_REPLACE, confirmBeforeReplace)
            .putBoolean(KEY_SAVE_PASSWORD, savePassword)
            .putBoolean(KEY_DYNAMIC_THEME, dynamicTheme)
            .apply()
    }
}
