using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web;
using System.Web.Script.Serialization;

namespace CurriculumExporter
{
    // 一条课程记录
    public class CourseItem
    {
        public string weekDay = "";
        public string classTime = "";
        public string courseName = "";
        public string teacherName = "";
        public string classroomName = "";
        public string buildingName = "";
        public string startTime = "";
        public string endTime = "";
        public string classWeek = "";
        public string ktmc = "";
        public string khfs = "";
        public string jx0404id = "";
        public string xkrs = "";
        public int startNode = 0;
        public int endNode = 0;
    }

    // 一周的数据
    public class WeekData
    {
        public int week = 0;
        public List<int> xqid = new List<int>();
        public List<string> date = new List<string>();
        public List<CourseItem> courses = new List<CourseItem>();
    }

    // 教务系统客户端:登录 + 抓课表
    public class EduClient
    {
        public const string BASE = "https://jwcydjw.gdlgxy.edu.cn";
        public const string KBJCMSID = "93F71F7506B04365A30409FC0F6EA392";
        private const string AES_KEY = "qzkj1kjghd=876&*";

        public string Token = "";
        public string Name = "";
        public string ClsName = "";
        public string Academy = "";
        public string UserNo = "";

        // 前端 xC.encrypt: AES-128-ECB(JSON.stringify(pwd), key) -> base64 -> btoa
        public static string EncryptPwd(string pwd)
        {
            using (Aes aes = Aes.Create())
            {
                aes.Key = Encoding.UTF8.GetBytes(AES_KEY);
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.PKCS7;
                ICryptoTransform enc = aes.CreateEncryptor();
                byte[] plain = Encoding.UTF8.GetBytes("\"" + pwd + "\"");
                byte[] cipher = enc.TransformFinalBlock(plain, 0, plain.Length);
                string b64 = Convert.ToBase64String(cipher);
                return Convert.ToBase64String(Encoding.ASCII.GetBytes(b64));
            }
        }

        private static string Post(string path, string token, string body)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(BASE + path);
            req.Method = "POST";
            req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36";
            req.Referer = BASE + "/";
            req.Accept = "application/json, text/plain, */*";
            req.Headers.Add("X-Requested-With", "XMLHttpRequest");
            req.Timeout = 25000;
            req.ReadWriteTimeout = 25000;
            req.KeepAlive = false;
            if (!string.IsNullOrEmpty(token)) req.Headers.Add("token", token);
            if (!string.IsNullOrEmpty(body))
            {
                byte[] data = Encoding.UTF8.GetBytes(body);
                req.ContentType = "application/x-www-form-urlencoded; charset=UTF-8";
                req.ContentLength = data.Length;
                using (Stream s = req.GetRequestStream()) { s.Write(data, 0, data.Length); }
            }
            else
            {
                req.ContentLength = 0;
            }
            try
            {
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    return sr.ReadToEnd();
                }
            }
            catch (WebException we)
            {
                // 500 等错误响应也带 JSON body,读出来给上层判断
                if (we.Response != null)
                {
                    using (StreamReader sr = new StreamReader(we.Response.GetResponseStream(), Encoding.UTF8))
                    {
                        return sr.ReadToEnd();
                    }
                }
                throw;
            }
        }

        private static Dictionary<string, object> Json(string s)
        {
            JavaScriptSerializer ser = new JavaScriptSerializer();
            ser.MaxJsonLength = 20 * 1024 * 1024;
            return (Dictionary<string, object>)ser.DeserializeObject(s);
        }

        private static string Str(Dictionary<string, object> d, string k)
        {
            if (d == null) return "";
            object v;
            if (!d.TryGetValue(k, out v) || v == null) return "";
            return v.ToString();
        }

        // 登录,成功返回 true
        public bool Login(string user, string pwd, out string message)
        {
            message = "";
            string encPwd = HttpUtility.UrlEncode(EncryptPwd(pwd));
            string path = "/njwhd/login?userNo=" + HttpUtility.UrlEncode(user) + "&pwd=" + encPwd + "&encode=1";
            string txt = Post(path, null, null);
            Dictionary<string, object> root = Json(txt);
            string code = Str(root, "code");
            if (code != "1")
            {
                message = Str(root, "Msg");
                if (message.Length == 0) message = "登录失败(未知原因)";
                return false;
            }
            object dobs;
            Dictionary<string, object> d = null;
            if (root.TryGetValue("data", out dobs)) d = dobs as Dictionary<string, object>;
            if (d == null) { message = "登录响应格式异常"; return false; }
            Token = Str(d, "token");
            Name = Str(d, "name");
            ClsName = Str(d, "clsName");
            Academy = Str(d, "academyName");
            UserNo = Str(d, "userNo");
            if (Token.Length == 0) { message = "未取到 token"; return false; }
            return true;
        }

        // 抓取第 w 周
        public WeekData FetchWeek(int w)
        {
            string path = "/njwhd/student/curriculum?week=" + w + "&kbjcmsid=" + KBJCMSID;
            string txt = Post(path, Token, null);
            Dictionary<string, object> root = Json(txt);
            object dobs;
            if (!root.TryGetValue("data", out dobs)) return null;
            object[] arr = dobs as object[];
            if (arr == null || arr.Length == 0) return null;
            Dictionary<string, object> d = arr[0] as Dictionary<string, object>;
            if (d == null) return null;

            WeekData wk = new WeekData();
            wk.week = w;

            object dobj;
            if (d.TryGetValue("date", out dobj))
            {
                object[] dates = dobj as object[];
                if (dates != null)
                {
                    foreach (object o in dates)
                    {
                        Dictionary<string, object> dd = o as Dictionary<string, object>;
                        if (dd == null) continue;
                        int id;
                        int.TryParse(Str(dd, "xqid"), out id);
                        wk.xqid.Add(id);
                        wk.date.Add(Str(dd, "mxrq"));
                    }
                }
            }
            object cobj;
            if (d.TryGetValue("courses", out cobj))
            {
                object[] cs = cobj as object[];
                if (cs != null)
                {
                    foreach (object o in cs)
                    {
                        Dictionary<string, object> c = o as Dictionary<string, object>;
                        if (c == null) continue;
                        if (cs.Length == 0) continue;
                        int xk; int.TryParse(Str(c, "xkrs"), out xk);
                        CourseItem it = new CourseItem();
                        it.weekDay = Str(c, "weekDay");
                        it.classTime = Str(c, "classTime");
                        it.courseName = Str(c, "courseName");
                        it.teacherName = Str(c, "teacherName");
                        it.classroomName = Str(c, "classroomName");
                        it.buildingName = Str(c, "buildingName");
                        it.startTime = Str(c, "startTime");
                        it.endTime = Str(c, "endTIme");
                        it.classWeek = Str(c, "classWeek");
                        it.ktmc = Str(c, "ktmc");
                        it.khfs = Str(c, "khfs");
                        it.jx0404id = Str(c, "jx0404id");
                        it.xkrs = xk.ToString();
                        int sn, en;
                        int.TryParse(it.classTime.Length >= 4 ? it.classTime.Substring(1, 2) : "0", out sn);
                        int.TryParse(it.classTime.Length >= 4 ? it.classTime.Substring(3, 2) : "0", out en);
                        it.startNode = sn;
                        it.endNode = en;
                        wk.courses.Add(it);
                    }
                }
            }
            return wk;
        }
    }
}