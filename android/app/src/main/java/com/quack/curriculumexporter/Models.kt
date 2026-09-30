package com.quack.curriculumexporter

/**
 * 一条课程安排。
 *
 * 字段名与教务系统返回的 JSON 一一对应（含服务端的大小写，见 [endTime]），
 * 这样和 Windows 版 Network.cs 的行为完全一致。
 */
data class CourseItem(
    /** 星期几，1=周一 … 7=周日 */
    val weekDay: String = "",
    /** 节次串，形如「第0102节」；[startNode]/[endNode] 从它里面截数字 */
    val classTime: String = "",
    val courseName: String = "",
    val teacherName: String = "",
    val classroomName: String = "",
    val buildingName: String = "",
    /** HH:mm */
    val startTime: String = "",
    /** HH:mm。服务端原始键名是 `endTIme`（大写 I），这里是已归一化的值 */
    val endTime: String = "",
    /** 该课程都在哪些周上课，形如「1-16」 */
    val classWeek: String = "",
    /** 上课班级 */
    val ktmc: String = "",
    /** 考核方式 */
    val khfs: String = "",
    /** 课程实例 id，用来拼 ICS 的 UID */
    val jx0404id: String = "",
    val xkrs: String = "",
    val startNode: Int = 0,
    val endNode: Int = 0,
)

/** 一周的数据：该周的日期网格 + 课程明细。 */
data class WeekData(
    val week: Int = 0,
    /** 与 [date] 等长，第 i 天是周几 */
    val xqid: List<Int> = emptyList(),
    /** 与 [xqid] 等长，格式 yyyy-MM-dd */
    val date: List<String> = emptyList(),
    val courses: List<CourseItem> = emptyList(),
)

/** 登录成功后的学生信息。 */
data class Student(
    val userNo: String = "",
    val name: String = "",
    val clsName: String = "",
    val academy: String = "",
    val token: String = "",
)

/** 一次抓取的完整结果。 */
data class Schedule(
    val student: Student = Student(),
    val weeks: List<WeekData> = emptyList(),
    /**
     * 本次请求的起始周。空课表的周不会出现在 [weeks] 里，
     * 所以判断「这次抓了哪些周」要看这两个字段，不能看 weeks。
     */
    val minWeek: Int = 1,
    val maxWeek: Int = 0,
    /** 因为用户取消 / 网络问题而没抓全的周数 */
    val missingWeeks: List<Int> = emptyList(),
) {
    /** 星期名，1=周一。 */
    companion object {
        val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        fun weekdayName(day: Int): String =
            WEEKDAY_NAMES.getOrNull(day - 1) ?: "周$day"
    }
}
