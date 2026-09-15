package cn.edu.njust.kezaizhangxin

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var schoolWebView: WebView
    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }
    private val credentialPrefs by lazy { getSharedPreferences("credentials", MODE_PRIVATE) }
    private var pendingCredentials: Credentials? = null
    private var captchaImage: String = ""

    private data class Credentials(val username: String, val password: String)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schoolWebView = createWebView()
        webView = createWebView()
        setContentView(android.widget.FrameLayout(this).apply {
            // The school WebView is kept behind the home screen. It can finish a
            // valid session refresh without exposing the school's intermediate UI.
            addView(schoolWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        webView.loadUrl("file:///android_asset/index.html")
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        NotificationScheduler.schedule(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " KeZaiZhangXin/0.1"
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = SchoolWebViewClient()
        }

    private fun beginSync() {
        val credentials = pendingCredentials ?: loadCredentials()
        if (credentials == null) {
            webView.loadUrl("file:///android_asset/index.html?sync=login-required")
            return
        }
        webView.visibility = View.VISIBLE
        webView.loadUrl("file:///android_asset/index.html?sync=loading")
        schoolWebView.loadUrl("https://gsmis.njust.edu.cn/")
    }

    private fun extractSchedule(view: WebView) {
        // This runs only inside the logged-in Njust WebView. It reads the course table;
        // account credentials are never passed to JavaScript or saved by the app.
        val script = """
            (function () {
              function tableFrom(doc) { return doc && doc.querySelector('#ctl00_contentParent_dgData'); }
              function allDocs(doc) {
                var docs = [doc];
                Array.from(doc.querySelectorAll('iframe,frame')).forEach(function (f) {
                  try { docs = docs.concat(allDocs(f.contentDocument)); } catch (_) {}
                }); return docs;
              }
              var docs = allDocs(document), table = null;
              for (var i = 0; i < docs.length; i++) { table = tableFrom(docs[i]); if (table) break; }
              if (!table) { Android.syncFailed('未找到课表。请先打开“课务管理 → 学期课表信息查询”。'); return; }
              var spanning = Array(9).fill(null), slots = [], weekdays = ['','', '星期一','星期二','星期三','星期四','星期五','星期六','星期日'];
              Array.from(table.rows).slice(1).forEach(function(row) {
                var grid = Array(9).fill(''), occupied = Array(9).fill(false), col = 0;
                // Carry a merged table cell into every row it spans. The school table
                // uses rowSpan for courses such as Tuesday periods 6–7.
                spanning.forEach(function(item, index) {
                  if (!item) return;
                  grid[index] = item.text; occupied[index] = true;
                  item.left--; if (item.left === 0) spanning[index] = null;
                });
                Array.from(row.cells).forEach(function(cell) {
                  while (occupied[col]) col++;
                  var span = cell.colSpan || 1, rows = cell.rowSpan || 1, text = cell.innerText.trim();
                  for (var j = 0; j < span; j++) {
                    grid[col + j] = text; occupied[col + j] = true;
                    if (rows > 1) spanning[col + j] = { text: text, left: rows - 1 };
                  }
                  col += span;
                });
                var section = grid[1];
                if (/^\d+$/.test(section)) for (var day = 2; day < 9; day++) if (grid[day]) slots.push({ weekday: weekdays[day], section: Number(section), text: grid[day] });
              });
              var timing = docs.map(function(d) { return d.body ? d.body.innerText : ''; }).join(' ')
                .match(/第1节：\s*8:00-8:45[\s\S]*?第13节：\s*20:40-21:25/);
              var pageText = docs.map(function(d) { return d.body ? d.body.innerText : ''; }).join(' ');
              var weekMatch = pageText.match(/第\s*(\d+)\s*周\s*星期[一二三四五六日天]/);
              function calendarWeekOne() {
                var calendarLink = docs.map(function (d) { return d.querySelector('#hykTermCalender'); }).find(Boolean);
                if (!calendarLink) return Promise.resolve('');
                var handler = calendarLink.getAttribute('onclick') || '';
                var urlMatch = handler.match(/url:\s*'([^']+)'/i);
                if (!urlMatch) return Promise.resolve('');
                var calendarUrl = new URL(urlMatch[1], calendarLink.ownerDocument.location.href).href;
                return fetch(calendarUrl, { credentials: 'include', cache: 'no-store' }).then(function (response) {
                  if (!response.ok) throw new Error('calendar response ' + response.status);
                  return response.text();
                }).then(function (html) {
                  var calendar = new DOMParser().parseFromString(html, 'text/html');
                  var calendarTable = calendar.querySelector('#ctl00_contentParent_dgData');
                  var firstMonday = calendarTable && calendarTable.rows[1] && calendarTable.rows[1].cells[1];
                  var dateMatch = firstMonday && firstMonday.innerText.match(/(\d{1,2})\s*\(?\s*(\d{1,2})月\s*\)?/);
                  var termMatch = calendar.body.innerText.match(/(\d{4})\s*-\s*(\d{4})\s*(秋|春)学期/);
                  if (!dateMatch || !termMatch) return '';
                  var day = Number(dateMatch[1]), month = Number(dateMatch[2]);
                  var year = month >= 7 ? Number(termMatch[1]) : Number(termMatch[2]);
                  return year + '-' + String(month).padStart(2, '0') + '-' + String(day).padStart(2, '0');
                });
              }
              calendarWeekOne().catch(function () { return ''; }).then(function (termWeek1) {
                Android.storeSchedule(JSON.stringify({
                  slots: slots,
                  timing: timing ? timing[0] : '',
                  syncWeek: weekMatch ? Number(weekMatch[1]) : 0,
                  termWeek1: termWeek1,
                  syncedAt: new Date().toISOString()
                }));
              });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun showSyncFailure(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        webView.visibility = View.VISIBLE
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
        webView.loadUrl("file:///android_asset/index.html?sync=failed&message=$encoded")
    }

    private fun saveCredentials(credentials: Credentials) {
        credentialPrefs.edit()
            .putString("username", encrypt(credentials.username))
            .putString("password", encrypt(credentials.password))
            .apply()
    }

    private fun loadCredentials(): Credentials? = try {
        val username = credentialPrefs.getString("username", null)?.let(::decrypt)
        val password = credentialPrefs.getString("password", null)?.let(::decrypt)
        if (username.isNullOrBlank() || password.isNullOrBlank()) null else Credentials(username, password)
    } catch (_: Exception) { null }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("njust_schedule_credentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("njust_schedule_credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun jsString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private inner class SchoolWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (url.contains("gsmis.njust.edu.cn")) fillOrShowLogin(view)
            if (url.contains("StuCourseQuery.aspx")) extractSchedule(view)
            if (url.endsWith("/Gstudent/Default.aspx")) {
                pendingCredentials?.let(::saveCredentials)
                pendingCredentials = null
                view.evaluateJavascript("""
                    (function goToTermSchedule(doc) {
                      var link = doc.querySelector('#tree1_4_a');
                      if (link) { link.click(); setTimeout(function () { Android.readSchedule(); }, 1600); return true; }
                      return Array.from(doc.querySelectorAll('iframe,frame')).some(function (f) {
                        try { return goToTermSchedule(f.contentDocument); } catch (_) { return false; }
                      });
                    })(document);
                """.trimIndent(), null)
            }
        }

        private fun fillOrShowLogin(view: WebView) {
            val credentials = pendingCredentials ?: loadCredentials() ?: return
            // A session refresh stays behind the home screen. If the school asks for
            // CAPTCHA, account/password are filled but the user must complete that
            // security step on the school page.
            view.evaluateJavascript("""
                (function () {
                  var password = document.querySelector('#PassWord, input[type=password]'); if (!password) return;
                  var textInputs = Array.from(document.querySelectorAll('input[type=text],input:not([type])'));
                  var username = document.querySelector('#UserName') || textInputs[0];
                  if (username) { username.value = ${this@MainActivity.jsString(credentials.username)}; username.dispatchEvent(new Event('input', {bubbles:true})); }
                  password.value = ${this@MainActivity.jsString(credentials.password)}; password.dispatchEvent(new Event('input', {bubbles:true}));
                  if (window.kzCaptchaHandedOff) return; window.kzCaptchaHandedOff = true;
                  var captcha = document.querySelector('#ValidateCode') || (textInputs.length > 1 ? textInputs[textInputs.length - 1] : null);
                  if (!captcha) return;
                  var image = document.querySelector('#ValidateImage') || Array.from(document.images).find(function (img) { return /captcha|validate|verify|check|code/i.test(img.src); });
                  if (!image || !image.src) { Android.syncFailed('未找到验证码图片，请重新同步。'); return; }
                  // ValidateImage is an <input type="image"> on the school page, not an
                  // <img>. Fetch it in this authenticated WebView so the home WebView can
                  // display the exact CAPTCHA without losing the session cookie.
                  fetch(image.src, { credentials: 'include', cache: 'no-store' }).then(function (response) {
                    if (!response.ok) throw new Error('captcha response ' + response.status);
                    return response.blob();
                  }).then(function (blob) {
                    var reader = new FileReader();
                    reader.onloadend = function () { Android.captchaRequired(String(reader.result || '')); };
                    reader.readAsDataURL(blob);
                  }).catch(function () { Android.captchaRequired(image.src); });
                })();
            """.trimIndent(), null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame && !request.url.toString().startsWith("file:///android_asset/")) {
                showSyncFailure("网络异常，请检查连接后重试")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility != View.VISIBLE) {
            webView.visibility = View.VISIBLE
            webView.loadUrl("file:///android_asset/index.html")
        } else super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface fun beginSync() = runOnUiThread { this@MainActivity.beginSync() }
        @JavascriptInterface fun login(username: String, password: String) = runOnUiThread {
            if (username.isBlank() || password.isBlank()) return@runOnUiThread
            pendingCredentials = Credentials(username.trim(), password)
            this@MainActivity.beginSync()
        }
        @JavascriptInterface fun hasCredentials(): Boolean = loadCredentials() != null
        @JavascriptInterface fun captchaRequired(image: String) = runOnUiThread {
            captchaImage = image
            webView.visibility = View.VISIBLE
            webView.loadUrl("file:///android_asset/index.html?sync=captcha")
        }
        @JavascriptInterface fun getCaptchaImage(): String = captchaImage
        @JavascriptInterface fun refreshCaptcha() = runOnUiThread {
            schoolWebView.evaluateJavascript("""
                (function () {
                  var image = document.querySelector('#ValidateImage');
                  if (!image || !image.src) { Android.syncFailed('未找到验证码图片，请重新同步。'); return; }
                  var url = image.src + (image.src.indexOf('?') >= 0 ? '&' : '?') + '_kz=' + Date.now();
                  fetch(url, { credentials: 'include', cache: 'no-store' }).then(function (response) {
                    if (!response.ok) throw new Error('captcha response ' + response.status);
                    return response.blob();
                  }).then(function (blob) {
                    var reader = new FileReader();
                    reader.onloadend = function () { Android.captchaRequired(String(reader.result || '')); };
                    reader.readAsDataURL(blob);
                  }).catch(function () { Android.syncFailed('验证码图片加载失败，请重新同步。'); });
                })();
            """.trimIndent(), null)
        }
        @JavascriptInterface fun submitCaptcha(code: String) = runOnUiThread {
            if (code.isBlank()) return@runOnUiThread
            webView.loadUrl("file:///android_asset/index.html?sync=loading")
            schoolWebView.evaluateJavascript("""
                (function () {
                  var inputs = Array.from(document.querySelectorAll('input[type=text],input:not([type])'));
                  var target = document.querySelector('#ValidateCode') || (inputs.length > 1 ? inputs[inputs.length - 1] : null);
                  if (!target) { Android.syncFailed('未找到验证码输入框，请重新同步。'); return; }
                  target.value = ${this@MainActivity.jsString(code.trim())}; target.dispatchEvent(new Event('input', {bubbles:true})); target.dispatchEvent(new Event('change', {bubbles:true}));
                  var form = target.form || document.querySelector('form');
                  var button = document.querySelector('#btLogin') || (form && Array.from(form.querySelectorAll('input[type=submit],input[type=button],button')).find(function (item) { return /登录|确认|submit/i.test(item.value || item.innerText || ''); }));
                  if (button) button.click(); else if (form) form.submit(); else Android.syncFailed('未找到登录确认按钮，请重新同步。');
                })();
            """.trimIndent(), null)
        }
        @JavascriptInterface fun getSchedule(): String = prefs.getString("data", "") ?: ""
        @JavascriptInterface fun readSchedule() = runOnUiThread { extractSchedule(schoolWebView) }
        @JavascriptInterface fun storeSchedule(json: String) = runOnUiThread {
            prefs.edit().putString("data", json).apply()
            NotificationScheduler.schedule(this@MainActivity)
            webView.visibility = View.VISIBLE
            webView.loadUrl("file:///android_asset/index.html?sync=success")
        }
        @JavascriptInterface fun syncFailed(message: String) = runOnUiThread {
            showSyncFailure(message)
        }
    }
}
