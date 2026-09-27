# 开发与发布

## 环境

- Android Studio；使用其中的 JBR（支持 Java 17 工具链）。
- Android SDK Platform 36、Build Tools 36.1.0。
- 项目自带 Gradle Wrapper，无需独立安装 Gradle。
- 浏览器测试需要 Node.js 20 或更新版本，以及 npm。

由 Android Studio 配置本机 SDK 路径（`local.properties`，不提交）。命令行构建时将 `JAVA_HOME` 指向 Android Studio 的 `jbr` 目录；若 Gradle 缓存路径解析异常，将 `GRADLE_USER_HOME` 设置为用户目录下的 `.gradle`。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

APK 默认输出至 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。

## 浏览器回归测试

```powershell
npm ci
npx playwright install chromium
npm test
```

也可以复用已安装的 Chrome：

```powershell
$env:PLAYWRIGHT_CHANNEL = 'chrome'
npm test
```

测试使用虚构课表和模拟 Android 桥接，不读取真实学校账号或本机课表。覆盖课程位置与状态、翻周、笔记保存、跨月、窄屏、旧课表日期锚点、冲突课程、详情返回滚动位置和地图入口。示例截图输出至 `dist/week-timetable-preview.png`。

浏览器测试不验证 Android 通知、系统图片授权、真实定位及系统窗口边距。修改这些功能后，需要在设备上验证；至少检查状态栏／挖孔、底部导航、键盘输入、课前和明日提醒，以及升级后原有笔记能否读取。

## 数据与界面

- `app/src/main/assets/index.html`：页面和弹层结构。
- `app/src/main/assets/styles.css`：课表、详情、地图样式。
- `app/src/main/assets/app.js`：周次与课程解析、交互、笔记和地图逻辑。
- `MainActivity.kt`：WebView 桥接、同步、加密凭据、图片引用、窗口边距和定位。
- `ScheduleNotificationReceiver.kt`：通知调度与接收。

课程详情按课程名称与教师生成标识；修改此规则需要考虑既有笔记迁移。网页通过 `Android` 桥接访问本地数据，不能把登录凭据注入页面或提交到仓库。

## 地图

`map-source/` 保留底图、建筑数据与校准配置；应用使用的离线地图位于 `app/src/main/assets/map/`。源数据修改后，在 Windows PowerShell 中运行：

```powershell
.\tools\generate-map-tiles.ps1
```

生成的地图文件需要随源码一起提交，确保克隆后可直接构建。

## 发布流程

1. 更新 `app/build.gradle.kts` 的 `versionName` 和递增的 `versionCode`，同步 `package.json`、README 与 CHANGELOG。
2. 执行浏览器测试、Debug 和 Release 构建；按改动范围完成设备验证。
3. 将 Release APK 复制到 `dist/KeZaiZhangXin-v<版本>.apk`，生成同名 `.sha256` 校验文件。
4. 提交源码和文档；推送提交及 `v<版本>` 标签。
5. 创建 GitHub Release，上传 APK 和 SHA-256 文件，并附上对应更新记录。

当前 Release 构建沿用本机开发签名。覆盖安装必须使用与旧版相同的签名；换电脑或重建签名可能导致无法覆盖安装。不要通过卸载应用解决签名问题，否则本地课表和笔记可能丢失。签名文件不得提交到 GitHub。

`dist/`、Gradle／Kotlin 缓存、`app/build/` 和 `node_modules/` 均为本机生成物，不提交。历史 APK 保存在 GitHub Releases；源码目录不再维护 `release/`。
