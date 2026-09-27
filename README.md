# 广理课表导出 (CurriculumExporter)

一键把**广东理工学院教务系统**的课表导出成手机日历能直接导入的 `.ics` 文件。

> 单文件 exe(.NET Framework 4.8),**零依赖**、**完全本地运行**、**代码开源可自行编译核对**。

---

## 为什么做这个

学校教务系统只能**在线看**课表,没有导出功能;手机上每次查课表都要:
登录 → 点进课表 → 切周次。一学期十几周,来回翻很烦。

这个工具把**整个学期**的课表一次性导出成标准日历文件,导入手机后:
- 直接在系统日历里看,**不用再登录教务系统**
- 支持课前提醒(用日历自带的能力)
- 军训周会单独标出来,不会以为是"这周没课"

---

## 使用方法

1. 下载 `curriculum-exporter.exe`(**或者自己编译**,见下文——推荐,这样你能确认它没干坏事)
2. 双击运行,填入学号和密码,点「获取课表」
3. 程序自动登录 → 抓取全学期课表 → 在**桌面**生成 `课表.ics`
4. 把这个 `.ics` 传到手机,**点开它 → 选择「日历」打开 → 导入**

> **安卓(如 ColorOS/OPPO)**:用系统「文件管理」打开该文件,**别在微信里点开**
> (微信常把它标成 `application/octet-stream`,系统就不会弹「日历」选项)。
> 导入前建议先新建一个叫「课表」的日历专门装它,以后好删。

---

## 隐私与安全

发这个工具给同学,最该说清楚的就是这块:

- **只和学校官网通信**。程序唯一连接的是 `https://jwcydjw.gdlgxy.edu.cn`,
  没有任何第三方服务器、没有中转、没有统计上报、没有遥测。
- **不保存密码**。填的学号密码只存在于内存里,程序结束后即消失;
  不写配置文件、不写注册表、不留日志。
- **密码的加密方式来自学校网页本身**。学校前端在提交密码前会做一次
  AES-128-ECB 加密(密钥硬编码在前端 JS 里),本工具复刻了同一套逻辑,
  所以服务端能正常验密。传输走的是 **HTTPS**。
- **但要诚实说明**:这套加密的密钥是**公开写死在前端代码里的**,
  任何人都能解开——也就是说,它**实质上等同于明文**。这是学校系统的设计问题,
  不是本工具引入的。任何能访问这个网页的人都能拿到这个密钥。
- **代码完全开源**。你可以通读 `src/` 下的全部源码,也可以自己编译,
  用产物替换 Release 里的 exe,确认行为一致。

---

## 自己编译(推荐)

**最省心的方式:不用自己编译,直接下载 [Actions](../../actions) 里 GitHub 官方机器编译出的产物**——
那是"GitHub 编译的",不需要信任任何人的本机环境。这是本项目最推荐的用法。

如果你想在自己机器上编译(验证这份源码确实能构建出可用程序):

需要 .NET Framework 4.x(所有 Win10/11 自带)或 Visual Studio Build Tools:

```powershell
cd CurriculumExporter
powershell -ExecutionPolicy Bypass -File build.ps1
```

产物:`curriculum-exporter.exe`(文件名可随意改,不影响内容)

编译参数(见 `build.ps1`):

```
csc /target:winexe /deterministic /codepage:65001 /optimize+
    /win32manifest:src\app.manifest /win32icon:src\app.ico
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll
    /r:System.Windows.Forms.dll /r:System.Web.Extensions.dll
    src\Program.cs src\Network.cs src\IcsBuilder.cs
```

### 关于构建

`build.ps1` 优先使用 Roslyn 编译器(支持 `/deterministic`,同一份源码反复编译哈希一致)。
用系统自带的旧 `csc` 也能编,只是它会把编译时间写进 PE 头,**每次哈希都不同**——
这是编译器的老毛病,不是程序有问题。

---

## 技术说明

### 登录接口

```
POST https://jwcydjw.gdlgxy.edu.cn/njwhd/login
     ?userNo=<学号>&pwd=<加密后密码>&encode=1
```

密码处理(与学校前端 `xC.encrypt` + `btoa` 完全一致):

```
pwd = base64( AES-128-ECB( JSON.stringify(密码), key="<前端硬编码 key>" ) )
```

登录成功后返回 JWT,后续请求通过 `token` 请求头携带。

### 课表接口

```
POST /njwhd/student/curriculum?week=<1..20>&kbjcmsid=<班级课表标识>
```

按周返回,含 7 天日期网格与课程明细(课程名、教师、教室、节次、周次、班级、人数、考核方式)。

### ICS 生成

- 每个课程时段展开成**一个独立的 `VEVENT`**,UID = `课程ID-日期-时间`(稳定,重复导入可被识别)
- 内嵌 `VTIMEZONE`(`Asia/Shanghai`),保证手机上时间不乱
- 逐行按 RFC 5545 折行(**每行 ≤75 字节,按 UTF-8 字节计**)
- 全输出 **UTF-8 无 BOM + CRLF**
- **军训周(第 14–15 周)单独生成一个全天事件**,因为教务系统那两周是空课表

---

## 常见问题

**Q: 杀软/SmartScreen 报警?**
A: exe 未做代码签名(个人项目无法免费签名),点「更多信息 → 仍要运行」。
想彻底放心就自己编译,或者直接读源码。

**Q: 导入后事件重复了?**
A: 重复导入会产生重复(日历按 UID 新建、不查重)。**导入前先删掉上次导入的那批事件。**

**Q: 手机上的时间差 8 小时?**
A: 说明客户端没识别 `TZID`。可用 `tools/` 里的思路改成全 UTC 写法。

**Q: 课表变了怎么办?**
A: 重新运行程序生成新的 `课表.ics`,手机上删旧的再导一次。

**Q: 能自动同步吗(课表变了手机自动更新)?**
A: 本工具是**一次性导出**。自动同步需要 CalDAV 服务端 + 客户端,更复杂,不在本项目范围。

---

## 目录结构

```
CurriculumExporter/
├── src/
│   ├── Program.cs        GUI(输入框 / 按钮 / 日志)
│   ├── Network.cs        登录 + 课表抓取(AES 加密、HTTP、JSON)
│   ├── IcsBuilder.cs     ICS 生成(折行 / 转义 / 时区 / 军训事件)
│   ├── app.manifest      DPI 感知声明(避免高分屏界面模糊)
│   └── app.ico           程序图标
├── tools/
│   └── mkico.py          PNG → ICO 转换脚本(需要 Pillow)
├── assets/
│   └── icon.png          图标源文件
├── build.ps1             一键编译(优先 Roslyn,可复现)
└── README.md
```

---

## 免责声明

- 本项目仅供**个人学习与自用**,用于导出**本人**的课表。
- 请勿用于批量抓取、爬取他人数据或对学校服务器造成压力。
- 使用本工具产生的一切后果由使用者自行承担。
- 与广东理工学院官方无关。

## License

MIT