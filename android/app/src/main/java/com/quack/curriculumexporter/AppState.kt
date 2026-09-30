package com.quack.curriculumexporter

/**
 * 抓取结果在主界面和结果页之间的传递。
 *
 * 刻意不走 Intent extra：一学期 20 周、几百条课程，序列化进 Bundle 很容易撑爆
 * binder 事务（TransactionTooLargeException）。两个 Activity 同进程，静态持有最省事，
 * 进程被系统回收就回主界面重抓一次。
 */
object AppState {

    var schedule: Schedule? = null

    /** 已展开的事件（含军训），结果页拿去写日历 / 生成 .ics。 */
    var events: List<IcsEvent> = emptyList()

    /** 用户在主页选的「生成军训周事件」。 */
    var includeMilitary: Boolean = true

    /**
     * 从别的页面带回来的提示（比如设置页保存成功）。
     * 提示得显示在主界面上，所以不能在那儿直接弹 —— 那边一 finish 就没了。
     */
    var pendingNotice: String? = null

    fun clear() {
        schedule = null
        events = emptyList()
    }
}
