# 课在掌心

一个面向南京理工大学研究生的本地 Android 课程表应用。应用通过隐藏的 WebView 登录学校研究生管理系统，在用户完成学校要求的验证码后同步课表，并在本地提供“今天 / 明天 / 本周”视图和课程提醒。

> 一切数据以学校官网为准。本项目不是南京理工大学官方应用。

## 功能

- 后台访问 `gsmis.njust.edu.cn`，同步“课务管理 → 学期课表信息查询”中的课程。
- 从学校教学日历读取学期第 1 周周一，按日期过滤实际上课周次。
- 展示下一节课、上课时间、节次、地点、教师和周次。
- 支持左右滑动切换“今天 / 明天 / 本周”。
- 本周课程按“进行中、未开始、已完成”排列，已完成课程置底并弱化。
- 周一至周四 22:00 提醒次日最早课程；无课时发送休息提示。
- 每节课开始前 20 分钟发送课程提醒。
- Android 13 及以上版本运行时请求通知权限；无法使用精确闹钟时自动降级。
- 设备重启后重新建立提醒。

## 隐私与安全

- 课表和登录凭据仅保存在设备本地，不上传到第三方服务器。
- 账号和密码使用 Android Keystore 中的 AES-GCM 密钥加密后保存。
- 验证码不会被自动识别、破解或绕过。学校要求验证码时，应用只展示原始验证码，由用户手动输入。
- 学校站点登录后的 HTTP 跳转例外仅限 `gsmis.njust.edu.cn` 域名；应用其余位置不允许明文流量。
- 请勿把真实账号、密码、Cookie、私钥、签名文件或访问令牌写入源码、测试数据、日志或 Git 历史。

## 技术结构

项目使用 Kotlin 和原生 Android WebView，最低支持 Android 8.0（API 26）。首页为随 APK 打包的 HTML/CSS/JavaScript，不依赖远程前端资源。

```text
app/src/main/
├── AndroidManifest.xml
├── assets/index.html
├── java/cn/edu/njust/kezaizhangxin/
│   ├── MainActivity.kt
│   └── ScheduleNotificationReceiver.kt
└── res/
    ├── drawable-nodpi/ic_launcher.png
    ├── values/styles.xml
    └── xml/network_security_config.xml
```

- `MainActivity.kt`：创建首页与学校 WebView，处理登录、验证码、课表抓取、本地存储和 JavaScript 桥接。
- `index.html`：课程解析、周次过滤、页面渲染、交互与登录/验证码弹窗。
- `ScheduleNotificationReceiver.kt`：解析本地课表、安排闹钟、处理开机重建和发送通知。
- `network_security_config.xml`：限制学校站点所需的明文流量例外。

## 同步流程

```text
首页发起同步
  → 后台 WebView 打开学校系统
  → 填入本地解密的账号和密码
  → 用户手动输入学校验证码
  → 登录后进入学期课表查询
  → 解析课程和教学日历
  → 保存本地 JSON
  → 重建课程通知并刷新首页
```

学校页面的 DOM、控件 ID 或登录流程发生变化时，优先检查 `MainActivity.kt` 中的选择器与跳转逻辑。修改同步逻辑时必须保留“验证码由用户手动完成”的安全边界。

## 开发环境

- Android Studio 自带 JBR（Java 17）
- Android Gradle Plugin 8.10.1
- Kotlin 2.0.21
- Gradle 8.11.1
- `compileSdk` / `targetSdk`：36
- `minSdk`：26
- 包名：`cn.edu.njust.kezaizhangxin`
- 当前版本：`1.0.0`（`versionCode 2`）

本机需要在未提交的 `local.properties` 中配置 Android SDK，例如：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

## 构建

PowerShell 下构建 debug APK：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\phynix\.gradle'
.\gradlew.bat :app:assembleDebug --no-daemon --offline --console=plain
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前离线缓存缺少 Android Lint 工件，release 构建需要跳过 lint 链：

```powershell
.\gradlew.bat :app:assembleRelease `
  -x :app:lintVitalAnalyzeRelease `
  -x :app:lintVitalReportRelease `
  -x :app:lintVitalRelease `
  --no-daemon --offline --console=plain
```

输出文件：

```text
app/build/outputs/apk/release/app-release.apk
```

当前 release 变体使用本机 debug keystore 签名，仅适合开发测试和直接安装。发布到应用商店前，必须配置独立的生产签名，并通过安全的本机配置或 CI Secret 注入签名信息，不能提交 keystore 或密码。

## 安装与调试

模拟器或设备在线后可执行：

```powershell
C:\Users\phynix\AppData\Local\Android\Sdk\platform-tools\adb.exe `
  -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
```

调试同步功能需要能够访问学校系统。不要在测试截图、日志或问题报告中暴露账号、验证码、Cookie 或个人课表信息。

## 扩展指南

### 修改首页

页面样式和渲染逻辑集中在 `app/src/main/assets/index.html`。原生能力通过 `Android` JavaScript 接口提供。增加新的桥接方法时：

1. 只暴露完成具体任务所需的最小接口。
2. 对来自网页的字符串做校验，不信任页面输入。
3. 涉及 WebView 或界面操作时切换到主线程。
4. 不把解密后的登录凭据返回给首页 JavaScript。

### 适配学校系统变化

课表、登录页和教学日历依赖学校网页结构。适配新结构时，应同时验证：

- 登录控件、验证码图片和提交按钮选择器。
- 登录成功后的目标页面跳转。
- 课表合并单元格、课程文本、教师、地点和周次解析。
- 教学日历的学期名称、日期格式及第 1 周周一。
- 同步失败时能回到应用首页并给出可理解的提示。

### 修改通知

通知时间和课表过滤位于 `ScheduleNotificationReceiver.kt`。变更时注意 PendingIntent ID 唯一性、夏令时/时区、系统重启、精确闹钟权限以及 Android 13+ 通知权限。课程解析规则应与首页保持一致，避免页面显示有课但通知缺失。

## 提交前检查

1. 构建 debug APK。
2. 有设备时安装并验证首页、同步、验证码和滑动交互。
3. 检查当天/跨周/学期开始前后的周次计算。
4. 检查课程开始前提醒和晚间提醒。
5. 使用 `git diff --check` 检查格式，并确认没有敏感信息或本机文件进入提交。

## License

项目目前未声明开源许可证。在添加许可证前，默认保留所有权利；如计划接受外部贡献或发布衍生版本，请先由维护者选择并加入合适的许可证。
