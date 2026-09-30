package com.quack.curriculumexporter

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** 手机上一个可写入的日历。 */
data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String = "",
    val accessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
) {
    /** 把系统内部的账户名转换成用户能理解的来源。 */
    private fun accountLabel(): String = when {
        accountName.equals("local account", ignoreCase = true) -> "本机"
        accountName.equals("local", ignoreCase = true) -> "本机"
        // 系统预置日历挂在 birthday@localhost 这类假账户下，原样摆出来没人看得懂
        accountName.endsWith("@localhost", ignoreCase = true) -> "本机"
        accountName.contains("heytap", ignoreCase = true) -> "HeyTap"
        accountName.contains("google", ignoreCase = true) -> "Google"
        accountName.contains("default calendar", ignoreCase = true) -> "默认账户"
        accountName.contains("default_calendar", ignoreCase = true) -> "默认账户"
        accountName.equals("com.quack.curriculumexporter", ignoreCase = true) -> "本机"
        accountName.startsWith("oplus_new", ignoreCase = true) -> "本机"
        accountName.isBlank() -> "系统"
        else -> accountName
    }

    private fun friendlyName(): String {
        val normalized = displayName.lowercase()
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        // 系统预置日历的名字都是内部标识（birthday@localhost、oplus_anniversary_default_calendar…），
        // 直接摆出来全是英文，没人看得懂，这里按关键词换成中文
        keywordName(normalized)?.let { return it }
        return when {
            normalized == "local account" || normalized == "local" -> "本机日历"
            normalized == "default calendar" || normalized == "oplus new" -> "默认日历"
            else -> displayName
        }
    }

    /**
     * 按关键词把**系统内部标识**换成中文；认不出来返回 null，由调用方回退原名。
     *
     * 含非 ASCII 的名字一律不动：用户自己起的名字（哪怕是「生日聚会」这种）不能被改写成「生日」，
     * 默认的「2026-2027-1 学期课表」也不能被改写成「课表」。
     */
    private fun keywordName(normalized: String): String? {
        if (normalized.any { it.code > 0x7F }) return null
        return when {
            normalized.contains("birthday") -> "生日"
            normalized.contains("anniversary") -> "纪念日"
            normalized.contains("countdown") -> "倒计时"
            normalized.contains("holiday") -> "节假日"
            normalized.contains("festival") -> "节日"
            normalized.contains("task") || normalized.contains("todo") -> "待办"
            normalized.contains("course") || normalized.contains("class schedule") -> "课表"
            else -> null
        }
    }

    /** 列表里显示「课程 · HeyTap」，不再暴露 default_calendar 之类的内部字符串。 */
    fun label(): String {
        val name = friendlyName()
        val source = accountLabel()
        if (name.isBlank()) return source
        if (name == "本机日历" && source == "本机") return name
        if (name == "默认日历" && source == "默认账户") return name
        return "$name · $source"
    }

    /** 课程/课表相关的日历优先显示。 */
    fun isCourseCalendar(): Boolean {
        val text = "$displayName $accountName".lowercase()
        return text.contains("课表") ||
            text.contains("课程") ||
            text.contains("course") ||
            text.contains("class")
    }

    /** 相同用户可见名称的重复记录只保留一个。 */
    fun uniqueKey(): String = label().trim().lowercase()
}

/**
 * 把课表写进手机系统日历。
 *
 * 为什么不用 .ics：手机上最顺的路径是「点一下 → 课表进日历」，
 * 而 Windows 版那种「生成文件 → 传到手机 → 文件管理打开 → 选日历」在手机上反而是绕路。
 * 两种都保留：喜欢自己导的人照样可以导出 .ics。
 */
class CalendarWriter(private val context: Context) {

    /** 本 App 的包名：写进事件的 CUSTOM_APP_PACKAGE，也用来认出「这条是不是自己写的」。 */
    private val packageName: String = context.packageName

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )

        /** 一次 applyBatch 塞多少个事件。太多会让单次事务过大。 */
        private const val BATCH_SIZE = 100

        private const val ZONE_ID = "Asia/Shanghai"
        private val ZONE: ZoneId = ZoneId.of(ZONE_ID)

        const val COURSE_CALENDAR_NAME = "广理课表"
        // 不使用通用的 local account，避免厂商 Provider 把它重定向到默认日历。
        private const val LOCAL_ACCOUNT_NAME = "com.quack.curriculumexporter"
        private const val LOCAL_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL

        /**
         * 写进 CalendarContract.Events.CUSTOM_APP_URI 的认领键前缀。
         *
         * 系统日历留了 customAppPackage / customAppUri 两个字段给第三方 App 标自己的事件，
         * 于是「哪条日历事件对应哪节课」不只记在 App 的 prefs 里：
         * 记录丢了也能凭标记认回自己的事件，认不回的自家残留还能清掉，不会重复堆积。
         */
        private const val MARKER_PREFIX = "curriculumexporter://event/"

        private const val PREFS = "curriculum_import"
        private const val KEY_CAL_ID = "last_calendar_id"
        private const val KEY_CAL_NAME = "last_calendar_name"
        private const val KEY_EVENT_IDS = "last_event_ids"

        /**
         * 上次导入的事件明细（JSON 数组）。系统日历的 sync_data* 和扩展属性在 Android 13 上
         * 只允许 sync adapter 写，所以「哪条事件对应哪节课」主要记在 App 自己这里，
         * 日历里的 customAppUri 标记只作兜底。
         */
        private const val KEY_EVENTS = "last_events"

        fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        /** 上次导入留在日历里的一条事件。 */
        data class SyncedEvent(
            val eventId: Long,
            /** 课程实例 + 日期 + 节次，认「同一节课」的首选 */
            val uid: String,
            /** 课程实例 + 星期几，不含日期时间；换课改了节次时靠它认出同一节课 */
            val courseKey: String,
            /** 写入时的内容指纹，用来判断这节课有没有变化 */
            val fingerprint: String,
            val startMillis: Long,
        ) {
            val isLegacy: Boolean get() = uid.isEmpty() && courseKey.isEmpty()
        }

        /** 上次导入留下的痕迹，用来判断这次是「首次写入」还是「更新旧课表」。 */
        data class ImportRecord(
            val calendarId: Long,
            val calendarName: String,
            val events: List<SyncedEvent>,
        )

        fun loadRecord(context: Context): ImportRecord? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getLong(KEY_CAL_ID, -1L)
            if (id < 0) return null
            val calendarName = prefs.getString(KEY_CAL_NAME, "").orEmpty()

            val raw = prefs.getString(KEY_EVENTS, null)
            val events = if (raw != null) {
                parseSyncedEvents(raw)
            } else {
                // 1.0.2 及更早只记了事件 id，认不出是哪节课，同步时当过期数据清掉重建
                prefs.getString(KEY_EVENT_IDS, "").orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .filter { it > 0 }
                    .map { SyncedEvent(it, "", "", "", 0L) }
            }
            if (events.isEmpty()) return null
            return ImportRecord(id, calendarName, events)
        }

        fun saveRecord(
            context: Context,
            calendarId: Long,
            calendarName: String,
            events: List<SyncedEvent>,
        ) {
            val array = JSONArray()
            events.forEach { e ->
                array.put(
                    JSONObject()
                        .put("id", e.eventId)
                        .put("uid", e.uid)
                        .put("key", e.courseKey)
                        .put("fp", e.fingerprint)
                        .put("start", e.startMillis)
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CAL_ID, calendarId)
                .putString(KEY_CAL_NAME, calendarName)
                .putString(KEY_EVENTS, array.toString())
                .remove(KEY_EVENT_IDS)
                .apply()
        }

        private fun parseSyncedEvents(raw: String): List<SyncedEvent> = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1L)
                if (id <= 0) {
                    null
                } else {
                    SyncedEvent(
                        eventId = id,
                        uid = o.optString("uid"),
                        courseKey = o.optString("key"),
                        fingerprint = o.optString("fp"),
                        startMillis = o.optLong("start", 0L),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

        fun clearRecord(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    /**
     * 列出所有能写的日历（只读订阅和生日日历会被过滤掉）。
     * 名字里带「课表」的排在最前，方便直接点确认。
     */
    fun writableCalendars(): List<CalendarAccount> {
        val all = allCalendars()
        val calendars = all.filter {
            it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
                !isSpecialCalendar(it.displayName, it.accountName, it.accountType)
        }
        val candidates = all.filter {
            !isSpecialCalendar(it.displayName, it.accountName, it.accountType)
        }
        // 某些厂商的 CalendarProvider 会把可写日历返回成未知权限级别。
        // 列表为空时仍展示非特殊候选项，实际写入失败会在结果页明确提示。
        val visible = if (calendars.isNotEmpty()) calendars else candidates
        return visible
            .distinctBy { it.uniqueKey() }
            .sortedWith(
                compareByDescending<CalendarAccount> { it.isCourseCalendar() }
                    .thenBy { it.label().lowercase() }
            )
    }

    fun allCalendarsForPicker(): List<CalendarAccount> = allCalendars()
        .distinctBy { it.uniqueKey() }
        .sortedWith(
            compareByDescending<CalendarAccount> { it.isCourseCalendar() }
                .thenBy { it.label().lowercase() }
        )

    private fun allCalendars(): List<CalendarAccount> {
        val result = ArrayList<CalendarAccount>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val displayName = c.getString(1).orEmpty().trim()
                if (displayName.isEmpty()) continue
                result += CalendarAccount(
                    id = c.getLong(0),
                    displayName = displayName,
                    accountName = c.getString(2).orEmpty().trim(),
                    accountType = c.getString(3).orEmpty().trim(),
                    accessLevel = c.getInt(4),
                )
            }
        }
        return result
    }

    private fun verifyCalendar(calendarId: Long, calendarName: String) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val valid = context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            c.moveToFirst() &&
                c.getLong(0) == calendarId &&
                c.getString(1).orEmpty() == calendarName &&
                c.getString(2).orEmpty() == LOCAL_ACCOUNT_NAME &&
                c.getString(3).orEmpty() == LOCAL_ACCOUNT_TYPE
        } == true
        if (!valid) {
            throw IllegalStateException("课表日历没有创建成功，系统返回的日历不是「$calendarName」")
        }
    }

    /** 创建或复用用户指定名称的本机空日历，供课程事件专用。 */
    fun createCourseCalendar(calendarName: String = COURSE_CALENDAR_NAME): CalendarAccount {
        val name = calendarName.trim().ifEmpty { COURSE_CALENDAR_NAME }
        val existing = allCalendars().firstOrNull {
            it.displayName.equals(name, ignoreCase = true) &&
                it.accountName.equals(LOCAL_ACCOUNT_NAME, ignoreCase = true) &&
                it.accountType.equals(LOCAL_ACCOUNT_TYPE, ignoreCase = true)
        }
        if (existing != null) {
            verifyCalendar(existing.id, name)
            return existing
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF111111.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZONE_ID)
        }
        val insertUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, LOCAL_ACCOUNT_TYPE)
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
        val uri = context.contentResolver.insert(insertUri, values)
            ?: throw IllegalStateException("手机日历 Provider 拒绝创建课表日历")
        val id = uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("课表日历创建成功，但没有返回日历编号")
        verifyCalendar(id, name)
        return CalendarAccount(
            id = id,
            displayName = name,
            accountName = LOCAL_ACCOUNT_NAME,
            accountType = LOCAL_ACCOUNT_TYPE,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
        )
    }

    /** 系统生成的特殊日历通常不可用于课程导入，且名称对用户没有帮助。 */
    private fun isSpecialCalendar(displayName: String, accountName: String, accountType: String): Boolean {
        val text = "$displayName $accountName $accountType".lowercase()
        val keywords = listOf(
            "birthday", "anniversary", "countdown", "holiday", "festival",
            "生日", "纪念日", "倒计时", "节假日", "节日",
        )
        return keywords.any { text.contains(it) }
    }

    /** 批量写入，返回新建事件的行 id（用于下次「先删旧的」）。 */
    fun write(calendarId: Long, events: List<IcsEvent>): List<Long> {
        if (events.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val created = ArrayList<Long>()

        events.chunked(BATCH_SIZE).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { e ->
                ops += ContentProviderOperation
                    .newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(valuesOf(calendarId, e))
                    .build()
            }
            resolver.applyBatch(CalendarContract.AUTHORITY, ops).forEach { result ->
                result.uri?.lastPathSegment?.toLongOrNull()?.let { created += it }
            }
        }
        if (created.size != events.size) {
            throw IllegalStateException("系统日历只接受了 ${created.size}/${events.size} 个事件")
        }
        verifyEventsBelongToCalendar(created, calendarId)
        return created
    }

    private fun verifyEventsBelongToCalendar(eventIds: List<Long>, calendarId: Long) {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
        )
        val placeholders = eventIds.joinToString(",") { "?" }
        val args = eventIds.map { it.toString() }.toTypedArray()
        val uri = CalendarContract.Events.CONTENT_URI
        val found = HashSet<Long>()
        context.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Events._ID} IN ($placeholders)",
            args,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) != calendarId) {
                    throw IllegalStateException("课程事件被系统写入了其他日历，而不是目标课表日历")
                }
                found += c.getLong(0)
            }
        }
        if (found.size != eventIds.size) {
            throw IllegalStateException("系统日历没有返回全部课表事件")
        }
    }

    /** 一次增量同步要做的改动。 */
    data class SyncPlan(
        val insert: List<IcsEvent> = emptyList(),
        val update: List<Pair<Long, IcsEvent>> = emptyList(),
        val delete: List<Long> = emptyList(),
        /** 内容没变、原样留着的 */
        val keep: Int = 0,
        /** 已经过去的日程：按约定原样不动 */
        val past: Int = 0,
    ) {
        val changes: Int get() = insert.size + update.size + delete.size
    }

    /**
     * 比对「日历里上次导入的」和「这次抓到的」，算出最小改动。
     *
     * 六条规则：
     *  1. [now] 之前的日程一律不动 —— 总不能改写过去；
     *  2. 认人优先用 uid（课程实例 + 日期 + 节次），认不出再用 courseKey（课程实例 + 星期几），
     *     所以换课改了节次也认得出是同一节课，走更新而不是删掉重加；
     *  3. 只动「自己上次写过的」事件，用户手动加进这个日历的日程不会被误伤；
     *  4. 记录丢了（清过数据、装过旧版本）时，先凭写进日历的自家标记认领，
     *     再退一步拿「课程名 + 开始时间」认领，认得出的走更新而不是新增；
     *  5. 自己写的、记录里却没有、这次也认不回来的残留（上次记录丢失留下的重复），
     *     一并列为删除 —— 重复不会越堆越多。用户手动加的日程没有标记，永远不动。
     *  6. 换过目标日历（在「选择要写入的日历」里挑了别的）时，别的日历里那份自家课表也一并删除，
     *     课表只留新日历那一份 —— 否则它既不在记录里、也认不回来，会变成清不掉的幽灵 + 两份重复。
     *
     * [allowDelete] = false 时只保留「增 / 改」，上面那些删除规则全部跳过（按周快速获取时用）。
     */
    fun planSync(
        calendarId: Long,
        events: List<IcsEvent>,
        now: Long,
        /**
         * false = 只增改、不删。
         *
         * 按周快速获取（近 3 周等）只拿到一部分数据，若照常删，范围外那些"这次没出现"的未来课程
         * 会被当成"教务系统里删掉了"而清掉。所以窄范围写入一律不删，过期课的清理留给完整获取。
         */
        allowDelete: Boolean = true,
    ): SyncPlan {
        val known = loadRecord(context)
            ?.takeIf { it.calendarId == calendarId }
            ?.events
            .orEmpty()
            .filter { it.eventId > 0 }
        // 用户可能已经在系统的日历 App 里删掉了几条，先看看还剩下哪些
        val aliveStarts = aliveEventStarts(known.map { it.eventId })
        val alive = known.filter { aliveStarts.containsKey(it.eventId) }

        val byUid = HashMap<String, ArrayDeque<SyncedEvent>>()
        val byCourse = HashMap<String, ArrayDeque<SyncedEvent>>()
        alive.forEach { k ->
            if (k.uid.isNotEmpty()) byUid.getOrPut(k.uid) { ArrayDeque() }.addLast(k)
            if (k.courseKey.isNotEmpty()) byCourse.getOrPut(k.courseKey) { ArrayDeque() }.addLast(k)
        }

        // 记录之外、日历里已经存在的未来事件：用来认领「记录丢了但其实是我们写的」那些
        val trackedIds = known.map { it.eventId }.toHashSet()
        val byMarker = HashMap<String, ArrayDeque<SyncedEvent>>()
        val orphans = HashMap<String, ArrayDeque<SyncedEvent>>()
        val ownStrays = ArrayList<Long>()
        futureEvents(calendarId, now).forEach { ev ->
            if (ev.id in trackedIds) return@forEach
            if (ev.owner == packageName) ownStrays += ev.id
            val queue = if (ev.marker.isNotEmpty()) {
                byMarker.getOrPut(ev.marker) { ArrayDeque() }
            } else {
                orphans.getOrPut("${ev.title}|${ev.start}") { ArrayDeque() }
            }
            queue.addLast(SyncedEvent(ev.id, "", "", "", ev.start))
        }

        val used = HashSet<Long>()
        val insert = ArrayList<IcsEvent>()
        val update = ArrayList<Pair<Long, IcsEvent>>()
        var keep = 0
        var past = 0

        for (e in events) {
            val start = startMillisOf(e)
            if (start < now) {
                past++
                continue
            }
            val hit = poll(byUid[e.uid], used)
                ?: poll(byCourse[e.courseKey], used)
                ?: poll(byMarker[markerOf(e.uid)], used)
                ?: poll(orphans["${e.title}|$start"], used)
            if (hit == null) {
                insert += e
                continue
            }
            used += hit.eventId
            // 认领来的还没有指纹，这次先按「要更新」处理，让 record 从这次起真正认住它
            if (hit.fingerprint.isNotEmpty() && hit.fingerprint == fingerprintOf(e)) {
                keep++
            } else {
                update += hit.eventId to e
            }
        }

        // 上次写过、这次课表里没有了、而且还没到的课 → 课被取消了
        val delete = ArrayList<Long>()
        if (allowDelete) {
            alive.filter { it.eventId !in used && (aliveStarts[it.eventId] ?: 0L) >= now }
                .forEach { delete += it.eventId }
            // 自己写过、记录里没留、这次也认不回来的 → 上次记录丢过留下的重复，清掉
            ownStrays.filter { it !in used }.forEach { delete += it }
            // 换过目标日历的话，别的日历里那份自家课表也清掉（规则 6），课表只留新日历这一份
            ownEventIdsOutside(calendarId).forEach {
                if (it !in used && it !in delete) delete += it
            }
        }

        return SyncPlan(insert, update, delete, keep, past)
    }

    /**
     * 按计划落地，返回新的「上次导入」记录。
     * [isCancelled] 在中途返回 true 时停下，已经做完的部分照样记下来（下次接着对齐）。
     */
    fun applySync(
        calendarId: Long,
        plan: SyncPlan,
        isCancelled: () -> Boolean = { false },
        onStage: (String) -> Unit = {},
    ): List<SyncedEvent> {
        val old = loadRecord(context)?.takeIf { it.calendarId == calendarId }?.events.orEmpty()

        val doDelete = plan.delete.isNotEmpty() && !isCancelled()
        if (doDelete) {
            onStage("移除 ${plan.delete.size} 条已取消的课…")
            deleteEvents(plan.delete)
        }
        val doUpdate = plan.update.isNotEmpty() && !isCancelled()
        if (doUpdate) {
            onStage("更新 ${plan.update.size} 条有变化的课…")
            updateEvents(calendarId, plan.update)
        }
        val doInsert = plan.insert.isNotEmpty() && !isCancelled()
        val created = if (doInsert) {
            onStage("写入 ${plan.insert.size} 条新课…")
            write(calendarId, plan.insert)
        } else {
            emptyList()
        }

        val deletedIds = if (doDelete) plan.delete.toHashSet() else emptySet()
        val changed = if (doUpdate) plan.update.toMap() else emptyMap()
        val result = ArrayList<SyncedEvent>(old.size + created.size)
        val oldIds = HashSet<Long>()
        old.forEach { r ->
            if (r.eventId in deletedIds) return@forEach
            oldIds += r.eventId
            val e = changed[r.eventId]
            result += if (e != null) syncedOf(r.eventId, e) else r
        }
        // 认领来的事件原本不在记录里，也要记下来，下次才能精确认人
        if (doUpdate) {
            plan.update.forEach { (id, e) -> if (id !in oldIds) result += syncedOf(id, e) }
        }
        created.forEachIndexed { i, id -> result += syncedOf(id, plan.insert[i]) }
        return result
    }

    /** 只改事件内容，不动事件本身 —— 你在日历里给它设的提醒、颜色都会留着。 */
    private fun updateEvents(calendarId: Long, updates: List<Pair<Long, IcsEvent>>) {
        val resolver = context.contentResolver
        updates.chunked(BATCH_SIZE).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { (id, e) ->
                ops += ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                    .withValues(valuesOf(calendarId, e))
                    .build()
            }
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
    }

    /** 这些事件 id 里还活着的，返回 id → 开始时间（毫秒）。 */
    private fun aliveEventStarts(eventIds: List<Long>): Map<Long, Long> {
        if (eventIds.isEmpty()) return emptyMap()
        val result = HashMap<Long, Long>()
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DTSTART)
        eventIds.distinct().chunked(BATCH_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events._ID} IN ($placeholders)",
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    result[c.getLong(0)] = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        }
        return result
    }

    /** 目标日历里还没到的那些事件：id、标题、开始时间，外加写入者留下的标记。 */
    private fun futureEvents(calendarId: Long, now: Long): List<CalEvent> {
        val result = ArrayList<CalEvent>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DELETED} = 0",
            arrayOf(calendarId.toString(), now.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                result += CalEvent(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    start = if (c.isNull(2)) 0L else c.getLong(2),
                    owner = c.getString(3).orEmpty(),
                    marker = c.getString(4).orEmpty(),
                )
            }
        }
        return result
    }

    /**
     * 别的日历里、由本 App 写入的事件 id（规则 6 用）。
     *
     * 只在换过目标日历时才非空：这些事件既不在导入记录里（记录只跟当前日历对齐），
     * 也不会被任何一次同步认领，留着就是永远清不掉的幽灵 + 与新日历重复的那一份。
     * 只按自家包名找，用户手动加进别的日历的日程不会被误伤。
     */
    private fun ownEventIdsOutside(calendarId: Long): List<Long> {
        val ids = ArrayList<Long>()
        val selection = "${CalendarContract.Events.CALENDAR_ID} != ? AND " +
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(calendarId.toString(), packageName)
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                selection,
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
        } catch (_: Exception) {
            // 个别 ROM 未必支持按 customAppPackage 过滤。查不了就当没有、旧日历那份留着
            //（退化成老行为），但不能因为这个把整次同步搞崩。
        }
        return ids
    }

    /** 日历里的一条事件，只取比对要用的字段。 */
    private class CalEvent(
        val id: Long,
        val title: String,
        val start: Long,
        /** 写这条事件的 App 包名；用户自己在日历里加的日程这里是空的 */
        val owner: String,
        /** 本 App 写的时候留下的认领键；不是本 App 写的就是空的 */
        val marker: String,
    )

    /** 写进 CUSTOM_APP_URI 的认领键：记录丢了也能凭它认出是自己的哪节课。 */
    private fun markerOf(uid: String): String =
        if (uid.isEmpty()) "" else MARKER_PREFIX + Uri.encode(uid)

    /** 从候选队列里取一个还没被占用的记录。 */
    private fun poll(queue: ArrayDeque<SyncedEvent>?, used: Set<Long>): SyncedEvent? {
        if (queue == null) return null
        while (queue.isNotEmpty()) {
            val head = queue.removeFirst()
            if (head.eventId !in used) return head
        }
        return null
    }

    private fun syncedOf(eventId: Long, e: IcsEvent) = SyncedEvent(
        eventId = eventId,
        uid = e.uid,
        courseKey = e.courseKey,
        fingerprint = fingerprintOf(e),
        startMillis = startMillisOf(e),
    )

    private fun startMillisOf(e: IcsEvent): Long =
        millisOf(e.date, if (e.allDay) "000000" else e.start)

    /** 内容指纹：只有真变了才值得去改日历。 */
    private fun fingerprintOf(e: IcsEvent): String = listOf(
        e.title,
        e.location,
        e.description,
        e.date,
        e.start,
        e.end,
        if (e.allDay) "1" else "0",
        e.endDate,
    ).joinToString("|").hashCode().toString()

    /** 按事件 id 删除（用户自己删掉的会被跳过，不影响别的）。 */
    fun deleteEvents(eventIds: List<Long>): Int {
        if (eventIds.isEmpty()) return 0
        val resolver = context.contentResolver
        var deleted = 0
        eventIds.chunked(BATCH_SIZE).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { id ->
                ops += ContentProviderOperation
                    .newDelete(CalendarContract.Events.CONTENT_URI)
                    .withSelection("${CalendarContract.Events._ID} = ?", arrayOf(id.toString()))
                    .build()
            }
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, ops)
            // count 是 Int?：删除时表示实际删掉的行数，0 或 null 都算没删着
            deleted += results.count { (it.count ?: 0) > 0 }
        }
        return deleted
    }

    private fun valuesOf(calendarId: Long, e: IcsEvent): ContentValues {
        var start = millisOf(e.date, if (e.allDay) "000000" else e.start)
        var end = millisOf(if (e.allDay) e.endDate else e.date, if (e.allDay) "000000" else e.end)
        // 日历 App 对 DTEND <= DTSTART 的事件会直接吞掉，兜一下底
        if (end <= start) end = start + 45 * 60 * 1000L

        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            // 自家标记：记录丢了也认得出这是本 App 写的，认不回的残留才能清掉
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
            val marker = markerOf(e.uid)
            if (marker.isNotEmpty()) put(CalendarContract.Events.CUSTOM_APP_URI, marker)
            put(CalendarContract.Events.TITLE, e.title)
            put(CalendarContract.Events.DESCRIPTION, e.description)
            put(CalendarContract.Events.EVENT_LOCATION, e.location)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZONE_ID)
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            // 提醒交给日历 App 自己的默认设置，不在这里硬塞
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
    }

    private fun millisOf(date: String, time: String): Long =
        java.time.ZonedDateTime.of(
            LocalDate.parse(date, DATE_FMT),
            LocalTime.parse(time, TIME_FMT),
            ZONE,
        ).toInstant().toEpochMilli()

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
}
