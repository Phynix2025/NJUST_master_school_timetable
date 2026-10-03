# 课在掌心

面向南京理工大学研究生的本地 Android 课程表应用。同步数据来自学校研究生管理系统，本项目不是学校官方应用，一切课程信息以学校官网为准。

## 下载

[下载 v2.2.0 APK](https://github.com/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/KeZaiZhangXin-v2.2.0.apk)

当前发布包使用开发签名，适合个人和同学间测试，不用于应用商店发布。最低支持 Android 8.0。

## 核心功能

- 顶部更多菜单支持手动检查更新，用户确认后下载、校验并调用系统安装器；覆盖升级保留数据并清理应用管理的更新文件。
- 七列周课表，左右滑动查看上／下周，支持选择周次和回到本周。
- 课程按实际节次定位，连堂跨行显示；已完成课程变灰，进行中课程高亮。
- 卡片显示课程名和地点，点击查看教师、完整课程信息、笔记和图片；地图入口位于顶部。
- 课程开始前 20 分钟提醒，晚间提示次日最早课程。
- 课程详情支持自动保存笔记和多张图片；图片可全屏查看、缩放和拖动。
- 内置离线校园地图，支持建筑搜索、定位、精度范围、目标标记和当前朝向。
- 系统重启、应用更新或时间变化后自动重建课程提醒。

## 隐私

- 课表、笔记和登录凭据仅保存在本机。
- 密码使用 Android Keystore AES-GCM 加密。
- 课程图片只保存系统授予的原文件引用，不复制图片。
- 定位仅在校园地图打开时使用，不保存轨迹、不申请后台定位。
- 验证码始终由用户手动输入，不自动识别或绕过。

## 项目结构

```text
app/                  Android 应用（Kotlin、WebView、HTML/CSS/JavaScript）
  src/main/assets/    课表页面与离线地图
  src/main/java/      原生桥接、同步、通知和定位
map-source/           地图源数据与校准配置
tools/                地图生成工具
tests/                浏览器回归测试（虚构数据）
docs/                 开发和发布说明
gradle/               Gradle Wrapper
```

本机生成的 APK、校验文件和测试截图统一放在 `dist/`，不提交到源码仓库；安装包通过 GitHub Releases 分发。

## 开发

最低支持 Android 8.0（API 26），编译目标为 API 36，包名为 `cn.edu.njust.kezaizhangxin`。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

环境配置、测试、地图更新及发布步骤见 [开发文档](docs/DEVELOPMENT.md)。本次变更见 [更新记录](CHANGELOG.md)。

请勿把账号、密码、Cookie、验证码、私钥、签名文件或访问令牌提交到仓库。

## License

项目暂未声明开源许可证，默认保留所有权利。
