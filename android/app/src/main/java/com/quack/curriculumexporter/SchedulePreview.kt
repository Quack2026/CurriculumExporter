package com.quack.curriculumexporter

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * 把抓到的课表渲染成带层次的富文本。
 *
 * 用 Spannable 而不是动态 addView：一学期 20 周、几百条课程，
 * 每条一个 View 光测量布局就够卡的，富文本一遍就画完了。
 */
object SchedulePreview {

    /** 预览是在 Activity 中渲染的，颜色必须从当前主题读取，才能跟随 values-night。 */
    private fun themeColors(context: android.content.Context): Pair<Int, Int> =
        context.getColor(R.color.text_primary) to context.getColor(R.color.text_secondary)

    /** 按周看：一周一段，段内按天列课。 */
    fun byWeek(context: android.content.Context, schedule: Schedule): CharSequence {
        val (primary, secondary) = themeColors(context)
        val sb = SpannableStringBuilder()
        val weeks = schedule.weeks.sortedBy { it.week }

        if (weeks.isEmpty()) {
            return styled("没有抓到任何一周的课表。", color = secondary)
        }

        weeks.forEachIndexed { index, week ->
            if (index > 0) sb.append("\n\n")
            style(sb, "第 ${week.week} 周", color = primary, size = 1.1f, bold = true)
            weekRange(week).takeIf { it.isNotEmpty() }?.let {
                style(sb, "   $it", color = secondary, size = 0.92f)
            }
            sb.append("\n")

            if (week.courses.isEmpty()) {
                style(sb, "这一周是空课表", color = secondary, size = 0.95f)
                return@forEachIndexed
            }

            var printed = false
            for (slot in week.xqid.indices) {
                val weekday = week.xqid[slot]
                val courses = week.courses.filter { it.weekDay.toIntOrNull() == weekday }
                if (courses.isEmpty()) continue

                printed = true
                if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append("\n")
                style(sb, Schedule.weekdayName(weekday), color = primary, size = 1f, bold = true)
                week.date.getOrNull(slot)?.takeIf { it.length >= 10 }?.let {
                    style(sb, "   ${it.substring(5)}", color = secondary, size = 0.92f)
                }
                sb.append("\n")

                courses.forEach { course ->
                    style(sb, "· ${course.courseName}", color = primary)
                    sb.append("\n")
                    style(sb, "    ${detailOf(course)}", color = secondary, size = 0.92f)
                    sb.append("\n")
                }
            }
            if (!printed) style(sb, "这周有课表数据，但没解析出课程", color = secondary, size = 0.95f)
        }
        return sb
    }

    /** 按课程看：同一门课的上课时段归到一起（第一次出现的位置决定顺序）。 */
    fun byCourse(context: android.content.Context, schedule: Schedule): CharSequence {
        val (primary, secondary) = themeColors(context)
        // key = 星期 + 时间 + 教室 + 老师，用来把「同一时段重复了 20 周」合并成一条
        val slots = LinkedHashMap<String, LinkedHashMap<String, CourseItem>>()

        schedule.weeks.sortedBy { it.week }.forEach { week ->
            for (i in week.xqid.indices) {
                val weekday = week.xqid[i]
                week.courses.filter { it.weekDay.toIntOrNull() == weekday }.forEach { c ->
                    val key = listOf(
                        weekday, c.startTime, c.endTime, c.classroomName, c.teacherName,
                    ).joinToString("|")
                    slots.getOrPut(c.courseName) { LinkedHashMap() }[key] = c
                }
            }
        }

        if (slots.isEmpty()) return styled("没有抓到任何课程。", color = secondary)

        val sb = SpannableStringBuilder()
        slots.entries.forEachIndexed { index, (name, times) ->
            if (index > 0) sb.append("\n\n")
            style(sb, name, color = primary, size = 1.1f, bold = true)

            val weeks = mutableSetOf<String>()
            sb.append("\n")
            times.values.forEach { c ->
                c.classWeek.takeIf { it.isNotEmpty() }?.let { weeks += it }
                style(sb, "· ${slotOf(c)}", color = primary, size = 0.98f)
                sb.append("\n")
                style(sb, "    ${placeOf(c)}", color = secondary, size = 0.92f)
                sb.append("\n")
            }
            if (weeks.isNotEmpty()) {
                style(sb, "   上课周次：${weeks.joinToString("、")}", color = secondary, size = 0.92f)
            } else {
                // 收尾多出来的换行不留着
                if (sb.length > 0 && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
            }
        }
        return sb
    }

    /** 形如「周一 第1-2节 08:00-09:40」。 */
    private fun slotOf(c: CourseItem): String {
        val parts = ArrayList<String>(3)
        parts += Schedule.weekdayName(c.weekDay.toIntOrNull() ?: 0)
        if (c.startNode > 0 && c.endNode > 0) parts += "第${c.startNode}-${c.endNode}节"
        if (c.startTime.isNotEmpty() && c.endTime.isNotEmpty()) {
            parts += "${c.startTime}-${c.endTime}"
        }
        return parts.joinToString(" ")
    }

    /** 时间 / 教室 / 老师，按周视图里每条课下面那行。 */
    private fun detailOf(c: CourseItem): String {
        val parts = ArrayList<String>(4)
        if (c.startTime.isNotEmpty() && c.endTime.isNotEmpty()) {
            parts += "${c.startTime}-${c.endTime}"
        }
        if (c.startNode > 0 && c.endNode > 0) parts += "第${c.startNode}-${c.endNode}节"
        if (c.classroomName.isNotEmpty()) parts += c.classroomName
        if (c.teacherName.isNotEmpty()) parts += c.teacherName
        return parts.joinToString(" · ").ifEmpty { "（这条课程没有时间地点信息）" }
    }

    /** 形如「1-201 · 第一教学楼 · 张老师」。 */
    private fun placeOf(c: CourseItem): String {
        val parts = ArrayList<String>(3)
        if (c.classroomName.isNotEmpty()) parts += c.classroomName
        if (c.buildingName.isNotEmpty()) parts += c.buildingName
        if (c.teacherName.isNotEmpty()) parts += c.teacherName
        return parts.joinToString(" · ").ifEmpty { "地点未提供" }
    }

    /** 该周日期范围，形如「2026-09-01 ~ 2026-09-07」。 */
    private fun weekRange(week: WeekData): String {
        val first = week.date.firstOrNull { it.length >= 10 } ?: return ""
        val last = week.date.lastOrNull { it.length >= 10 } ?: return ""
        return "$first ~ $last"
    }

    // ---------------------------------------------------------------- 样式

    private fun styled(
        text: String,
        color: Int,
        size: Float = 1f,
        bold: Boolean = false,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        style(sb, text, color, size, bold)
        return sb
    }

    private fun style(
        sb: SpannableStringBuilder,
        text: String,
        color: Int,
        size: Float = 1f,
        bold: Boolean = false,
    ) {
        val start = sb.length
        sb.append(text)
        val end = sb.length
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(ForegroundColorSpan(color), start, end, flags)
        if (size != 1f) sb.setSpan(RelativeSizeSpan(size), start, end, flags)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
    }
}
