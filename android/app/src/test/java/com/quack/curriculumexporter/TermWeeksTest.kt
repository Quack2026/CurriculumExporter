package com.quack.curriculumexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按「获取课表」里的周范围。
 *
 * 算错了轻则白抓一周白等几秒，重则多打几次学校服务器，所以把边界单独钉住：
 * 四个范围全部以「本周往后」算，已经上过的课不重抓。
 */
class TermWeeksTest {

    private val last = EduClient.DEFAULT_MAX_WEEK

    @Test
    fun `全面重新获取永远是第 1 周到最后一整学期`() {
        assertEquals(WeekRange(1, last), TermWeeks.ranges(4).first())
        assertEquals(WeekRange(1, last), TermWeeks.ranges(1).first())
    }

    @Test
    fun `全面更新从本周一直到第 20 周`() {
        assertEquals(WeekRange(4, last), TermWeeks.ranges(4)[1])
    }

    @Test
    fun `近 3 周是本周加上之后两周`() {
        // 第 4 周 -> 4、5、6
        assertEquals(WeekRange(4, 6), TermWeeks.ranges(4)[3])
    }

    @Test
    fun `近 5 周是本周加上之后四周`() {
        // 第 4 周 -> 4~8
        assertEquals(WeekRange(4, 8), TermWeeks.ranges(4)[2])
    }

    @Test
    fun `靠后的周次不会超出第 20 周`() {
        assertEquals(WeekRange(18, last), TermWeeks.ranges(18)[2])
        assertEquals(WeekRange(19, last), TermWeeks.ranges(19)[3])
        assertEquals(WeekRange(last, last), TermWeeks.ranges(last)[2])
    }

    @Test
    fun `还不知道第几周时只有全面重新获取能用`() {
        val ranges = TermWeeks.ranges(0)
        assertFalse(ranges[0].needsFullFetch)
        assertEquals(WeekRange(1, last), ranges[0])
        assertTrue(ranges.drop(1).all { it.needsFullFetch })
    }

    @Test
    fun `离谱的周次同样按不知道处理`() {
        assertTrue(TermWeeks.ranges(-3)[1].needsFullFetch)
        assertTrue(TermWeeks.ranges(last + 5)[1].needsFullFetch)
    }
}
