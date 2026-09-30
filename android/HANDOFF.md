# 交接文档 · CurriculumExporter 安卓版

> 更新时间：2026-09-30 深夜（**v1.0.0**，versionCode 1）
> 读这份文档的人默认要**继续改这个项目**；下游的「用户使用说明」在 `README.md`。
>
> **版本号说明**：安卓版从这个提交起正式定为 **1.0.0 / versionCode 1**（首个正式发布，tag 名 **`android-v1.0.0`** —— 远端早先那个 `v1.0.0` 指向 Windows 版的旧提交，别混）。
> 开发期间用过的 **1.0.1 ~ 1.0.5（versionCode 2~6）编号作废** —— 后面 5.x 各节标题里保留的那些版本号只是当时的历史记录，
> 别拿它们跟正式版号对照。**注意 versionCode 从 6 降回了 1**：手机上装过开发包的，系统会拒绝覆盖安装（Android 不允许降级），
> 必须先卸载再装，而卸载会连带清掉 prefs 和 Keystore 里的密码，得重新填一次账号。

## 1. 项目坐标

| 项 | 值 |
|---|---|
| 源码 | `C:\Tools\CurriculumExporter\android`（仓库 `Quack2026/CurriculumExporter` 的 `android/` 子目录，分支 `main`；旧路径 `C:\Tools\CurriculumExporter-Android` 只是个 junction） |
| 包名 | `com.quack.curriculumexporter` |
| 应用名 | **`GLTimetable`**（`@string/app_name`；桌面标签、系统应用信息页、主界面顶栏标题都用它。写入系统日历时新建的日历名仍是中文「广理课表」，`.ics` 文件名前缀也一样 —— 那两个是用户在日历/文件里认的东西，跟应用名不是一回事，没动） |
| 版本 | versionCode **1** / versionName **1.0.0**（首个正式发布，tag `android-v1.0.0`） |
| SDK | minSdk 26 · targetSdk 35 · compileSdk 35 · buildTools **35.0.0**（钉住，避免去下损坏的 34.0.0） |
| 技术栈 | Kotlin + XML，**0 个第三方运行时依赖**（仅测试用 junit 4.13.2 + org.json:json:20240303） |
| Windows 原版参考 | `C:\Tools\CurriculumExporter`（C#/.NET 4.8，`src/Network.cs` 是接口与加密的权威实现，**只读参考**；和安卓版同属一个仓库，仓库根就是它） |

**仓库根是 Windows 原版**，安卓版在它的 `android/` 子目录里。改安卓代码只动 `android/` 下面的文件；仓库根的 Windows 部分不要碰。提交信息含中文时用 `git commit -F <文件>`（PowerShell 5.1 传参编码坑）。

## 2. 构建与验证

```powershell
powershell -ExecutionPolicy Bypass -File C:\Tools\CurriculumExporter\android\build-apk.ps1
```

- 脚本按**自身所在目录**定位项目，所以目录整体搬家也不用改脚本。
- 脚本内部跑 `lintDebug assembleDebug testDebugUnitTest --console=plain`，日志写 `build.log`，末行打印 APK 路径与单测报告名。
- 产物：`app\build\outputs\apk\debug\app-debug.apk`（release 沿用 debug 签名）。
- 脚本**不复制** APK 到项目根；根目录那份 `curriculum-exporter-v1.0.0-android.apk` 是手工放的历史包，需要时手动同步。

工具链位置（本机实测）：

| 组件 | 路径 / 变量 |
|---|---|
| JDK | 21（脚本内设置） |
| Gradle | `D:\Android\gradle-8.11.1` |
| Android SDK | `D:\Android\Sdk`（platform-35 + build-tools 35.0.0） |
| `GRADLE_USER_HOME` | `D:\Android\gradle-home` |
| adb | `D:\Android\Sdk\platform-tools\adb.exe`（设备 `5b311bcd`，OPPO PDRM00） |

**硬性约束**

1. **所有 `.ps1` 必须 ASCII-only** —— 本机只有 Windows PowerShell 5.1，它按 GBK 读无 BOM 文件，中文注释会直接解析失败。
2. 用 `read_file` 读源码判断编码：`Get-Content` 显示中文乱码只是 PS 5.1 的显示问题，文件本身是 UTF-8，**不要"修"它**。
3. 交付前必须 `BUILD SUCCESSFUL` + 单测 `failures=0`；当前基线是 **13 个单测全过**（`EduCryptoTest` 4 + `IcsBuilderTest` 9）、**lint 0 errors / 19 warnings**。

## 3. 教务接口契约（别凭记忆改）

来自 `src/Network.cs` 取证并已在真机验证：

| 项 | 值 |
|---|---|
| BASE | `https://jwcydjw.gdlgxy.edu.cn` |
| 登录 | `POST /njwhd/login?userNo=<学号>&pwd=<加密口令>&encode=1` |
| 抓周 | `POST /njwhd/student/curriculum?week=<1..20>&kbjcmsid=<KBJCMSID>`，请求头带 `token` |
| 口令算法 | `base64(base64(AES-128-ECB(JSON.stringify(pwd), key="qzkj1kjghd=876&*")))` |
| UA | **必须沿用桌面 Chrome UA**，否则拿不到数据 |
| 结束时间字段 | **`endTIme`**（大写 I）；取不到再退 `endTime` —— 这个拼写错误是接口自带的 |

2026-09-29 真机实测：20 周抓取 → 15 周有课（第 1、14、15、19、20 周为空，是教务系统真实空周）、166 条课程、167 个日历事件。

## 4. 代码结构（`app/src/main/java/com/quack/curriculumexporter/`）

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 抓取入口、日志输出、`CrashReporter.install` |
| `ResultActivity.kt` | 课表预览、写入日历、导出/分享 .ICS |
| `EduClient.kt` | 登录 + 抓 20 周（`login` / `fetchWeek` / `fetchAll`） |
| `EduCrypto.kt` | 上面那套口令加密 |
| `Models.kt` | `Schedule` / `Course` / `WeekData` / `IcsEvent`（`Schedule.minWeek/maxWeek` 记的是**本次请求**的周范围，不是抓到的周 —— 空课表周不会出现在 `weeks` 里） |
| `CalendarWriter.kt` | **系统日历增量同步**（本轮改动集中在这里） |
| `TermWeeks.kt` | 从抓到的日期网格反推校历起点、算「今天第几周」、长按菜单的四个周范围（`ranges()` 是纯函数，有单测） |
| `IcsBuilder.kt` / `Exporter.kt` | .ICS 生成、保存到 `下载/课表导出/`、分享 |
| `SettingsStore.kt` / `SecureCredentials.kt` | 设置项、Keystore 加密保存密码 |
| `AppState.kt` | Activity 之间传抓取结果（**不用 Intent extra**） |
| `Sheet.kt` | 底部弹出面板：点一行只切选中态，按「完成」才回调（选范围、选日历共用） |
| `DialogBox.kt` | 自绘圆角对话框，替代各处 `AlertDialog` |
| `UiTheme.kt` / `Anim.kt` / `SystemBars.kt` / `SegmentedControl.kt` | 外观与交互。`UiTheme.apply()` 负责顶栏 / 状态栏着色 —— **顶栏色走 `@color/topbar`，不跟 accent**（见 5.8） |
| `CrashReporter.kt` | 崩溃兜底记录（下次启动弹出来，可复制） |

## 5. CalendarWriter 的同步设计（本轮重写过，务必先读这段）

### 5.1 数据流

- 抓到的课 → `IcsEvent` 列表
- `planSync(calendarId, events, now, allowDelete)` 比对「记录」与「日历现状」，算出最小改动 `SyncPlan{insert, update, delete, keep, past}`
- `applySync(...)` 按计划落地，返回新的「记录」
- 记录存 prefs `curriculum_import` 的 `last_events`（JSON 数组：`{id, uid, key, fp, start}`）

### 5.2 六条规则

1. `now` 之前的日程**一律不动** —— 不改写过去；
2. 认人优先 `uid`（课程实例 + 日期 + 节次），再 `courseKey`（课程实例 + 星期几）：换课改了节次也认得出是同一节课，走**更新**而不是删掉重加；
3. **只动自己写的事件**，用户在日历里手动加的日程永远不动；
4. 记录丢了（清过数据、装过旧版本）时，先凭**写进日历的自家标记**认领，再退一步用「课程名 + 开始时间」认领，认得出的走更新而不是新增；
5. 自家写的、记录里没留、这次也认不回来的**残留** → 列入删除（重复不会越堆越多）；
6. **换过目标日历**（在「选择要写入的日历」里挑了别的）时，别的日历里那份自家课表**也一并删除**，课表只留新日历那一份 —— 靠 `ownEventIdsOutside(calendarId)` 按包名找、按 `calendar_id != 目标` 过滤。不这么做的话，旧那份既不在记录里（记录只跟当前日历对齐）、也认不回来，就成了清不掉的幽灵 + 与新日历并存的两份重复。只按自家包名找，用户手动加进别的日历的日程不会被误伤。

> **第七条例外：`allowDelete = false` 时，上面所有删除规则（1 的过期清理、5 的残留、6 的搬家）全部跳过**，只增、只改。长按「获取课表」选了窄范围（近 3 / 近 5 周）时走的就是这条路 —— 这次只拿到一部分周的数据，范围外的课「没出现在这次结果里」不等于「教务系统里删了」，照常删会把它们清光。是否整学期由 `Schedule.minWeek/maxWeek` 判断，详见 5.6。

### 5.3 自家标记（v1.0.2 起）

系统日历允许第三方 App 给自己的事件打标记，`valuesOf()` 现在会写：

| 列 | 值 |
|---|---|
| `CalendarContract.Events.CUSTOM_APP_PACKAGE` | 本 App 包名（判断"这条是不是自己写的"） |
| `CalendarContract.Events.CUSTOM_APP_URI` | `curriculumexporter://event/<urlencode(uid)>`（记录丢了也能精确认领） |

- **别用 `SYNC_DATA*`**：代码注释已记录 —— Android 13 起只允许 sync adapter 写，虽然本机日历实测能写能读，但它不是给第三方 App 用的字段，换机就可能失效。
- 真机已实测 `customAppPackage` / `sync_data2` 可写可读回（`adb shell content update --bind`）。
- `adb shell content` 的 `--bind COLUMN:TYPE:VALUE` 按 `:` 切分，**值里不能有冒号**（`curriculumexporter://...` 会被判定 malformed），做实验时要用不含冒号的临时值。

### 5.4 这次的故障复盘（修的就是它）

设备上曾出现**日历里 167 条重复课程**：

| 证据 | 内容 |
|---|---|
| 日历 `calendar_id=6` | 316 条事件（`_id` 336..651），未来 298 = 149×2、过去 18 |
| `_id` 336..502（167 条，21:36 创建） | **不在导入记录里** → 永远不会被认领或删除（"幽灵"） |
| `_id` 503..651（149 条，21:45 创建） | 记录认得的那批 |
| 导入记录 | 316 条 = 167 条失效 legacy（`id` 168..334、uid 全空）+ 149 条新格式 |

成因：靠 `title|start` 的兜底认领**一次没能命中**（记录与日历脱节时），就再插一份；此后无法自愈。**规则 5 + 自家标记**就是为此加的。

### 5.5 选日历对话框必须自己画列表（第二批修复）

「启用系统日历选择」打开后弹出的那个选择框，**不能用 `AlertDialog.setItems()`**。

平台 Material 主题下（`AppThemeBase` 直接继承 `android:style/Theme.Material.Light.NoActionBar`，没有自定义 `alertDialogTheme`）`setItems()` 那份列表在真机上**完全不渲染**：对话框只剩标题、提示语和「取消 / 创建广理课表」两个按钮，一个日历都看不到 —— 用户的原话就是"这个设置没用"。

实测证据（2026-09-30）：`uiautomator dump` 整个对话框只有 16 个节点、没有任何 `ListView`/`AdapterView`；截图里提示语下面直接就是按钮面板，列表区域根本不存在。

改成 `setView(...)` + 逐行 `addView`（新增 `res/layout/dialog_calendar_picker.xml` 与 `res/layout/item_calendar_choice.xml`）后正常列出全部日历。`MainActivity` 里查看崩溃详情用的也是 `setView`，那个一直是好的 —— 说明平台 `AlertDialog` 本身没问题，问题只在 `setItems()` 这条路径上。

顺带修的：提示语原本写死"已隐藏生日、纪念日、倒计时等系统特殊日历"，`show_all_calendars=true` 时与实际不符，现在按设置切 `dlg_calendar_hint` / `dlg_calendar_hint_all`。

### 5.6 按周范围获取（第三批，v1.0.3）

整学期 20 个请求要十几秒，而日常更新只关心最近几周。**长按主界面「获取课表」**弹出范围菜单（`dialog_fetch_range.xml` / `item_fetch_range.xml`，同样走 `setView` + 逐行 `addView`）：

| 选项 | 周范围 |
|---|---|
| 全面重新获取 | 1 ~ 20（含已经上过的课） |
| 全面更新 | 本周 ~ 20 |
| 近 5 周 | 本周 ~ 本周+4（封顶 20） |
| 近 3 周 | 本周 ~ 本周+2（封顶 20） |

全部以「本周往后」算，已上过的课不重抓。**`EduClient.fetchAll(minWeek, maxWeek)` 永远串行，一周一个请求、不并发** —— 这是硬要求，提速只靠缩小范围，不给学校服务器添麻烦。

**「今天第几周」从哪来：`TermWeeks`。** 校历只有教务系统知道，猜不得：抓到的 `WeekData` 里带着该周的日期网格，取「周一那天」（`xqid == 1`）往前推 `(week - 1) * 7` 天 = 第 1 周周一，存进 prefs `curriculum_prefs` 的 `term_first_monday`；之后按天数差算周次（算出来超过 30 周视为校历过期，当不知道处理）。**第一次必须先完整获取一次**，否则菜单里后三项点了只提示「先获取一次课表」。

**窄范围写入必须 `allowDelete = false`**（见 5.1 的签名与 5.2 里的说明）：只抓着 3 周的数据去写日历，若照常删，范围外那些"这次没出现"的未来课程会被当成已取消而清光。是不是整学期由 `Schedule.minWeek/maxWeek` 判断 —— **不能看 `weeks`**：军训周、开学前后的空课表周本来就不在里面，实测会把整学期获取误判成"只抓了第 2~18 周"。

实测（2026-09-30，第 4 周）：菜单提示「现在是第 4 周」，「近 3 周」→ 「3 周 · 37 条课程」+「本次只获取了第 4~6 周」；写入前后 `calendar_id=1` 都是 **147 条**（不修的话只剩这 3 周的 37 条）。「全面更新」→ 「13 周 · 152 条课程」+「本次只获取了第 4~20 周」。

### 5.7 日历名显示成中文（v1.0.3）

系统预置日历的名字都是内部标识，摆给用户等于没法看（实测 `birthday@localhost · birthday@localhost`、`oplus_anniversary_default_calendar · 默认账户`）。

- `CalendarAccount.keywordName()`：按关键词把**纯 ASCII 的内部标识**换成中文（birthday→生日、anniversary→纪念日、countdown→倒计时、holiday→节假日、festival→节日、task/todo→待办、course→课表）。
- `CalendarAccount.accountLabel()`：增加 `*@localhost` → 本机。

**关键约束：名字里含非 ASCII 字符时一律不改写。** 否则用户自己起的「生日聚会」会被改写成「生日」、默认日历名「2026-2027-1 学期课表」会被改写成「课表」。

实测列表已变成：`广理课表 · 本机` / `生日 · 本机` / `生日 · 默认账户` / `纪念日 · 默认账户` / `倒计时 · 默认账户` / `本机日历`。

### 5.8 浮层重做 + 暗色模式修复（第四批，v1.0.4）

**用户诉求（原话要点）**：长按弹出的浮窗"很丑，很多浮窗没圆角，里面设计也丑"；要「长按后点一个选项，后面就默认是它，**而不是点进去就直接执行**」，布局「左边选项、右边圆形」，点击用强调色涂满表示已选，最下面加「完成」；顺带把全应用浮层统一美化。

**两个新组件**（四个旧布局 `dialog_fetch_range.xml` / `item_fetch_range.xml` / `dialog_calendar_picker.xml` / `item_calendar_choice.xml` 已删除）

- `Sheet.kt` + `res/layout/sheet.xml` + `res/layout/item_choice.xml`：底部弹出面板。`Dialog` + `Gravity.BOTTOM` + 透明 window 背景 + `setDimAmount(0.5f)`；贴底、只圆上两角（`bg_sheet`）、把手 + 标题 + 提示 + 可滚列表 + 「完成」+ 可选次要按钮。内容超过屏高 2/3 时内部滚动。范围菜单与日历选择共用。
- `DialogBox.kt` + `res/layout/dialog_message.xml`：自绘圆角对话框（标题 / 正文 / 可选自定义 body / 左取消右确定），宽度压到屏宽 88%。
- 两者都挂 `AppDialogTheme`（`values/themes.xml` 与 `values-night/themes.xml` 各一份，parent 只用平台公开的 `Theme.Material[.Light].Dialog.Alert`），配 `SheetAnim`（`anim/sheet_in.xml` / `sheet_out.xml`）。
- **别用平台 `<Button>`**：`Widget.Material.Button` 自带 `backgroundTint`，会把自定义圆角背景整个涂平。按钮一律 `<TextView>` + style。

**范围面板的交互（改动重点，务必保持）**

点一行**只切选中态**（`isSelected` 驱动右边的圆点 + 行底色），按「完成」**只把档位写进 prefs `fetch_range` 并刷新按钮下面那行说明，不开抓**；真正的开抓永远是再点一下「获取课表」。

| 操作 | 结果 |
|---|---|
| 单击「获取课表」 | 按记住的档位直接抓（周次未知 → 降级全面获取并在提示条里说明） |
| 长按「获取课表」 | 弹底部面板；点一行只选中，按「完成」只记默认值、面板关闭 |
| 抓取中长按 | 不弹面板（按钮此时是「取消获取」） |

周次未知时（`TermWeeks.today() == 0`）后三档**禁用**（整行压暗到 0.38，点了只 toast），只有「全面重新获取」可用。

> 上一版这里是「按完成即按该范围开抓」，用户明确不要这种"点进去就直接执行"，已改。

**暗色模式的三个坑（都修了）**

1. **`uiMode` 写在 `configChanges` 里 → 系统切深色时 Activity 不重建**，`values-night` 那套颜色根本不换，表现出来是「切了深色，界面几乎没变」（只有运行时涂的顶栏 / 状态栏跟着变，反而变成半生不熟的中间态）。修法：三个 Activity 都加 `onConfigurationChanged`，比对 `UI_MODE_NIGHT_MASK`，真变了就 `recreate()`。`MainActivity` 抓取中不能重建（会丢半截结果），先记 `pendingThemeRecreate`，等 `finishFetchingUi()` 收尾时再补。
2. **顶栏不该跟 accent 走**：暗色下 accent 是浅色（白胶囊 + 黑字），顶栏若也涂 accent，就成了横在深色界面顶上的一条白带。新增 `@color/topbar`（亮 `#111113` / 暗 `#1A1A1D`；**亮色后来在第六批按用户要求改成纯白，见 5.10**）专供顶栏与状态栏：`UiTheme.apply()`、两份 `themes.xml`、`SystemBars.install()` 全部改用它；`windowLightStatusBar` 两边都写 `false`（**这条在顶栏改白后不再适用** —— 现在状态栏图标明暗由 `UiTheme.apply()` 按顶栏底色现算）。顺带删掉了没人调用的 `UiTheme.accent()`。
3. **`values-night` 优先级高于 `values-v31`**，所以「暗色 + 动态色」必须在 `values-night-v31` 里单独给一次（`accent` / `on_accent` / `ripple_on_accent`）。

另外 `SettingsStore.dynamicTheme` 默认值由 `true` 改成 **`false`**：旧默认是跟随系统主题色，A12+ 会取壁纸色（实机上是紫蓝），与「纯黑白」的既定偏好冲突。要跟色的仍可在「高级设置 → 外观」里打开。

### 5.9 动效与交互重做（第五批，正式定为 v1.0.0）

**用户诉求（原话要点）**：「动画效果太弱了，各种按键反馈都很弱，页面功能交互设计的感觉有点糟糕，还有页面切换」，浮窗弹出的动画也要有，并明确授权自主设计（不用问）。

**一、按压反馈统一成 `Anim.press(view, pressed, haptic)`**

原先是 XML `stateListAnimator` + `button_press.xml`（只改 `scaleX/Y`），问题：没法做过冲回弹、带不了触感、每个按钮都得在布局里挂一次。现在全部删掉 XML 那套，改由 `Anim.press` 在运行时挂 `OnTouchListener`：

- 按下 120ms 压到 `pressed`（整行卡片 0.97 / 按钮 0.95 / 对话框按钮 0.94），抬手 300ms 用 `OvershootInterpolator(3.2f)` 弹回。
- `onTouch` 返回 **`false`**，把点击与长按交回 View 自己处理 —— 「获取课表」的长按菜单就是这么保住的。也正因为返回 false，**不要**在里面补 `performClick()`（会双触发），lint 的 `ClickableViewAccessibility` 已加 `@SuppressLint` 并注明理由。
- 触感用 `KEYBOARD_TAP`。

**二、页面转场**

`Anim.pageForward/pageBack` + `anim/page_in|page_out|page_in_back|page_out_back.xml`（横向推进：新页从右边 8% 推入、旧页往左退 6%），由 `MainActivity`/`ResultActivity`/`SettingsActivity` 里的 `overridePendingTransition` 调用。测试机是 API 33，没做 API 34 那种新的转场 API 分支。

**三、浮层动画全部自绘（弃用 window `windowAnimationStyle`）**

`sheet_in/out.xml`、`hold.xml` 已删除，`AppDialogTheme` 的 `windowAnimationStyle` 也删了。原因：window 动画是整窗（含遮罩）一起动，做不出「遮罩淡入 + 内容单独上滑」的层次，而且「遮罩」其实是 window 的 dim，想同步淡入淡出做不到。

现在 `sheet.xml` / `dialog_message.xml` 的外层是 `FrameLayout`：一层 `sheetScrim`/`dialogScrim`（`@color/scrim`）+ 一层内容面板；window 背景透明、`setDimAmount(0f)`。

- Sheet：`root.post` 里 panel 从自身高度滑入 300ms、scrim 220ms 淡入，然后行**错峰**入场（`100 + i*40` ms）。预置 `scrim.alpha=0f; panel.alpha=0f` 防第一帧闪一下。
- 行之间补了 1dp 分隔线（`@color/app_divider`，左右各缩进 12dp），并入入场动画列表（`entries`：行 + 线都要动，`rows` 只存可选行）。
- **下滑收起**：`attachDrag` 只绑在**把手 + 标题**上（`sheetHandle`/`sheetTitle`），不绑列表 —— 绑了就抢列表的滚动。拖过面板高度 28% 就 `close()`。
- `Anim.pulse(viewDot)`：选中行时圆点弹一下。
- DialogBox：`Anim.popIn(card, 0.9f)`（alpha + scale 0.9→1，`OvershootInterpolator`）；关闭时先播 card 的 alpha/scale(0.94) + scrim 淡出，`withEndAction` 里才 `dialog.dismiss()`。

**四、新增两个自绘组件**

- `ProgressLine.kt`：抓取进度条（轨道 + 一块 28% 宽的块 900ms 来回扫动），替掉了系统 `ProgressBar`（转圈那套在黑白界面里像半成品）。`onAttachedToWindow` / `onVisibilityChanged` 里启停 `ValueAnimator`，页面不可见时不空转。
- `Snack.kt`：底部浮出的提示条，替掉全部 `Toast`（结果页 13 处 `toast()` 已更名 `notice()`）。挂在 `android.R.id.content` 上、`@color/accent` 反相底 + `@color/on_accent` 字，淡入 240ms、2.6s 后淡出。设置页保存提示走 `AppState.pendingNotice`，回到主界面时由 `MainActivity.onResume` 消费（设置页 finish 得太快，Snack 挂在它身上会来不及看见）。

**五、交互设计改动**

| 位置 | 原来 | 现在 |
|---|---|---|
| 主界面改范围 | 只有长按「获取课表」能看到，靠一行灰字提示 | 按钮下面**可点的胶囊行**（`rangeRow` + `bg_range_row` + `ic_chevron_down`），点和长按都能开面板 |
| 主界面「高级设置」 | 一个小按钮 | 整行卡片（`bg_card_clickable` + `ic_chevron_right`） |
| 设置页每一行 | 只有右边 Switch 能点 | 整行可点（`SettingRow.Clickable` + `bg_row_touch`），点哪都切换 + 触感 |
| 设置页入场 | 对整块容器做动画 | `Anim.staggerChildren(settingsContent)` 逐行错峰（整块大容器做动画有 OOM 隐患，见 6.2） |
| 「分享 .ics」 | 必须先「保存 .ics」才可点（`savedUri != null` 才 enable） | 随时可点：没存过就自动先存一份再分享 |
| 分段控件 | 切段是硬切 | 滑块 280ms `OvershootInterpolator(1.4f)` 弹性；点已选中的段不再重复动画 |
| 结果页 | 切「按周/按课程」后停在原滚动位置 | `smoothScrollTo(0,0)` 回到顶部 |
| 写入中日历按钮 | 无反馈 | `Anim.breathe`（1→1.03 无限往返）提示"正在写" |

**六、暗色分段控件对比度（修上一批的遗留观察）**

`values` / `values-night` 各给一组：`segment_track`（亮 `#FFE6E6EA` / 暗 `#FF1C1C1F`）、`segment_thumb`（亮白 / 暗 `#FF3C3C43`）、`segment_thumb_line`（暗色下 1dp 描边，白滑块在深底上才不糊）。

**坑：`SegmentedControl` 的滑块 `elevation` 会把选中段的文字盖住。** `activity_result.xml` 里 `segModeThumb` 原本有 `android:elevation="2dp"`，滑块后添加、又带 elevation → 画在文字之上，选中段文字完全消失（截图放大才看出来）。删掉 elevation 即可（同 elevation 时按添加顺序，滑块在下、文字在上）。

**七、`UiTheme.apply` 不再涂顶栏背景色**

原来用 `ColorDrawable(topBarColor)` 覆盖 `topBar.background`，会把 `bg_topbar` 底部那条 1dp 分隔线一起抹掉。现在顶栏底色交给 `bg_topbar`（solid `@color/topbar` + 底部 1dp `@color/topbar_line`），`ColorDrawable` 那行删掉。

### 5.10 启动图标重做（第六批，正式版 1.0.0 的图标）

旧图标是「黑底 + 3×3 等大方白格」，语义像计算器、小尺寸糊成一团。用户自己画了一版几何图形，源稿留在 `android/design/icon-source.svg`（Illustrator 导出、701×701 画布；三块实心区块，区块之间的缝隙构成十字与斜带）。

落地方式：

- **画布**：`ic_launcher_foreground.xml` 的 viewport 直接照抄源稿的 `701`，配 `108dp` 的边长让系统自己缩放 —— path 数据原样搬运，不用手算坐标。
- **缩放 0.42 的来历**：系统只显示 108 画布**中心的 72dp**，而「绝不被任何遮罩裁掉」的安全区是中心**直径 66 的圆**。图形是正方形，要整个落进那个圆，边长最多 `66 / √2 ≈ 46.7` → `46.7 / 108 ≈ 0.43`，取 **0.42**（边长 45.4）。真机实测图形约占可见区的 63%，正是用户在预览里挑中的那个留白感。
  **别把预览图里的百分比直接抄成 `scale`** —— 预览量的是「占整个 108 画布」，真机看到的是「占中心 72」，两者差 1.5 倍。
- **描边那组 path 不要**：源稿 `<g>` 里的 `stroke` 元素（`polyline` / `line` / 两条 `c` 曲线）与三个实心 path 的边界完全重合，是 Illustrator 顺手导出的轮廓，留着只会糊边。
- **日夜反色**：颜色走 `@color/icon_background` / `@color/icon_foreground` 两个语义色 —— **亮色 = 白底 + 近黑图形，暗色整体对调**（用户 2026-10-01 看过真机后定的方向，`values` / `values-night` 里把两个色对调即可），因此只有一份 `ic_launcher.xml`。icon 色刻意不跟 `accent`，避免被系统动态色染成紫。
- **`<monochrome>` 层**：供 Android 13+ 主题图标使用，顺带消掉 `MonochromeLauncherIcon` 警告；它的 path 与缩放必须和前景完全一致（0.42），否则一开主题图标图形会突然变大。按 AOSP 的 `AdaptiveIconDrawable.inflate()` 实现，API 26–32 会跳过不认识的子标签，所以直接写在 `mipmap-anydpi-v26/ic_launcher.xml` 里是安全的。

**同一批还改了两处外观（用户看完真机提的）**：

- **亮色顶栏改纯白**：原来亮色顶栏是近黑（`#111113`），底下接着纯白卡片和浅灰背景，用户觉得「整体配色太冲突」。现在 `topbar` 亮色 = `#FFFFFFFF`、暗色 = `#1A1A1D`；`topbar_line` 亮色 = `#14000000`（浅灰细线，白顶栏下仍能看出分界）、暗色 = `#1AFFFFFF`。**只改了颜色资源**：顶栏标题/返回箭头的前景色和状态栏图标明暗都是 `UiTheme.apply()` 按顶栏底色现算的（`onAccent()` 亮度 > 0.58 → 黑字 → 顺带挂 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`），改白之后状态栏图标自动变黑。另外把 `TopBarTitle` 的静态 `android:textColor` 和 `ic_back.xml` 的 `fillColor` 从 `@color/on_accent` 换成 `@color/text_primary`，免得首帧（`apply()` 还没跑）出现白底白字。注意 `ic_check.xml` / `bg_button_primary.xml` / `button_text_primary.xml` 仍然要用 `on_accent`（按钮上永远是白胶囊黑字），别一起改。
- **应用名换成英文短名**：`@string/app_name` 从「广理课表导出」改成 **`GLTimetable`**（GL = 广东理工，Timetable 一眼看出是干什么的；短、纯英文、辨识度高）。桌面标签、系统应用信息页、主界面顶栏标题、`AndroidManifest` 的 `android:label` 全走这一个字符串，改一处即可。

## 6. 踩过的坑

### 6.1 设备交互类

- **`adb input tap` 偶发被 ColorOS 系统浮层吃掉（不是必然，别当 App bug）**：2026-09-29 结果页「写入系统日历」按钮位置被系统「跨屏互联」提示接管，同排 `保存 .ICS` / `分享 .ICS` 完全正常；2026-09-30 复测**同一坐标连续三轮全部成功**（全量写入 / 幂等 / 记录丢失自愈），可见是偶发覆盖。
  判据：点击后 **0.4 秒内截图**能看到系统 toast；按钮 `enabled`/文本/prefs 全无变化。
  处理顺序：先截图看有没有系统提示条 → 重试一次 → 仍无效就请用户用手点（手点实测立即生效）。
- **低电量弹窗抢焦点**：`mCurrentFocus=Window{... NotificationShade}`、`uiautomator dump` 只剩状态栏（`1%`）时，App 其实还在前台正常 `ResumedActivity`，是系统弹窗/通知栏盖住了。先处理电量与弹窗，再验 UI。
- `uiautomator dump` 的产物要 `adb pull` 回本地用完整路径读；`$env:TEMP` 每次命令都会换目录。
- 每次 adb 调用都会往 stderr 打 `daemon not running; starting now`，触发 `NativeCommandError`（命令其实成功）→ 统一 `$ErrorActionPreference='Continue'` + `2>&1`。同理，命令里出现 `exec: WaitDelay expired before I/O complete` 也是噪音（命令本身往往已经成功，看 `EXIT=` 与后续输出判断）。
- **`adb screencap` 出来整屏纯黑 ≠ App 崩溃**：2026-09-30 深夜遇到过，`dumpsys power` 显示 `mWakefulness=Asleep`（只是熄屏），`ResumedActivity` 仍是本应用、logcat 无异常。截图前先 `svc power stayon true` + `input keyevent 224` + `wm dismiss-keyguard`（ColorOS 的锁屏光 `keyevent 224` 只亮屏不解锁）；收工记得 `svc power stayon false`。
- **原地长按**：`input swipe <x> <y> <x> <y> 900`（起止点相同）。**不要**用带位移的竖向 swipe —— ColorOS 会当成"拉出通知栏"。
- **想验证「上次运行出错了」对话框**：`adb shell am crash com.quack.curriculumexporter` 就能造一次崩溃（堆栈是 `RemoteServiceException: shell-induced crash`，`CrashReporter` 会照常捕获），再 `am start` 主界面就会弹出来。点掉「关闭」后 `takeLast()` 消费掉记录，下次启动不再弹。
- **造场景可以手工注入 prefs**：`run-as <pkg> sh -c 'echo <base64> | base64 -d > shared_prefs/xxx.xml'`（值先在本机 `[Convert]::ToBase64String(...)` 编好，绕开 shell 引号与中文编码）。**写完必须 `am force-stop` 再启动**才读得到（`SharedPreferences` 有进程内缓存）。验证完记得清掉，别把伪造值留给用户（例如伪 `term_first_monday` 会让窄范围抓取算错周次）。

### 6.2 Android 实现类

- **`AlertDialog.setItems()` 在平台 Material 主题下不渲染**（本项目实测，见 5.5）：要列表就用 `setView` 自己画。
- **`EditText` 的 `setOnFocusChangeListener` 会在对话框关闭后自己触发**：提示条刚亮出来就消失，元凶是对话框 `dismiss()` 后输入框「重新获得焦点」→ 回调里的 `hideBanner()` 把 banner 收掉，**跟 banner 本身没关系**。改成 `setOnClickListener`（点了才收）。另外两处同类问题：`revealBar` 开头要 `view.animate().cancel()`（收起动画的 `withEndAction` 会把 `visibility` 设回 `GONE`，把刚显示的又抹掉）、`showBanner` 用 `banner.post {}` 推迟一帧。诊断这种「显示了又立刻没」，先怀疑**别处有人在改同一个 view**。
- **超大 `previewText` 不要做 alpha/translationY 动画** —— 会生成离屏 bitmap 导致 OOM 闪退。
- anim XML 的插值器写 `@android:interpolator/xxx`，不写完整类名。
- `windowLightNavigationBar` 只能放 `values-v27`。
- API 26–28 写公共 Download 需要 `WRITE_EXTERNAL_STORAGE`；API 29+ 走 MediaStore。
- Activity 间传结果用静态 `AppState`，**不要塞 Intent extra**。
- 颜色取用一律 `context.getColor(...)`；**但「强调色」不能直接读 `R.color.accent`** —— 它在 `values-v31` / `values-night-v31` 里指向系统动态色（A12+ 取壁纸色系，实机上是淡紫），而关掉动态色时界面走的是 `AppThemePlain` + `@color/plain_accent`（黑白）。直接读资源会绕开主题，于是纯黑白界面里冒出一条紫底提示条（v1.0.5 的 `Snack` 就这么中过一次）。要取"当前主题正在用的"强调色：`theme.resolveAttribute(android.R.attr.colorAccent, value, true)`，再 `getColor(value.resourceId)`；文字对比色用 `UiTheme.onAccent(color)` 现算，别读 `on_accent`。
- `SystemBars.install(this, root, topBar)` 负责 edge-to-edge 让位。
- 用户偏好：界面**纯黑白**、跟随系统亮/暗、**不加第三方依赖**、文案全中文、**不引入动画库**。
- 真机自动点时，先确认目标页在顶部再算坐标：`MainActivity` 的日志一长整页就能滚，滚到底后「高级设置」等按钮的**旧坐标会落到日志上**，表现为"点了没反应"（本轮为此白跑两轮）。可靠回顶：`am force-stop` + `am start`。**别用 `input swipe` 向下拖回顶** —— ColorOS 会把整屏中部的下滑手势当成"拉出通知栏"，通知栏一旦盖上，之后所有 `input tap` 全落在它上面；已经拉下来就用 `cmd statusbar collapse` 收掉。
- `SettingsActivity` / `ResultActivity` **不是 exported**，`am start -n .../.SettingsActivity` 会被静默拒绝（不报错也不进页面），进这两页只能从界面点。
- **`mipmap-anydpi-v26` 的 `-v26` 不能去掉**：想按 lint 的 `ObsoleteSdkInt` 建议把目录改名成 `mipmap-anydpi`（minSdk 已经是 26），实测**构建直接失败** —— `AAPT: error: resource mipmap/ic_launcher (aka com.quack.curriculumexporter:mipmap/ic_launcher) not found`，clean 后重试一样。留 `-v26`，这条警告当噪音。
- **换启动图标后，机器上的图标缓存会骗人**：`install -r`、`am force-stop com.android.launcher` 都不刷新（`com.android.launcher` 是这台 ColorOS 的桌面包名）。**判据同时看桌面图标和「应用信息」页的大图标**，别只看一个 —— 两者各有缓存，步调还不一致（本轮「应用信息」页在重装后先变了、桌面还是旧的）。只改图标**颜色**（path 不变）时缓存比换 path 更顽固：`install -r` + 重启设备后桌面仍可能是旧配色。**最彻底的办法是卸载重装**（`pm` 的图标缓存会重建）。任何时候怀疑"APK 没生效"，先用 `aapt2 dump xmltree --file res/drawable/ic_launcher_foreground.xml` 与 `aapt2 dump resources` 核对包内资源，再谈界面。
- **ColorOS 桌面不按 night 配置取 App 图标**：`ui_night_mode=2` + 重启设备后，桌面图标仍是亮色版（黑底白图），没有走 `values-night` 的反色。资源表本身是对的（`aapt2 dump resources` 能看到 `color/icon_foreground` 有 `()` 与 `(night)` 两份），换成会重载图标的 launcher 才看得到反色。**别花时间在这上面排查**。

## 7. 当前状态与验证结果（2026-09-30 ~ 10-01 真机通过）

两批修复，都在真机上验过。

**第一批（5.4 那个缺陷）**：`CalendarWriter.kt` 加自家标记 + 自家残留清理（规则 4/5）。

构建：`BUILD SUCCESSFUL`、13 个单测全过、lint **0 errors / 10 warnings**（原 19）。

真机验证（OPPO PDRM00 `5b311bcd`，`calendar_id=6`，2026-09-30 19:57–19:59，设备时间 `Wed Sep 30 19:58:24 CST 2026`）：

| 验证项 | 结果 |
| --- | --- |
| 全量写入 | 日历 **147 条**；导入记录 147 条（distinct 147、`_id` 652..798、uid/key/fp 全填充、0 条 legacy） |
| 自家标记落盘 | `customAppPackage` 147/147、`customAppUri` 147/147，例如 `curriculumexporter://event/202620271005957-20261001T0810%40jwcydjw.gdlgxy.edu.cn` |
| 规则 1（过去事件不写） | 最早事件 10-01 08:10；09-30 当天两节（08:10 思道法、10:20 高数）已过去 → 149 − 2 = **147** |
| 幂等 | 再点一次 → toast `日历已经是最新的：147 条没变化`，日历仍 147 条、记录里 `_id` 范围不变（全走 `keep`）。**判据要用 `_id` 范围 / 条数，别用记录文件 mtime** —— `saveRecord` 每次都 `put` + `apply`，值没变也会写盘，而 `apply` 是异步的，查得早就会看到旧 mtime、容易误判 |
| **记录丢失自愈** | `am force-stop` + `run-as ... rm -f shared_prefs/curriculum_import.xml` + 重新抓取并写入 → 日历**仍 147 条**、`_id` 仍 652..798，`event_create_time`=1790769468271(19:57:48) 而 `event_update_time`=1790769577716(19:59:37) → **原地 update 认领**，不是删旧插新 |

结论：5.4 里"316 条含 167 条幽灵"的病根已消除——正常路径幂等；最坏情况（导入记录丢失）也能凭 `customAppUri` 收敛，不再重复堆积。

**第二批（用户报的"启用系统日历选择 / 显示全部日历没用"，成因见 5.5）**：picker 改成自己画列表，并补上规则 6（换日历 = 搬家）。

构建：`BUILD SUCCESSFUL`、13 个单测全过、lint **0 errors / 11 warnings**（多出的 1 条是 `Overdraw`，来自新加的对话框布局，属噪音）。

真机验证（同日 20:29–20:31，`use_calendar_picker=true` / `show_all_calendars=true`）：

| 验证项 | 结果 |
| --- | --- |
| picker 列出日历 | 对话框列出全部 6 个：`广理课表 · 本机`、`birthday@localhost`、`oplus_anniversary_default_calendar`、`oplus_countdown_default_calendar`、`oplus_new_birthday_default_calendar`、`本机日历`；dump 节点数 16 → 23 |
| 提示语随设置变 | `showAll=true` 时显示"现在显示手机里的全部日历…"（新增 `dlg_calendar_hint_all`） |
| **换日历 = 搬家** | 选「本机日历」后：`calendar_id=1` **147 条**、`calendar_id=6` 由 147 → **0 条**、记录 `last_calendar_id=1` / `本机日历` → 没有两份并存 |
| 新日历上幂等 | 再点一次（仍选「本机日历」）→ toast `日历已经是最新的：147 条没变化`，`_id` 仍 799..945 |
| 关掉「显示全部日历」 | picker 只列 **2 个可写日历**（`广理课表 · 本机`、`本机日历`），只读的 `birthday` / `oplus_anniversary` / `oplus_countdown` / `oplus_new_birthday` 全被过滤掉；提示语同时切回 `dlg_calendar_hint`（"已隐藏生日、纪念日、倒计时等系统特殊日历…"）→ 两个设置各自都真正生效，不再出现"打开了也没反应" |

本轮收尾（与同步逻辑无关，同日完成）：

- `README.md` 三处"删除旧事件再写入"的措辞 → 改为增量同步（含 `customAppUri` 兜底的说明）
- 删死代码 `Anim.reveal`
- lint 19 → 10 warnings：
  - 删 4 条 `UnusedResources`（`color/plain_on_accent`、`dimen/space_xxl`、`dimen/radius_l`、`string/dlg_dup_keep`）。**坑**：`values-night/colors.xml` 里有同名项，base 删了而 night 没删会报 `MissingDefaultResource` **Error**（已一并删掉）
  - 3 条 `Autofill`：`userInput` / `passInput` / `calendarNameInput` 加 `android:importantForAutofill="no"`
  - 1 条 `ClickableViewAccessibility`（`Anim.press`）：加 `@SuppressLint` 并注释理由——`onTouch` 返回 `false` 时 View 自己会 `performClick`，再补调会双触发，所以刻意不调
- 有意保留、不打算修的警告（最新一轮 lint 后共 9 条）：`Overdraw`×3（纯白背景是用户明确偏好）、`GetInstance`（ECB 是教务接口要求）、`DataExtractionRules`、`UseCompoundDrawables`×2、`RedundantLabel`、`ObsoleteSdkInt`（`mipmap-anydpi-v26` 的 `-v26` 看着多余，**但实测不能去掉**，见 6.2）。`MonochromeLauncherIcon` 已在第六批补上 `<monochrome>` 层后消失。

冒烟回归（第一批的新包）：重装后重新抓取并写入 → 日历仍 147 条、记录里 `_id` 范围不变、toast `日历已经是最新的：147 条没变化`；说明删 `Anim.reveal` 与加 `importantForAutofill="no"` 都没带来副作用。

第二批的新包同样重装冒烟过（见上表"新日历上幂等"）。第二批新增两个布局文件：`res/layout/dialog_calendar_picker.xml`、`res/layout/item_calendar_choice.xml`。

**第三批（长按按范围获取 + 日历名中文化 + 提示条不显示，成因与设计见 5.6 / 5.7）**：`TermWeeks.kt`（新增，含 7 个单测）、`EduClient.fetchAll(minWeek, maxWeek)`、`Schedule.minWeek`、`CalendarWriter.planSync(..., allowDelete)` + `keywordName()`、`MainActivity.showRangeMenu()`、`Anim.revealBar` 的 `cancel()`。

构建：`BUILD SUCCESSFUL`、**20 个单测全过**（`EduCryptoTest` 4 + `IcsBuilderTest` 9 + `TermWeeksTest` 7）、lint **0 errors / 12 warnings**（多出的 2 条仍是新对话框布局的 `Overdraw`，属噪音）。

真机验证（同日 21:05–21:24，校历第 4 周，`use_calendar_picker=true` / `show_all_calendars=true`）：

| 验证项 | 结果 |
| --- | --- |
| 长按菜单（还没获取过课表） | 「选择获取范围」+「还没获取过课表。先跑一次「全面重新获取」…」+ 4 项 + 取消 |
| 校历推算 | 整学期获取后 `run-as` 读到 `term_first_monday = 2026-09-07`（第 1 周周一），由此算出第 4 周 |
| 长按菜单（已知周次） | 「现在是第 4 周。范围越窄越快（只抓本周往后的课，一周一个请求，不并发）。」 |
| 近 3 周 | 「3 周 · 37 条课程 · 38 个日历事件」+「本次只获取了第 4~6 周。…」 |
| 全面更新 | 「13 周 · 152 条课程 · 153 个日历事件」+「第 14、15、19、20 周没抓到」+「本次只获取了第 4~20 周。…」 |
| 整学期不再误报 | 「15 周 · 166 条课程 · 167 个日历事件」+「第 1、14、15、19、20 周没抓到」，**没有**"本次只获取" |
| **窄范围不误删** | 整学期写入「本机日历」后 `calendar_id=1` **147 条** → 再只抓「近 3 周」写进同一日历 → **仍是 147 条**（若照常删只剩这 3 周的 37 条） |
| 提示条能显示 | 没校历时点「近 3 周」→ 提示条「先获取一次课表，本应用才知道现在第几周」正常亮出并**留在界面上** |
| 日历名中文化 | picker 列出 `广理课表 · 本机` / `生日 · 本机` / `生日 · 默认账户` / `纪念日 · 默认账户` / `倒计时 · 默认账户` / `本机日历`（上一张表里那批 `birthday@localhost` 之类的英文名已不再出现） |

**第四批（浮层重做 + 暗色模式修复，设计见 5.8）**：新增 `Sheet.kt` / `DialogBox.kt` + `sheet.xml` / `item_choice.xml` / `dialog_message.xml` + `bg_sheet` / `bg_dialog` / `bg_choice_row` / `bg_radio` / `ic_radio_on|off` / `ic_check` / `bg_handle` + `anim/sheet_in|out.xml` + `@color/topbar`（两份 colors）+ `values-night-v31/colors.xml`；`MainActivity` 的长按菜单与 `ResultActivity` 的日历选择换 `Sheet`，8 处 `AlertDialog` 换 `DialogBox`；三个 Activity 补 `onConfigurationChanged` 处理深色切换；`UiTheme.apply()` / `SystemBars.install()` 改用 `topbar`；`SettingsStore.dynamicTheme` 默认 `false`。四个旧布局（`dialog_fetch_range` / `item_fetch_range` / `dialog_calendar_picker` / `item_calendar_choice`）已删除。）

构建：`BUILD SUCCESSFUL`、**20 个单测全过**（4 + 9 + 7）、lint **0 errors / 10 warnings**（全是既有噪音：`Overdraw`×3、`UseCompoundDrawables`×2、`GetInstance`、`RedundantLabel`、`DataExtractionRules`、`ObsoleteSdkInt`、`MonochromeLauncherIcon`）。

真机验证（同日 22:20–22:37，OPPO PDRM00，校历第 4 周，`versionName 1.0.3` 的开发包 + 用户真实账号）：

| 验证项 | 结果 |
| --- | --- |
| 纯黑白 | `pm clear` 后冷启动：顶栏/按钮黑、卡片纯白、背景浅灰，**没有紫蓝色**（`dynamicTheme` 默认关生效） |
| 长按弹面板 | 底部面板贴底、上两角圆角、把手 + 「选择获取范围」+ 左文字右圆点 + 「完成」；周次已知时四项全可用、默认选中「全面更新」 |
| 点一行只看选中 | 点「近 3 周」→ 圆点实心 + 对勾 + 行底淡色，其余保持空心；**没有任何抓取动作** |
| 按「完成」不开抓 | 面板关闭、按钮下面变成「本次获取：第 4 ~ 8 周 / 长按「获取课表」可调整范围」、prefs 落 `fetch_range=2`；**界面无提示条、无抓取**（旧版本这里会立刻按该范围开抓） |
| 周次未知时禁用 | 注入假 prefs 后冷启动：后三档整行压暗、点它只 toast |
| 切深色立即生效 | `cmd uimode night yes`（**不重启 App**）→ `onConfigurationChanged` 触发 `recreate()` → 背景近黑、卡片深灰、按钮转白胶囊，**顶栏是深灰底白字、没有白带** |
| DialogBox | `am crash` 造崩溃 → 重开弹「上次运行出错了」：圆角卡片、等宽堆栈可滚、左「关闭」描边胶囊 + 右「复制」白胶囊，暗色下配色正常 |
| 结果页（真实数据） | 抓「近 5 周」→「谢铭浩 · 26本科电子01班」「2026-2027-1 学期课表」「5 周 · 63 条课程 · 64 个日历事件」+「本次只获取了第 4~8 周…」；「按周看 / 按课程看」分段控件、`写入系统日历` 白胶囊 + `保存 .ics` / `分享 .ics` 描边胶囊 |
| 导出 | 「保存 .ics」→ toast `已保存到「下载/课表导出/广理课表-….ics」`（验证用的那份已从手机删掉） |
| 崩溃记录可消费 | 点「关闭」后 `am force-stop` + 重启 → 不再弹 |

> 上面那条「暗色下 `SegmentedControl` 对比度偏弱」的遗留观察已在第五批修掉（见下）。

**第五批（动效与交互重做，设计见 5.9）**：`Anim.kt` 重写（`press` / `stagger` / `popIn` / `pulse` / `breathe` / `pageForward|Back`）、新增 `ProgressLine.kt` 与 `Snack.kt`、`Sheet.kt` / `DialogBox.kt` 动画层重写（`sheet.xml` / `dialog_message.xml` 外层改 `FrameLayout`，自绘 scrim + 内容），`MainActivity` / `ResultActivity` / `SettingsActivity` / `SegmentedControl` / `UiTheme` 配套改动，布局与配色资源一批调整（新增 `bg_topbar` / `bg_range_row` / `bg_row_touch` / `ic_chevron_down` / `page_*.xml`；删除 `stateListAnimator` 那套、`sheet_in|out` / `hold` / `slide_in_up` / `slide_out_down` / `button_press`）。

构建：`BUILD SUCCESSFUL`、**20 个单测全过**（4 + 9 + 7）、lint **0 errors / 10 warnings**（与上一批同类，全是既有噪音）。

真机验证（同日 22:57–23:04，OPPO PDRM00，校历第 4 周；当时包上写的是 `versionName 1.0.5`，即现在的正式版 1.0.0）：

| 验证项 | 结果 |
| --- | --- |
| 分段控件选中段文字 | 删掉滑块 `elevation` 后「按周看」文字清晰可见（暗色、亮色都拍过）；切「按课程看」→ 滑块弹到右侧、内容改按课程分组，回顶部 |
| 面板分隔线 | 暗色面板四行之间 1dp 细线可见、左右缩进一致；选中行浅底 + 实心圆对勾 + 行底淡色 |
| 拖拽收起 | 从把手向下拖 350ms → 面板收起、scrim 同步淡出、主界面停在原位置 |
| 干净抓取 | 9 秒到结果页：「谢铭浩 · 26本科电子01班」「13 周 · 152 条课程 · 153 个日历事件」（未抓到 14/15/19/20 周，与前几批一致） |
| 提示条配色 | 「已保存到「下载/课表导出/…」」——修之前是**紫底**（`R.color.accent` 在 API 31+ 指向系统动态色），改成读主题 `colorAccent` 后是白底黑字，与按钮同色 |
| 设置页整行可点 | `uiautomator dump` 前后对比：点「显示全部日历」行左侧空白 → `showAllSwitch` `false` → `true`，其余 Switch 不动 |
| 保存提示链路 | 设置页「保存设置」→ 回主界面浮出「设置已保存」（`AppState.pendingNotice` → `onResume` 消费） |
| 权限对话框 | 重装后首次「写入系统日历」→ `DialogBox`「需要日历权限」（左描边「只导出文件」/ 右白胶囊「去授权」），圆角与遮罩正常 |
| 亮色模式 | 主界面 / 结果页 / 面板 全部纯黑白灰，没有彩色残留；版本号显示正确（当时是 1.0.5，正式版为 1.0.0） |

> 测试期间把「显示全部日历」开关来回拨过，收尾已恢复成默认关闭；设备已 `cmd uimode night no`、`svc power stayon false`，`/sdcard` 上的截图与 `ui*.xml`、本轮导出的 3 个 `.ics` 都已删除。

**第六批（启动图标重做 + 亮色白顶栏 + 英文应用名，设计见 5.10）**：`drawable/ic_launcher_foreground.xml` 换成用户手绘的几何图形、新增 `drawable/ic_launcher_monochrome.xml`、`mipmap-anydpi-v26/ic_launcher.xml` 补 `<monochrome>` 层；`values` / `values-night` 各加 `icon_background` / `icon_foreground` 两个语义色；源稿收进 `android/design/icon-source.svg`。同批按用户看真机后的反馈又动了三处：**图标配色对调**（亮 = 白底黑图形 / 暗 = 黑底白图形）、**亮色顶栏改纯白**（`topbar` + `topbar_line`，顺带把 `TopBarTitle` 与 `ic_back.xml` 的静态前景色从 `on_accent` 换成 `text_primary`）、**应用名改 `GLTimetable`**（`app_name`）。

构建：`BUILD SUCCESSFUL`、**20 个单测全过**（4 + 9 + 7）、lint **0 errors / 9 warnings**（`MonochromeLauncherIcon` 消失，其余 8 条同上）。

真机验证（2026-10-01 00:05–00:30，OPPO PDRM00）：

| 验证项 | 结果 |
| --- | --- |
| APK 里的资源 | `aapt2 dump xmltree --file res/drawable/ic_launcher_foreground.xml`：三条新 path + `<group scale=0.42>` 都在；`dump resources`：`icon_background` `() #ffffffff` / `(night) #ff111113`、`icon_foreground` 恰好反过来；`topbar` `() #ffffffff` / `(night) #ff1a1a1d` |
| 桌面图标（判据） | **白底 + 近黑几何图形**，缩在安全区内**没有被剪角**，图形约占可见区 63%（配色方向是用户看真机后定的） |
| 应用名铺开 | 桌面标签、系统「应用信息」页、主界面顶栏标题**都变成 `GLTimetable`** |
| 遮罩模拟 | 按真实映射（108 画布中心 72）离线渲染：圆形遮罩下四角也不越界；48px 下十字与斜带仍可辨 |
| 亮色顶栏 | 纯白底 + 黑字（`GLTimetable` 加粗）+ 底下一条浅灰 1dp 细线，**状态栏图标自动变黑**（`UiTheme.apply()` 按底色现算出来的） |
| 暗色顶栏 | 深灰底 + 白字，与前几批一致，没有出现"顶上一条白带" |
| 夜间反色 | 资源层正确，但 ColorOS 桌面重启后仍显示亮色版图标（原因见 6.2，不是本批的 bug） |

收尾：设备已 `cmd uimode night auto`、`svc power stayon false`、`am force-stop`；`/sdcard` 上本会话的截图全部删除（`Download/课表导出/` 里用户自己的 `.ics` 保留）。交付包 `curriculum-exporter-v1.0.0-android.apk` 已覆盖成这一版（1003818 B，SHA256 `1F033AC023BF6706155B357CA02E384942D49789D733C22C706444666001CFE9`）。

维护须知：

- 根目录交付包 `curriculum-exporter-v1.0.0-android.apk` 是**手工**从 `app/build/outputs/apk/debug/app-debug.apk` 复制的，`build-apk.ps1` 不会自动同步，改完代码记得覆盖。**开发期的 `curriculum-exporter-v1.0.1 ~ v1.0.5-android.apk` 都已删除** —— 根目录只留这一版正式包，避免装错。
- 仓库：`https://github.com/Quack2026/CurriculumExporter.git`（分支 `main`），安卓版在 `android/` 子目录。提交信息含中文用 `git commit -F <文件>`。
- **根 `README.md` 是仓库门面，主推安卓版**：顶部下载按钮直链指向 `releases/download/android-v1.0.0/curriculum-exporter-v1.0.0-android.apk`，Windows 版收在「Windows 版（备选）」小节里。安卓预览图在 `android/img/app-*.jpg`（`app-main` / `app-fetch-range` / `app-settings-light` / `app-settings-dark`），原来是微信导出的中文名，已改成英文。
- **发布按 tag 前缀分流**（`.github/workflows/build.yml` 的 `Decide release target` 一步）：tag 是 `android-*` → 只把 `android/curriculum-exporter-*-android.apk` 挂到 Release，跳过 Windows 编译；其他 tag → 现编 Windows exe 再挂，附 SHA256。所以**发安卓 Release 不用在本机跑任何东西**，在网页上 Draft a new release 选 `android-vX.Y.Z` 发布即可。
- **发新版安卓包要动三处**：`app/build.gradle.kts` 里的 `versionCode` / `versionName`；本地 `build-apk.ps1` 出包后覆盖成 `android/curriculum-exporter-<新版本>-android.apk`（**旧包删掉，只留这一份** —— CI 是用通配符找它的，留两份会挂错）；根 `README.md` 里的下载直链与 tag 名。
