package com.quack.curriculumexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ICS 生成是纯逻辑，而且手机日历对格式很挑，这里把它钉住。 */
class IcsBuilderTest {

    private fun course(
        weekDay: String = "1",
        name: String = "高等数学",
        start: String = "08:00",
        end: String = "09:40",
        classroom: String = "1-201",
        building: String = "第一教学楼",
        startNode: Int = 1,
        endNode: Int = 2,
    ) = CourseItem(
        weekDay = weekDay,
        classTime = "第0102节",
        courseName = name,
        teacherName = "张老师",
        classroomName = classroom,
        buildingName = building,
        startTime = start,
        endTime = end,
        classWeek = "1-16",
        ktmc = "计算机2301",
        khfs = "考试",
        jx0404id = "K0001",
        xkrs = "45",
        startNode = startNode,
        endNode = endNode,
    )

    private fun schedule(courses: List<CourseItem>) = Schedule(
        student = Student(userNo = "20230001", name = "张三", clsName = "计算机2301"),
        weeks = listOf(
            WeekData(
                week = 1,
                xqid = listOf(1, 2, 3),
                date = listOf("2026-09-01", "2026-09-02", "2026-09-03"),
                courses = courses,
            ),
        ),
        maxWeek = 1,
    )

    @Test
    fun `课程按星期和日期配对成事件`() {
        val events = IcsBuilder.events(
            schedule(listOf(course(weekDay = "1"), course(weekDay = "2", name = "大学英语"))),
            includeMilitary = false,
        )
        assertEquals(2, events.size)
        // 周一那条落在 09-01，周二那条落在 09-02
        assertEquals("20260901", events[0].date)
        assertEquals("20260902", events[1].date)
        assertEquals("080000", events[0].start)
        assertEquals("094000", events[0].end)
        assertEquals("1-201  ·  第一教学楼", events[0].location)
    }

    @Test
    fun `星期对不上的课程不会凭空生成事件`() {
        // 课程写的是周日，但这一周只给了周一到周三，应当没有事件
        val events = IcsBuilder.events(
            schedule(listOf(course(weekDay = "7"))),
            includeMilitary = false,
        )
        assertEquals(0, events.size)
    }

    @Test
    fun `时间字段残缺的课程会被跳过而不是生成午夜事件`() {
        val skipped = ArrayList<String>()
        val events = IcsBuilder.events(
            schedule(listOf(course(start = "", end = ""))),
            includeMilitary = false,
            onSkip = { skipped += it },
        )
        assertEquals(0, events.size)
        assertEquals(1, skipped.size)
    }

    @Test
    fun `军训事件默认生成且可以用开关关掉`() {
        val withMilitary = IcsBuilder.events(schedule(listOf(course())), includeMilitary = true)
        val without = IcsBuilder.events(schedule(listOf(course())), includeMilitary = false)
        assertEquals(2, withMilitary.size)
        assertEquals(1, without.size)

        val military = withMilitary.last()
        assertTrue(military.allDay)
        assertEquals("20261207", military.date)
        assertEquals("20261221", military.endDate)
        assertEquals("冬季军训（第14-15周）", military.title)
    }

    @Test
    fun `生成的 ics 结构完整`() {
        val text = IcsBuilder.build(IcsBuilder.events(schedule(listOf(course()))))
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertTrue(text.contains("BEGIN:VTIMEZONE\r\nTZID:Asia/Shanghai\r\n"))
        assertTrue(text.contains("BEGIN:VEVENT\r\n"))
        assertTrue(text.contains("DTSTART;TZID=Asia/Shanghai:20260901T080000\r\n"))
        assertTrue(text.contains("DTEND;TZID=Asia/Shanghai:20260901T094000\r\n"))
        assertTrue(text.contains("BEGIN:VEVENT\r\nUID:military-training-2026@jwcydjw.gdlgxy.edu.cn\r\n"))
        // 无 BOM，且全是 CRLF
        assertEquals('B', text[0])
        assertFalse(text.contains("\n\n"))
        assertFalse(text.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `每一行都不超过 75 字节且续行以空格开头`() {
        val longName = "数据".repeat(60)
        val text = IcsBuilder.build(
            IcsBuilder.events(schedule(listOf(course(name = longName)))),
        )
        val lines = text.split("\r\n")
        lines.filter { it.isNotEmpty() }.forEach { line ->
            assertTrue(
                "行长 ${line.toByteArray(Charsets.UTF_8).size} 超了：$line",
                line.toByteArray(Charsets.UTF_8).size <= 75,
            )
        }
        // 高数那条被换成长名字后一定会折行，检查续行确实是空格开头
        assertTrue(lines.any { it.startsWith(" ") })
        // 折回来的内容必须还是完整的课程名，不能丢字符
        val unfolded = text.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:$longName\r\n"))
    }

    @Test
    fun `每个 VEVENT 的必需属性齐全且 BEGIN END 配对`() {
        val longName = "很长的课程名称".repeat(8)
        val text = IcsBuilder.build(
            IcsBuilder.events(schedule(listOf(course(), course(weekDay = "2", name = longName)))),
        )
        assertEquals(
            text.split("BEGIN:VEVENT").size,
            text.split("END:VEVENT").size,
        )
        assertEquals(
            text.split("BEGIN:VCALENDAR").size,
            text.split("END:VCALENDAR").size,
        )

        // 把折行还原后再看每个事件块，缺任何一项手机日历都可能整条吞掉
        val unfolded = text.replace("\r\n ", "")
        unfolded.split("BEGIN:VEVENT\r\n").drop(1).forEach { chunk ->
            val body = chunk.substringBefore("END:VEVENT")
            listOf("UID:", "DTSTAMP:", "DTSTART", "DTEND", "SUMMARY:", "SEQUENCE:").forEach {
                assertTrue("事件块里缺 $it：\n$body", body.contains(it))
            }
        }
    }

    @Test
    fun `描述里的分号逗号和换行都按 RFC 5545 转义`() {
        val event = IcsEvent(
            uid = "u1",
            date = "20260901",
            start = "080000",
            end = "094000",
            title = "课;程,名",
            location = "1-201",
            description = "第一行\n第二行",
        )
        val text = IcsBuilder.build(listOf(event))
        assertTrue(text.contains("SUMMARY:课\\;程\\,名\r\n"))
        assertTrue(text.contains("DESCRIPTION:第一行\\n第二行\r\n"))
    }

    @Test
    fun `描述里拿不到节次时不写第0-0节`() {
        val noNode = IcsBuilder.events(
            schedule(listOf(course(startNode = 0, endNode = 0))),
            includeMilitary = false,
        )
        val desc = noNode[0].description
        assertTrue(desc.contains("节次：08:00~09:40"))
        assertFalse(desc.contains("第0-0节"))
    }
}
