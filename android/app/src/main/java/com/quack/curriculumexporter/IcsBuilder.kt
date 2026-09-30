package com.quack.curriculumexporter

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 一个展开后的日历事件。
 *
 * ICS 文本、写进系统日历、统计条数，全部从这一个结构取，
 * 免得同一套「哪一周的哪一天上哪门课」的逻辑在三个地方各写一遍。
 */
data class IcsEvent(
    val uid: String,
    /** yyyyMMdd */
    val date: String,
    /** HHmmss */
    val start: String,
    /** HHmmss */
    val end: String,
    val title: String,
    val location: String,
    val description: String,
    /** 全天事件（军训）；此时用 [endDate] 作为结束日（不含） */
    val allDay: Boolean = false,
    val endDate: String = "",
    /**
     * 写进系统日历后，用来认「同一节课」的稳定标识：只有课程实例 id + 星期几，
     * 不含日期和具体时间。这样换课改了节次后，仍然能对上原来那条日历事件。
     */
    val courseKey: String = "",
)

/**
 * 标准 ICS 生成（RFC 5545）。
 *
 * 输出刻意和 Windows 版逐字节对齐：UTF-8 无 BOM + CRLF、每行按 UTF-8 字节折到 75 以内、
 * 内嵌 Asia/Shanghai 的 VTIMEZONE，保证导入手机日历后时间不飘。
 */
object IcsBuilder {

    /** 日历名（会显示在手机日历 App 的日历列表里）。学期变了记得改这一行。 */
    const val CALENDAR_NAME = "2026-2027-1 学期课表"

    /**
     * 军训：第 14-15 周，教务系统里这两周是空课表，所以单独造一个全天事件，
     * 免得看课表的人以为"这两周没课"。日期同样是硬编码的学期安排，换学期要改。
     */
    private const val MILITARY_START = "20261207"
    private const val MILITARY_END_EXCLUSIVE = "20261221"
    private const val MILITARY_UID = "military-training-2026@jwcydjw.gdlgxy.edu.cn"
    private const val MILITARY_TITLE = "冬季军训（第14-15周）"

    /**
     * 把抓到的课表展开成事件列表。
     *
     * @param includeMilitary 是否补一个军训全天事件
     * @param onSkip 某条课程时间字段不完整时会被跳过，这里回传原因（用于日志）
     */
    fun events(
        schedule: Schedule,
        includeMilitary: Boolean = true,
        onSkip: (String) -> Unit = {},
    ): List<IcsEvent> {
        val out = ArrayList<IcsEvent>()

        for (week in schedule.weeks) {
            for (i in week.xqid.indices) {
                val weekday = week.xqid[i]
                val raw = week.date.getOrNull(i) ?: continue
                val day = raw.replace("-", "")
                if (day.length != 8) continue

                for (c in week.courses) {
                    if (c.weekDay.toIntOrNull() != weekday) continue

                    val st = c.startTime.replace(":", "")
                    val et = c.endTime.replace(":", "")
                    if (st.length != 4 || et.length != 4) {
                        onSkip("${c.courseName}（第 ${week.week} 周）：时间字段不完整，已跳过")
                        continue
                    }
                    out += IcsEvent(
                        uid = "${c.jx0404id}-${day}T${st}@jwcydjw.gdlgxy.edu.cn",
                        date = day,
                        start = "${st}00",
                        end = "${et}00",
                        title = c.courseName,
                        location = c.classroomName +
                            if (c.buildingName.isNotEmpty()) "  ·  ${c.buildingName}" else "",
                        description = description(c, week.week),
                        courseKey = if (c.jx0404id.isEmpty()) "" else "${c.jx0404id}|${c.weekDay}",
                    )
                }
            }
        }

        if (includeMilitary) out += militaryEvent()

        // 按开始时间排序，和 Windows 版一致
        return out.sortedWith(compareBy({ it.date }, { it.start }))
    }

    /** 单个事件的备注文案。 */
    private fun description(c: CourseItem, week: Int): String {
        // 节次在原始数据里未必可靠（classTime 格式各校区不一样），拿不到就只写时间，
        // 不去生成「第0-0节」这种读起来像 bug 的东西。
        val time = when {
            c.startNode > 0 && c.endNode > 0 ->
                "第${c.startNode}-${c.endNode}节（${c.startTime}~${c.endTime}）"
            c.startTime.isNotEmpty() && c.endTime.isNotEmpty() -> "${c.startTime}~${c.endTime}"
            else -> "未提供"
        }
        return buildString {
            append("教师：").append(c.teacherName)
            append("\n上课班级：").append(c.ktmc)
            append("\n节次：").append(time)
            append("\n周次：").append(c.classWeek).append(" 周（第").append(week).append("周）")
            append("\n人数：").append(c.xkrs)
            append("\n考核方式：").append(c.khfs)
        }
    }

    private fun militaryEvent() = IcsEvent(
        uid = MILITARY_UID,
        date = MILITARY_START,
        start = "000000",
        end = "000000",
        title = MILITARY_TITLE,
        location = "（军训场地以学校通知为准）",
        description = "第14、15周 2026-12-07 ~ 2026-12-20 全校停课，2026-12-21 复课。",
        allDay = true,
        endDate = MILITARY_END_EXCLUSIVE,
        courseKey = MILITARY_UID,
    )

    /** 生成完整的 .ics 文本。 */
    fun build(events: List<IcsEvent>, calendarName: String = CALENDAR_NAME): String {
        val stamp = stamp()
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//gdlgxy//curriculum//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("METHOD:PUBLISH\r\n")
        sb.append("X-WR-CALNAME:").append(escape(calendarName)).append("\r\n")
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n")
        sb.append(
            "BEGIN:VTIMEZONE\r\nTZID:Asia/Shanghai\r\nBEGIN:STANDARD\r\n" +
                "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0800\r\nTZOFFSETTO:+0800\r\n" +
                "TZNAME:CST\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        )

        for (e in events) {
            val body = StringBuilder()
            body.append("BEGIN:VEVENT\r\n")
            body.append("UID:").append(e.uid).append("\r\n")
            body.append("DTSTAMP:").append(stamp).append("\r\n")
            if (e.allDay) {
                body.append("DTSTART;VALUE=DATE:").append(e.date).append("\r\n")
                body.append("DTEND;VALUE=DATE:").append(e.endDate).append("\r\n")
            } else {
                body.append("DTSTART;TZID=Asia/Shanghai:").append(e.date).append("T").append(e.start).append("\r\n")
                body.append("DTEND;TZID=Asia/Shanghai:").append(e.date).append("T").append(e.end).append("\r\n")
            }
            body.append("SEQUENCE:0\r\nSTATUS:CONFIRMED\r\nTRANSP:OPAQUE\r\n")
            body.append("SUMMARY:").append(escape(e.title)).append("\r\n")
            body.append("LOCATION:").append(escape(e.location)).append("\r\n")
            body.append("DESCRIPTION:").append(escape(e.description)).append("\r\n")
            body.append("END:VEVENT")

            for (line in body.split("\r\n")) {
                sb.append(fold(line)).append("\r\n")
            }
        }

        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /**
     * RFC 5545 折行：一行不超过 75 字节（按 UTF-8 算），续行以单个空格开头。
     * 这里用 73 作为上限，给续行开头那个空格留位置，与 Windows 版一致。
     */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 73) return line
        val sb = StringBuilder()
        var cur = StringBuilder()
        var len = 0
        for (ch in line) {
            val bytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (len + bytes > 73) {
                sb.append(cur).append("\r\n")
                cur = StringBuilder(" ").append(ch)
                len = 1 + bytes
            } else {
                cur.append(ch)
                len += bytes
            }
        }
        if (cur.isNotEmpty()) sb.append(cur)
        return sb.toString()
    }

    /** RFC 5545 文本转义。顺序有讲究：反斜杠必须先转。 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    private fun stamp(): String = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
}
