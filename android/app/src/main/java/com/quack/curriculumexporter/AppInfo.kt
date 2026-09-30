package com.quack.curriculumexporter

import android.content.Context

/** 应用自身的元信息，界面用它显示当前安装的版本，方便确认装的是哪一份包。 */
object AppInfo {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
