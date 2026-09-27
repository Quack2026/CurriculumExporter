using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace CurriculumExporter
{
    public class MainForm : Form
    {
        private TextBox txtUser;
        private TextBox txtPass;
        private Button btnGo;
        private Button btnOpen;
        private TextBox txtLog;
        private Label lblStatus;
        private string lastFile = "";

        public MainForm()
        {
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); }
            catch (Exception) { }

            Text = "广理课表导出 · 一键生成手机日历";
            ClientSize = new Size(620, 500);
            MinimumSize = new Size(560, 440);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("微软雅黑", 9F, FontStyle.Regular, GraphicsUnit.Point);

            // ================= 顶部输入区 =================
            Panel top = new Panel();
            top.Dock = DockStyle.Top;
            top.Height = 118;

            Label l1 = new Label();
            l1.Text = "学号";
            l1.Location = new Point(16, 20);
            l1.Size = new Size(44, 24);
            l1.TextAlign = ContentAlignment.MiddleLeft;
            top.Controls.Add(l1);

            txtUser = new TextBox();
            txtUser.Location = new Point(62, 18);
            txtUser.Size = new Size(330, 25);
            txtUser.Font = new Font("Consolas", 10F);
            top.Controls.Add(txtUser);

            Label l2 = new Label();
            l2.Text = "密码";
            l2.Location = new Point(16, 56);
            l2.Size = new Size(44, 24);
            l2.TextAlign = ContentAlignment.MiddleLeft;
            top.Controls.Add(l2);

            txtPass = new TextBox();
            txtPass.Location = new Point(62, 54);
            txtPass.Size = new Size(330, 25);
            txtPass.Font = new Font("Consolas", 10F);
            txtPass.UseSystemPasswordChar = true;
            top.Controls.Add(txtPass);

            btnGo = new Button();
            btnGo.Text = "获取课表";
            btnGo.Location = new Point(404, 18);
            btnGo.Size = new Size(190, 61);
            btnGo.Font = new Font("微软雅黑", 11F, FontStyle.Bold);
            btnGo.Click += new EventHandler(btnGo_Click);
            top.Controls.Add(btnGo);

            // ================= 底部状态区 =================
            Panel bottom = new Panel();
            bottom.Dock = DockStyle.Bottom;
            bottom.Height = 52;

            lblStatus = new Label();
            lblStatus.Text = "就绪 — 输入学号密码后点『获取课表』";
            lblStatus.Location = new Point(16, 14);
            lblStatus.Size = new Size(400, 24);
            lblStatus.TextAlign = ContentAlignment.MiddleLeft;
            lblStatus.ForeColor = Color.FromArgb(90, 90, 90);
            bottom.Controls.Add(lblStatus);

            btnOpen = new Button();
            btnOpen.Text = "打开所在文件夹";
            btnOpen.Location = new Point(430, 11);
            btnOpen.Size = new Size(164, 30);
            btnOpen.Enabled = false;
            btnOpen.Click += new EventHandler(btnOpen_Click);
            bottom.Controls.Add(btnOpen);

            // ================= 中间日志区 =================
            txtLog = new TextBox();
            txtLog.Multiline = true;
            txtLog.ScrollBars = ScrollBars.Vertical;
            txtLog.ReadOnly = true;
            txtLog.Dock = DockStyle.Fill;
            txtLog.BackColor = Color.FromArgb(250, 250, 250);
            txtLog.Font = new Font("Consolas", 9F);

            // ★ 关键:Fill 必须先 Add,Top/Bottom 后 Add(否则 Fill 占满并盖住它们)
            Controls.Add(txtLog);
            Controls.Add(bottom);
            Controls.Add(top);

            AppendLog("使用说明:");
            AppendLog("  1. 填入学号与密码,点『获取课表』");
            AppendLog("  2. 程序自动登录教务系统并抓取全学期课表");
            AppendLog("  3. 生成 课表.ics 到桌面 → 传到手机");
            AppendLog("  4. 手机上点开该文件 → 选择『日历』导入即可");
            AppendLog("");
        }

        private void AppendLog(string s)
        {
            if (txtLog.InvokeRequired)
            {
                txtLog.BeginInvoke(new Action<string>(AppendLog), new object[] { s });
                return;
            }
            txtLog.AppendText(s + "\r\n");
        }

        private void SetStatus(string s)
        {
            if (lblStatus.InvokeRequired)
            {
                lblStatus.BeginInvoke(new Action<string>(SetStatus), new object[] { s });
                return;
            }
            lblStatus.Text = s;
        }

        private void btnGo_Click(object sender, EventArgs e)
        {
            string user = txtUser.Text.Trim();
            string pwd = txtPass.Text;
            if (user.Length == 0 || pwd.Length == 0)
            {
                MessageBox.Show("请先填写学号和密码。", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            btnGo.Enabled = false;
            btnGo.Text = "获取中...";
            btnOpen.Enabled = false;
            txtLog.Clear();
            Thread t = new Thread(delegate() { DoWork(user, pwd); });
            t.IsBackground = true;
            t.Start();
        }

        private void DoWork(string user, string pwd)
        {
            bool ok = false;
            try
            {
                SetStatus("正在登录教务系统...");
                AppendLog("[1/3] 登录教务系统 ...");
                EduClient c = new EduClient();
                string msg;
                if (!c.Login(user, pwd, out msg))
                {
                    AppendLog("      登录失败:" + msg);
                    SetStatus("登录失败");
                    return;
                }
                AppendLog("      成功:" + c.Name + " / " + c.ClsName + " / " + c.Academy);
                SetStatus("登录成功,正在抓取课表...");

                AppendLog("[2/3] 抓取全学期课表 ...");
                List<WeekData> weeks = new List<WeekData>();
                int total = 0;
                for (int w = 1; w <= 20; w++)
                {
                    WeekData wk = c.FetchWeek(w);
                    if (wk != null && wk.courses.Count > 0)
                    {
                        weeks.Add(wk);
                        total += wk.courses.Count;
                        AppendLog("      第 " + w.ToString().PadLeft(2, ' ') + " 周:" + wk.courses.Count + " 条");
                    }
                    Thread.Sleep(120);
                }
                if (weeks.Count == 0)
                {
                    AppendLog("      未获取到任何课表数据");
                    SetStatus("未获取到课表");
                    return;
                }
                AppendLog("      共 " + weeks.Count + " 个有课周次," + total + " 条记录");

                AppendLog("[3/3] 生成 ICS 日历文件 ...");
                string ics = IcsBuilder.Build(weeks, 20);
                string dir = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                string file = Path.Combine(dir, "课表.ics");
                File.WriteAllText(file, ics, new UTF8Encoding(false));
                int evCount = 0;
                int p = 0;
                while (true)
                {
                    p = ics.IndexOf("BEGIN:VEVENT", p);
                    if (p < 0) break;
                    evCount++;
                    p += 5;
                }
                lastFile = file;
                AppendLog("      已生成:" + file);
                AppendLog("      " + evCount + " 个事件," + new FileInfo(file).Length + " 字节");
                AppendLog("");
                AppendLog("完成!把桌面上的 课表.ics 传到手机,点开选择『日历』导入即可。");
                SetStatus("完成 — 共 " + evCount + " 个事件");
                ok = true;
            }
            catch (Exception ex)
            {
                AppendLog("出错:" + ex.Message);
                SetStatus("出错");
            }
            finally
            {
                if (this.IsHandleCreated)
                {
                    this.BeginInvoke(new Action<bool>(delegate(bool o)
                    {
                        btnGo.Enabled = true;
                        btnGo.Text = "获取课表";
                        btnOpen.Enabled = o && lastFile.Length > 0;
                    }), new object[] { ok });
                }
            }
        }

        private void btnOpen_Click(object sender, EventArgs e)
        {
            if (lastFile.Length == 0) return;
            try
            {
                Process.Start("explorer.exe", "/select,\"" + lastFile + "\"");
            }
            catch (Exception) { }
        }
    }

    static class Program
    {
        [STAThread]
        static void Main()
        {
            try
            {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
            }
            catch (Exception) { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}