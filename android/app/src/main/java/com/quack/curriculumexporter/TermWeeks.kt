package com.quack.curriculumexporter

import android.content.Context
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「今天在第几周」。
 *
 * 校历只有教务系统知道，所以本应用不去猜：任何一次获取（哪怕只抓一周）拿到的数据里
 * 都带着该周的日期网格，取该周「周一那天的日期」，往前推 `(周次 - 1) * 7` 天就是第 1 周周一。
 * 记下这个起点之后，按天数差算周次，不用再问服务器。
 *
 * 于是长按「获取课表」里的「近 3 周」才能真的只抓 3 周 —— 第一次必须先完整获取一次。
 */
object TermWeeks {

    /** 与 MainActivity 共用同一份 prefs（都是「上次运行留下的状态」）。 */
    private const val PREFS = "curriculum_prefs"
    private const val KEY_FIRST_MONDAY = "term_first_monday"

    /** 算出来超过这个周数就认为校历起点过期了（放假太久没更新），当不知道处理。 */
    private const val MAX_SANE_WEEK = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 获取成功后记下校历起点。数据不完整时静默跳过，绝不影响主流程。 */
    fun remember(context: Context, schedule: Schedule) {
        val anchor = schedule.weeks.firstOrNull { it.week > 0 && it.date.isNotEmpty() } ?: return
        val monday = mondayOf(anchor) ?: return
        val first = try {
            LocalDate.parse(monday).minusWeeks((anchor.week - 1).toLong())
        } catch (_: Exception) {
            return
        }
        prefs(context).edit().putString(KEY_FIRST_MONDAY, first.toString()).apply()
    }

    /** 今天第几周；还没获取过、或起点已经过期时返回 0（调用方据此要求先完整获取一次）。 */
    fun today(context: Context): Int {
        val first = prefs(context).getString(KEY_FIRST_MONDAY, null) ?: return 0
        val start = try {
            LocalDate.parse(first)
        } catch (_: Exception) {
            return 0
        }
        val days = ChronoUnit.DAYS.between(start, LocalDate.now())
        if (days < 0) return 0
        val week = days / 7 + 1
        return if (week > MAX_SANE_WEEK) 0 else week.toInt()
    }

    /** [week] 这一周里周一那天，格式 yyyy-MM-dd。 */
    private fun mondayOf(week: WeekData): String? {
        val at = week.xqid.indexOfFirst { it == 1 }
        if (at < 0 || at >= week.date.size) return null
        return week.date[at].takeIf { it.length == 10 }
    }

    /**
     * 长按「获取课表」菜单里的四个范围，全都以**本周往后**算（已经上过的课不重抓）。
     *
     * 顺序固定：全面重新获取、全面更新、近 5 周、近 3 周。
     * [current] = 0（还没完整获取过）时后三个返回 `minWeek = 0`，调用方据此只给提示不抓取
     * —— 校历起点只有教务系统的数据里才有，猜不得。
     */
    fun ranges(current: Int): List<WeekRange> {
        val last = EduClient.DEFAULT_MAX_WEEK
        if (current !in 1..last) {
            return listOf(
                WeekRange(1, last),
                WeekRange(0, last),
                WeekRange(0, last),
                WeekRange(0, last),
            )
        }
        return listOf(
            WeekRange(1, last),
            WeekRange(current, last),
            WeekRange(current, (current + 4).coerceAtMost(last)),
            WeekRange(current, (current + 2).coerceAtMost(last)),
        )
    }
}

/** 一个周范围。[minWeek] = 0 表示「还不知道今天第几周」。 */
data class WeekRange(val minWeek: Int, val maxWeek: Int) {
    /** 周次未知：点了只能提示先完整获取一次。 */
    val needsFullFetch: Boolean get() = minWeek <= 0
}
