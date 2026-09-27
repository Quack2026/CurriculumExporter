using System;
using System.Collections.Generic;
using System.Text;

namespace CurriculumExporter
{
    // 生成标准 ICS(每个事件一个 VEVENT + VTIMEZONE),供手机日历『文件导入』
    public static class IcsBuilder
    {
        // RFC5545: 每行不超过 75 字节,超出折行(续行以空格开头)
        private static string Fold(string line)
        {
            if (Encoding.UTF8.GetByteCount(line) <= 73) return line;
            StringBuilder sb = new StringBuilder();
            string cur = "";
            int len = 0;
            foreach (char ch in line)
            {
                int b = Encoding.UTF8.GetByteCount(ch.ToString());
                if (len + b > 73)
                {
                    sb.Append(cur).Append("\r\n");
                    cur = " " + ch;
                    len = 1 + b;
                }
                else
                {
                    cur += ch;
                    len += b;
                }
            }
            if (cur.Length > 0) sb.Append(cur);
            return sb.ToString();
        }

        private static string Esc(string s)
        {
            if (s == null) return "";
            return s.Replace("\\", "\\\\").Replace(";", "\\;").Replace(",", "\\,").Replace("\r\n", "\\n").Replace("\n", "\\n").Replace("\r", "\\n");
        }

        private static string Stamp()
        {
            return DateTime.UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'");
        }

        public static string Build(List<WeekData> weeks, int maxWeek)
        {
            string stamp = Stamp();
            List<string> blocks = new List<string>();
            List<string> sorts = new List<string>();

            foreach (WeekData wk in weeks)
            {
                for (int i = 0; i < wk.xqid.Count; i++)
                {
                    int day = wk.xqid[i];
                    string date = wk.date[i];
                    string dstr = date.Replace("-", "");
                    foreach (CourseItem c in wk.courses)
                    {
                        int wd;
                        if (!int.TryParse(c.weekDay, out wd) || wd != day) continue;
                        string sT = c.startTime.Replace(":", "");
                        string eT = c.endTime.Replace(":", "");
                        string uid = c.jx0404id + "-" + dstr + "T" + sT + "@jwcydjw.gdlgxy.edu.cn";
                        string desc = "教师：" + c.teacherName + "\n上课班级：" + c.ktmc + "\n节次：第" + c.startNode + "-" + c.endNode + "节（" + c.startTime + "~" + c.endTime + "）\n周次：" + c.classWeek + " 周（第" + wk.week + "周）\n人数：" + c.xkrs + "\n考核方式：" + c.khfs;
                        string loc = c.classroomName + (c.buildingName.Length > 0 ? "  ·  " + c.buildingName : "");
                        StringBuilder ev = new StringBuilder();
                        ev.Append("BEGIN:VEVENT\r\n");
                        ev.Append("UID:").Append(uid).Append("\r\n");
                        ev.Append("DTSTAMP:").Append(stamp).Append("\r\n");
                        ev.Append("DTSTART;TZID=Asia/Shanghai:").Append(dstr).Append("T").Append(sT).Append("00\r\n");
                        ev.Append("DTEND;TZID=Asia/Shanghai:").Append(dstr).Append("T").Append(eT).Append("00\r\n");
                        ev.Append("SEQUENCE:0\r\nSTATUS:CONFIRMED\r\nTRANSP:OPAQUE\r\n");
                        ev.Append("SUMMARY:").Append(Esc(c.courseName)).Append("\r\n");
                        ev.Append("LOCATION:").Append(Esc(loc)).Append("\r\n");
                        ev.Append("DESCRIPTION:").Append(Esc(desc)).Append("\r\n");
                        ev.Append("END:VEVENT");
                        blocks.Add(ev.ToString());
                        sorts.Add(dstr + sT);
                    }
                }
            }

            // 军训:第14、15周(课表为空)全天事件
            StringBuilder mt = new StringBuilder();
            mt.Append("BEGIN:VEVENT\r\n");
            mt.Append("UID:military-training-2026@jwcydjw.gdlgxy.edu.cn\r\n");
            mt.Append("DTSTAMP:").Append(stamp).Append("\r\n");
            mt.Append("DTSTART;VALUE=DATE:20261207\r\n");
            mt.Append("DTEND;VALUE=DATE:20261221\r\n");
            mt.Append("SEQUENCE:0\r\nSTATUS:CONFIRMED\r\nTRANSP:OPAQUE\r\n");
            mt.Append("SUMMARY:").Append(Esc("冬季军训（第14-15周）")).Append("\r\n");
            mt.Append("LOCATION:").Append(Esc("（军训场地以学校通知为准）")).Append("\r\n");
            mt.Append("DESCRIPTION:").Append(Esc("第14、15周 2026-12-07 ~ 2026-12-20 全校停课，2026-12-21 复课。")).Append("\r\n");
            mt.Append("END:VEVENT");
            blocks.Add(mt.ToString());
            sorts.Add("20261207T000000");

            // 按开始时间排序
            int[] idx = new int[sorts.Count];
            for (int i = 0; i < idx.Length; i++) idx[i] = i;
            Array.Sort(idx, delegate(int a, int b) { return string.CompareOrdinal(sorts[a], sorts[b]); });

            StringBuilder sb = new StringBuilder();
            sb.Append("BEGIN:VCALENDAR\r\n");
            sb.Append("VERSION:2.0\r\n");
            sb.Append("PRODID:-//gdlgxy//curriculum//CN\r\n");
            sb.Append("CALSCALE:GREGORIAN\r\n");
            sb.Append("METHOD:PUBLISH\r\n");
            sb.Append("X-WR-CALNAME:2026-2027-1 学期课表\r\n");
            sb.Append("X-WR-TIMEZONE:Asia/Shanghai\r\n");
            sb.Append("BEGIN:VTIMEZONE\r\nTZID:Asia/Shanghai\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0800\r\nTZOFFSETTO:+0800\r\nTZNAME:CST\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n");
            foreach (int i in idx)
            {
                // 逐行折行
                string[] lines = blocks[i].Split(new string[] { "\r\n" }, StringSplitOptions.None);
                foreach (string ln in lines)
                {
                    sb.Append(Fold(ln)).Append("\r\n");
                }
            }
            sb.Append("END:VCALENDAR\r\n");
            return sb.ToString();
        }
    }
}