package cn.edu.njust.kezaizhangxin

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Native update flow. DownloadManager owns downloads across Activity/process restarts. */
class AppUpdater(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val downloads = activity.getSystemService(DownloadManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var destroyed = false
    private var resumed = false
    private var checking = false
    private var verifying = false
    private var waitingPermission = false
    private var progressVisible = false
    private val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
    private val installedCode = code(installed)
    private val installedName = installed.versionName ?: installedCode.toString()
    private val poll = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            refreshDownload()
            if (prefs.getLong("download", -1) >= 0) handler.postDelayed(this, 1000)
        }
    }

    private fun directory(): File = File(
        activity.getExternalFilesDir(null) ?: error("更新存储目录不可用"), "updates"
    ).apply { check(isDirectory || mkdirs()) { "无法创建更新目录" } }
    private fun file(info: JSONObject) = File(directory(), "update-${info.getLong("versionCode")}.apk")
    private fun saved(): JSONObject = JSONObject(prefs.getString("metadata", "") ?: "")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    private fun ui(action: () -> Unit) = activity.runOnUiThread {
        if (!destroyed && !activity.isFinishing && !activity.isDestroyed) action()
    }
    private fun show(builder: AlertDialog.Builder): AlertDialog {
        dialog?.dismiss()
        return builder.create().also { dialog = it; it.show() }
    }
    private fun releasePage() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE))) }
            .onFailure { Toast.makeText(activity, "没有可用的浏览器", Toast.LENGTH_LONG).show() }
    }
    private fun showError(message: String, retry: (() -> Unit)? = null) {
        val builder = AlertDialog.Builder(activity).setTitle("更新未完成").setMessage(message)
            .setNegativeButton("关闭", null).setNeutralButton("打开发布页") { _, _ -> releasePage() }
        if (retry != null) builder.setPositiveButton("重试") { _, _ -> retry() }
        show(builder)
    }

    fun showMenu() {
        val hasTask = prefs.getLong("download", -1) >= 0 || !prefs.getString("ready", "").isNullOrEmpty()
        val items = if (hasTask) arrayOf("检查更新", "下载进度／安装更新", "版本发布页") else arrayOf("检查更新", "版本发布页")
        show(AlertDialog.Builder(activity).setTitle("课在掌心 v$installedName")
            .setItems(items) { _, which ->
                when (items[which]) {
                    "检查更新" -> checkUpdate()
                    "下载进度／安装更新" -> showTask()
                    else -> releasePage()
                }
            }.setNegativeButton("关闭", null))
    }

    private fun checkUpdate() {
        if (checking) return
        if (prefs.getLong("download", -1) >= 0) { showTask(); return }
        checking = true
        val checkingDialog = show(AlertDialog.Builder(activity).setTitle("检查更新")
            .setMessage("正在连接 GitHub…").setNegativeButton("稍后查看", null))
        executor.execute {
            val result = runCatching {
                val release = readJson(API)
                check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "暂无正式发布版本" }
                val tag = release.getString("tag_name")
                val assets = release.getJSONArray("assets")
                val all = (0 until assets.length()).map { assets.getJSONObject(it) }
                val manifest = all.singleOrNull { it.optString("name") == "update.json" }
                // Older releases predate update.json; a lower/equal public version is not an update.
                if (manifest == null) {
                    check(UpdatePolicy.compareVersions(tag, installedName) <= 0) { "新版本尚未提供完整更新信息，请稍后重试或打开发布页" }
                    null
                } else {
                    val manifestUrl = manifest.getString("browser_download_url")
                    check(UpdatePolicy.validAssetUrl(manifestUrl)) { "更新信息地址无效" }
                    val info = readJson(manifestUrl)
                    check(info.getString("versionName") == tag.removePrefix("v")) { "发布版本与更新信息不一致" }
                    val version = info.getLong("versionCode")
                    check(version > 0) { "版本信息无效" }
                    if (version <= installedCode) null else {
                        check(info.getInt("minSdk") <= Build.VERSION.SDK_INT) { "新版本需要更高版本的 Android，当前版本仍可使用" }
                        val apk = all.singleOrNull { it.optString("name") == info.getString("apk") }
                            ?: error("新版本安装包尚未上传完整")
                        val url = apk.getString("browser_download_url")
                        check(UpdatePolicy.validAssetUrl(url)) { "安装包地址无效" }
                        check(info.getLong("size") in 1..MAX_APK && info.getLong("size") == apk.getLong("size")) { "安装包大小信息不一致" }
                        check(UpdatePolicy.validHash(info.getString("sha256"))) { "安装包校验信息缺失" }
                        val digest = apk.optString("digest")
                        if (digest.startsWith("sha256:")) check(digest.removePrefix("sha256:").equals(info.getString("sha256"), true)) { "发布校验信息不一致" }
                        info.put("url", url).put("notes", release.optString("body").take(12000))
                            .put("published", release.optString("published_at").take(10))
                    }
                }
            }
            ui {
                checking = false
                checkingDialog.dismiss()
                result.fold(onSuccess = { info ->
                    if (info == null) show(AlertDialog.Builder(activity).setTitle("没有可用的新版本")
                        .setMessage("当前版本 v$installedName。\n当前已发布的正式版本不高于此版本。")
                        .setPositiveButton("知道了", null))
                    else offer(info)
                }, onFailure = { showError(networkError(it)) { checkUpdate() } })
            }
        }
    }

    private fun offer(info: JSONObject) {
        show(AlertDialog.Builder(activity).setTitle("发现新版本 v${info.getString("versionName")}")
            .setMessage("当前：v$installedName\n发布日期：${info.optString("published")}\n安装包：${size(info.getLong("size"))}\n\n${info.optString("notes")}")
            .setNegativeButton("暂不更新", null).setPositiveButton("下载更新") { _, _ ->
                val ready = prefs.getString("ready", "")
                if (ready == "update-${info.getLong("versionCode")}.apk") { showReady(); return@setPositiveButton }
                val network = activity.getSystemService(ConnectivityManager::class.java)
                if (network.isActiveNetworkMetered) show(AlertDialog.Builder(activity).setTitle("使用计费网络下载？")
                    .setMessage("当前可能使用移动流量，安装包大小 ${size(info.getLong("size"))}。")
                    .setNegativeButton("取消", null).setPositiveButton("继续下载") { _, _ -> startDownload(info) })
                else startDownload(info)
            })
    }

    private fun startDownload(info: JSONObject) {
        if (prefs.getLong("download", -1) >= 0) { showTask(); return }
        runCatching {
            val root = directory()
            check(root.usableSpace >= info.getLong("size") * 2 + 20 * 1024 * 1024) { "可用空间不足，请清理存储后重试" }
            val target = file(info)
            check(!target.exists() || target.delete()) { "无法清理未完成的下载" }
            val request = DownloadManager.Request(Uri.parse(info.getString("url")))
                .setTitle("课在掌心 v${info.getString("versionName")}")
                .setDescription("更新安装包，完成后请返回应用确认安装")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(target))
            // Save destination before enqueue, so an interrupted process cannot lose the file identity.
            prefs.edit().putString("metadata", info.toString()).remove("ready").commit()
            val id = downloads.enqueue(request)
            prefs.edit().putLong("download", id).commit()
            showTask()
            handler.removeCallbacks(poll); handler.post(poll)
        }.onFailure { showError(networkError(it)) { startDownload(info) } }
    }

    private fun showTask() {
        if (!prefs.getString("ready", "").isNullOrEmpty()) { showReady(); return }
        if (prefs.getLong("download", -1) < 0) { checkUpdate(); return }
        progressVisible = true
        show(AlertDialog.Builder(activity).setTitle("下载更新").setMessage("正在读取下载进度…")
            .setNegativeButton("后台下载", null).setNeutralButton("取消下载") { _, _ -> cancelDownload() })
            .setOnDismissListener { progressVisible = false }
        refreshDownload()
    }

    private fun cancelDownload(cleanCompleted: Boolean = false) {
        val id = prefs.getLong("download", -1)
        if (id >= 0) runCatching { downloads.remove(id) }
        val completed = prefs.getLong("completedDownload", -1)
        if (cleanCompleted && completed >= 0) runCatching { downloads.remove(completed) }
        runCatching { file(saved()).delete() }
        prefs.edit().remove("download").remove("ready").remove("metadata").apply {
            if (cleanCompleted) remove("completedDownload")
        }.apply()
        handler.removeCallbacks(poll)
    }

    private fun refreshDownload() {
        val id = prefs.getLong("download", -1)
        if (id < 0 || verifying) return
        runCatching {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor != null && cursor.moveToFirst()) { "下载任务已被系统移除，请重新下载" }
                fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> verifySaved(id)
                    DownloadManager.STATUS_FAILED -> {
                        val reason = number(DownloadManager.COLUMN_REASON).toInt()
                        error(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "空间不足，下载失败" else "下载失败，请检查网络后重试")
                    }
                    else -> if (progressVisible) {
                        val downloaded = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val prefix = if (number(DownloadManager.COLUMN_STATUS).toInt() == DownloadManager.STATUS_PAUSED) "等待网络恢复…\n" else ""
                        dialog?.setMessage(prefix + if (total > 0) "${downloaded * 100 / total}% · ${size(downloaded)} / ${size(total)}" else "已下载 ${size(downloaded)}")
                    }
                }
            }
        }.onFailure {
            val info = runCatching { saved() }.getOrNull()
            cancelDownload()
            showError(networkError(it), info?.let { metadata -> { startDownload(metadata) } })
        }
    }

    private fun verifySaved(downloadId: Long? = null, install: Boolean = false) {
        if (verifying) return
        val info = runCatching { saved() }.getOrElse { showError("更新记录丢失，请重新检查更新") { checkUpdate() }; return }
        verifying = true
        val verifyingDialog = if (install) show(AlertDialog.Builder(activity).setTitle("校验安装包").setMessage("正在检查完整性和签名…")) else null
        if (progressVisible) dialog?.setMessage("下载完成，正在校验安装包…")
        executor.execute {
            val result = runCatching { verify(file(info), info) }
            ui {
                verifying = false
                verifyingDialog?.dismiss()
                if (downloadId != null && prefs.getLong("download", -1) != downloadId) return@ui
                result.fold(onSuccess = {
                    val previous = prefs.getLong("completedDownload", -1)
                    if (downloadId != null && previous >= 0 && previous != downloadId) runCatching { downloads.remove(previous) }
                    prefs.edit().remove("download").putString("ready", file(info).name).apply {
                        if (downloadId != null) putLong("completedDownload", downloadId)
                    }.commit()
                    // Only updater-owned APKs are touched; never uninstall the app or delete user data.
                    directory().listFiles()?.filter { it.name.matches(Regex("update-\\d+\\.apk")) && it != file(info) }
                        ?.forEach { it.delete() }
                    if (install) installReady() else showReady()
                }, onFailure = {
                    cancelDownload()
                    showError(networkError(it)) { checkUpdate() }
                })
            }
        }
    }

    private fun verify(apk: File, info: JSONObject) {
        check(apk.isFile && apk.length() == info.getLong("size")) { "安装包不完整，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) }.equals(info.getString("sha256"), true)) { "安装包校验失败，请重新下载" }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pkg = activity.packageManager.getPackageArchiveInfo(apk.path, flags) ?: error("安装包无法识别")
        check(pkg.packageName == activity.packageName) { "安装包不属于课在掌心" }
        check(code(pkg) == info.getLong("versionCode") && code(pkg) > installedCode && pkg.versionName == info.getString("versionName")) { "安装包版本不匹配或不是新版本" }
        check((pkg.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "当前 Android 版本不支持此更新" }
        val current = activity.packageManager.getPackageInfo(activity.packageName, flags)
        fun signatures(p: PackageInfo): Set<String> {
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return signatures?.map { it.toCharsString() }?.toSet() ?: emptySet()
        }
        check(signatures(pkg).isNotEmpty() && signatures(pkg) == signatures(current)) { "安装包签名不匹配，不能覆盖升级。请勿卸载当前应用" }
    }

    private fun showReady() {
        val info = runCatching { saved() }.getOrElse { showError("更新记录无效，请重新检查更新"); return }
        if (!runCatching { file(info).isFile }.getOrDefault(false)) {
            prefs.edit().remove("ready").apply()
            showError("已下载的安装包不存在，请重新下载") { startDownload(info) }; return
        }
        show(AlertDialog.Builder(activity).setTitle("更新已下载 v${info.getString("versionName")}")
            .setMessage("安装将覆盖旧版本并保留课表和笔记。系统会请你确认安装。")
            .setNegativeButton("稍后安装", null).setPositiveButton("立即安装") { _, _ -> verifySaved(install = true) })
    }

    private fun installReady() {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show(AlertDialog.Builder(activity).setTitle("需要安装授权")
                .setMessage("请在系统设置中允许“课在掌心”安装应用。返回后可继续安装；取消授权不影响当前版本。")
                .setNegativeButton("取消", null).setPositiveButton("前往设置") { _, _ ->
                    runCatching {
                        waitingPermission = true
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }.onFailure { waitingPermission = false; showError("无法打开安装授权设置，请在系统设置中手动授权") }
                })
            return
        }
        runCatching {
            val uri = Uri.Builder().scheme("content").authority("${activity.packageName}.updates")
                .appendPath(prefs.getString("ready", "")!!).build()
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("update", uri)
            activity.startActivity(intent)
        }.onFailure { showError("无法启动系统安装器，安装包已保留，可稍后重试") }
    }

    fun resume() {
        resumed = true
        // Successful replacement is confirmed by installed version, not by leaving the installer.
        runCatching {
            val expected = runCatching { saved().getLong("versionCode") }.getOrDefault(Long.MAX_VALUE)
            if (expected <= installedCode) {
                cancelDownload(cleanCompleted = true)
                directory().listFiles()?.filter { it.name.matches(Regex("update-\\d+\\.apk")) &&
                    it.name.removePrefix("update-").removeSuffix(".apk").toLong() <= installedCode }?.forEach { it.delete() }
                Toast.makeText(activity, "已更新至 v$installedName，安装包已清理", Toast.LENGTH_LONG).show()
            }
        }
        if (waitingPermission) {
            waitingPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) showReady()
            else showError("尚未允许安装应用，当前版本仍可正常使用")
        }
        handler.removeCallbacks(poll); handler.post(poll)
    }
    fun pause() { resumed = false; handler.removeCallbacks(poll) }
    fun close() { destroyed = true; pause(); dialog?.dismiss(); executor.shutdownNow() }

    private fun readJson(address: String): JSONObject {
        var url = URL(address)
        repeat(6) {
            check(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                (url.host == "api.github.com" || url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) { "更新地址不受信任" }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 12000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "KeZaiZhangXin/$installedName")
            try {
                when (val status = connection.responseCode) {
                    in 300..399 -> { url = URL(url, connection.getHeaderField("Location") ?: error("下载重定向无效")) }
                    200 -> {
                        val bytes = connection.inputStream.use { it.readBytesLimited(1024 * 1024) }
                        return JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                    403, 429 -> error("GitHub 请求受限，请稍后再试")
                    404 -> error("暂未找到完整的正式版本信息，请稍后重试")
                    else -> error("更新服务器暂不可用（HTTP $status）")
                }
            } finally { connection.disconnect() }
        }
        error("更新地址重定向次数过多")
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            check(output.size() + n <= limit) { "更新信息过大" }; output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    private fun networkError(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
        is java.io.IOException -> "连接或文件读取失败，请检查网络与可用存储后重试"
        is org.json.JSONException, is IllegalArgumentException -> "更新信息格式无效，请稍后重试或打开发布页"
        else -> error.message ?: "更新失败，请稍后重试"
    }
    private fun size(bytes: Long) = String.format(java.util.Locale.US, "%.2f MB", bytes / 1048576.0)
    companion object {
        private const val RELEASE_PAGE = "https://github.com/Phynix2025/NJUST_master_school_timetable/releases/latest"
        private const val API = "https://api.github.com/repos/Phynix2025/NJUST_master_school_timetable/releases/latest"
        private const val MAX_APK = 512L * 1024 * 1024
    }
}
