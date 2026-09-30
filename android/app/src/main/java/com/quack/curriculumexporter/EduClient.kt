package com.quack.curriculumexporter

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** 抓取过程中可以预期的失败（凭据错、会话失效、网络不通…）。 */
class EduException(
    message: String,
    /** true 表示会话/token 已失效，重试没有意义，应当立刻中止。 */
    val sessionLost: Boolean = false,
) : Exception(message)

/**
 * 教务系统客户端：登录 + 按周抓课表。
 *
 * 接口和参数与 Windows 版 Network.cs 完全一致，只换了运行环境：
 * `HttpURLConnection` 代替 `HttpWebRequest`，`org.json` 代替 `JavaScriptSerializer`。
 */
class EduClient {

    companion object {
        const val BASE = "https://jwcydjw.gdlgxy.edu.cn"

        /** 班级课表标识，学校前端页面里写死的值。 */
        const val KBJCMSID = "93F71F7506B04365A30409FC0F6EA392"

        /** 一学期按 20 周抓，与 Windows 版一致。 */
        const val DEFAULT_MAX_WEEK = 20

        /**
         * 故意沿用 Windows 版的桌面 Chrome UA。
         * 学校这套系统按 UA 分流，改 UA 有被发到另一个页面的风险，没必要冒险。
         */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

        private const val TIMEOUT_MS = 25_000

        /** 周与周之间的间隔。学校系统是"模拟你自己点网页"，别把服务器打疼。 */
        private const val WEEK_INTERVAL_MS = 150L

        /** 失败后的额外等待。 */
        private const val RETRY_BACKOFF_MS = 800L

        /** 连续这么多周失败，就认定网络/会话断了，直接收工。 */
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    var student: Student = Student()
        private set

    private var token: String = ""

    /** 登录。失败抛 [EduException]，消息可直接显示给用户。 */
    fun login(userNo: String, password: String) {
        val path = "/njwhd/login?userNo=" + URLEncoder.encode(userNo, "UTF-8") +
            "&pwd=" + URLEncoder.encode(EduCrypto.encryptPassword(password), "UTF-8") +
            "&encode=1"

        val root = parseJson(post(path, token = null, body = null))
        if (root.str("code") != "1") {
            throw EduException(root.str("Msg").ifEmpty { "登录失败（原因未知）" })
        }
        val data = root.optJSONObject("data") ?: throw EduException("登录响应格式异常")
        token = data.str("token")
        if (token.isEmpty()) throw EduException("登录成功，但服务器没返回 token")

        student = Student(
            userNo = data.str("userNo").ifEmpty { userNo },
            name = data.str("name"),
            clsName = data.str("clsName"),
            academy = data.str("academyName"),
            token = token,
        )
    }

    /**
     * 抓第 [week] 周。返回 null 表示这一周教务系统里就是空的（比如军训周）。
     * 真出错时抛 [EduException]。
     */
    private fun fetchWeek(week: Int): WeekData? {
        val path = "/njwhd/student/curriculum?week=$week&kbjcmsid=$KBJCMSID"
        val root = parseJson(post(path, token = token, body = null))

        if (root.str("code") == "401") {
            throw EduException("会话已失效，请重新获取一次", sessionLost = true)
        }
        // 正常响应是数组，部分网关会把只有一周的数据直接包成对象。
        val day = when (val data = root.opt("data")) {
            is org.json.JSONArray -> data.optJSONObject(0)
            is JSONObject -> data
            else -> null
        } ?: return null

        val xqid = ArrayList<Int>()
        val dates = ArrayList<String>()
        day.optJSONArray("date")?.let { grid ->
            for (i in 0 until grid.length()) {
                val cell = grid.optJSONObject(i) ?: continue
                xqid += cell.str("xqid").toIntOrNull() ?: 0
                dates += cell.str("mxrq")
            }
        }

        val courses = ArrayList<CourseItem>()
        day.optJSONArray("courses")?.let { list ->
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                val classTime = c.str("classTime")
                courses += CourseItem(
                    weekDay = c.str("weekDay"),
                    classTime = classTime,
                    courseName = c.str("courseName"),
                    teacherName = c.str("teacherName"),
                    classroomName = c.str("classroomName"),
                    buildingName = c.str("buildingName"),
                    startTime = c.str("startTime"),
                    endTime = c.endTimeCompat(),
                    classWeek = c.str("classWeek"),
                    ktmc = c.str("ktmc"),
                    khfs = c.str("khfs"),
                    jx0404id = c.str("jx0404id"),
                    xkrs = c.str("xkrs"),
                    startNode = nodeAt(classTime, 1),
                    endNode = nodeAt(classTime, 3),
                )
            }
        }
        return WeekData(week = week, xqid = xqid, date = dates, courses = courses)
    }

    /**
     * 抓 [minWeek]~[maxWeek] 周的课表，默认整学期。
     *
     * 支持范围是为了快：长按「获取课表」可以只抓近 3 / 近 5 周。
     * 无论范围多宽，永远是**串行**的一周一个请求，不并发 —— 别给学校服务器添麻烦。
     *
     * @param onProgress 每条进度都会回调一次，直接丢进界面日志
     * @param shouldStop 返回 true 时尽快收尾，已抓到的部分照样返回
     */
    fun fetchAll(
        minWeek: Int = 1,
        maxWeek: Int = DEFAULT_MAX_WEEK,
        onProgress: (String) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Schedule {
        val weeks = ArrayList<WeekData>()
        var consecutiveFailures = 0

        for (week in minWeek..maxWeek) {
            if (shouldStop()) {
                onProgress("已取消，剩 ${maxWeek - week + 1} 周没抓。")
                break
            }
            try {
                val wk = fetchWeek(week)
                consecutiveFailures = 0
                if (wk == null || wk.courses.isEmpty()) {
                    onProgress("第 $week 周：空课表")
                } else {
                    weeks += wk
                    onProgress("第 $week 周：${wk.courses.size} 条课程")
                }
            } catch (e: EduException) {
                if (e.sessionLost) throw e
                consecutiveFailures++
                onProgress("第 $week 周失败：${e.message}")
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw EduException(
                        "连续 $consecutiveFailures 周失败，先停下。最后一次：${e.message}",
                        sessionLost = true,
                    )
                }
                sleepInterruptibly(RETRY_BACKOFF_MS, shouldStop)
                continue
            }
            sleepInterruptibly(WEEK_INTERVAL_MS, shouldStop)
        }

        val got = weeks.map { it.week }.toSet()
        return Schedule(
            student = student,
            weeks = weeks,
            minWeek = minWeek,
            maxWeek = maxWeek,
            missingWeeks = (minWeek..maxWeek).filter { it !in got },
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun post(path: String, token: String?, body: String?): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(BASE + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "$BASE/")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("token", token)
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true

            // 即使没有 body 也要走一次 outputStream：这样会带上 Content-Length: 0，
            // 和 Windows 版显式设 ContentLength = 0 的行为一致。
            conn.doOutput = true
            conn.outputStream.use { os ->
                if (!body.isNullOrEmpty()) os.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 500 也会带 JSON body（比如 token 失效返回 {"code":"401"}），要读出来给上层判断
            return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (e: SocketTimeoutException) {
            throw EduException("连接学校服务器超时，检查一下网络。")
        } catch (e: IOException) {
            throw EduException("网络错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseJson(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw EduException("服务器返回的不是预期格式，可能被校园网/WiFi 登录页拦了。")
    }

    /** 与 C# 版 `Str()` 等价：键不存在、值为 JSON null 都返回空串。 */
    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    /**
     * 服务端返回的键名是 `endTIme`（大写 I）。先按原样取，取不到再退一步试标准拼写，
     * 这样万一学校哪天改回来了也不会丢字段。
     */
    private fun JSONObject.endTimeCompat(): String {
        val raw = str("endTIme")
        return raw.ifEmpty { str("endTime") }
    }

    /**
     * 从 classTime（形如「第0102节」）里截出节次，等价于 C# 的 `classTime.Substring(at, 2)`。
     * 唯一区别：C# 在长度刚好不够时会抛异常，这里返回 0，不至于因为一条脏数据废掉整个抓取。
     */
    private fun nodeAt(classTime: String, at: Int): Int {
        if (classTime.length < at + 2) return 0
        return classTime.substring(at, at + 2).toIntOrNull() ?: 0
    }

    /** 可被打断的等待：取消请求后不用干等一整段间隔。 */
    private fun sleepInterruptibly(ms: Long, shouldStop: () -> Boolean) {
        var waited = 0L
        while (waited < ms && !shouldStop()) {
            Thread.sleep(100)
            waited += 100
        }
    }
}
