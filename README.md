# 课在掌心

面向南京理工大学研究生的本地 Android 课程表应用。同步数据来自学校研究生管理系统，本项目不是学校官方应用，一切课程信息以学校官网为准。

## 下载

[下载 v2.0.0 APK](https://github.com/Phynix2025/NJUST_master_school_timetable/releases/download/v2.0.0/KeZaiZhangXin-v2.0.0.apk)

当前发布包使用开发签名，适合个人和同学间测试，不用于应用商店发布。最低支持 Android 8.0。

## 核心功能

- 查看今天、明天、本周课程及下一节课。
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

## 构建

项目使用 Kotlin、原生 Android WebView 和本地 HTML/CSS/JavaScript，包名为 `cn.edu.njust.kezaizhangxin`。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

地图源数据位于 `map-source/`，修改后运行：

```powershell
.\tools\generate-map-tiles.ps1
```

请勿把账号、密码、Cookie、验证码、私钥、签名文件或访问令牌提交到仓库。

## License

项目暂未声明开源许可证，默认保留所有权利。
