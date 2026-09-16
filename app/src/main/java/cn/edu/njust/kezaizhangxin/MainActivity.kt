package cn.edu.njust.kezaizhangxin

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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
    private val courseDetailPrefs by lazy { getSharedPreferences("course_details", MODE_PRIVATE) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var pendingCredentials: Credentials? = null
    private var captchaImage: String = ""
    private var locationRequested = false
    private var mapOpen = false
    private var courseDetailOpen = false
    private var imagePreviewOpen = false
    private var pendingImageCourseKey: String? = null
    private var lastLocation: Location? = null
    private var lastHeadingPublishTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publishLocation(location)
        override fun onProviderDisabled(provider: String) {
            if (locationRequested && !hasEnabledLocationProvider()) notifyHome("onLocationUnavailable")
        }
    }

    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!mapOpen || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeadingPublishTime < HEADING_PUBLISH_INTERVAL_MS) return
            val rotationMatrix = FloatArray(9)
            val adjustedMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val (xAxis, yAxis) = when (currentDisplayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            if (!SensorManager.remapCoordinateSystem(rotationMatrix, xAxis, yAxis, adjustedMatrix)) return
            val magneticHeading = Math.toDegrees(SensorManager.getOrientation(adjustedMatrix, FloatArray(3))[0].toDouble())
            val declination = lastLocation?.let { location ->
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    location.altitude.toFloat(),
                    location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                ).declination
            } ?: 0f
            val trueHeading = (magneticHeading + declination + 360.0) % 360.0
            lastHeadingPublishTime = now
            notifyHome("onNativeHeading", trueHeading)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private data class Credentials(val username: String, val password: String)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        schoolWebView = createWebView(SchoolBridge(), SchoolWebViewClient())
        webView = createWebView(HomeBridge(), WebViewClient())
        setContentView(android.widget.FrameLayout(this).apply {
            // The school WebView is kept behind the home screen. It can finish a
            // valid session refresh without exposing the school's intermediate UI.
            addView(schoolWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        webView.loadUrl("file:///android_asset/index.html")
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::handleBackNavigation)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        NotificationScheduler.ensureChannel(this)
        NotificationScheduler.schedule(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(bridge: Any, client: WebViewClient): WebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " KeZaiZhangXin/0.1"
            addJavascriptInterface(bridge, "Android")
            webViewClient = client
        }

    private fun requestForegroundLocation() {
        locationRequested = true
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST)
            return
        }
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!locationRequested) return
        val permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            locationRequested = false
            notifyHome("onLocationPermissionDenied")
            return
        }
        runCatching { locationManager.removeUpdates(locationListener) }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            notifyHome("onLocationUnavailable")
            return
        }
        providers.mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?.let(::publishLocation)
        providers.forEach { provider -> runCatching { locationManager.requestLocationUpdates(provider, 2_000L, 1f, locationListener) } }
    }

    private fun hasEnabledLocationProvider(): Boolean =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .any { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

    private fun publishLocation(location: Location) {
        if (!locationRequested) return
        lastLocation = location
        notifyHome("onNativeLocation", location.latitude, location.longitude, location.accuracy, location.time)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun startHeadingUpdates() {
        if (!mapOpen) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sensorManager.unregisterListener(headingListener)
        sensorManager.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopHeadingUpdates() {
        sensorManager.unregisterListener(headingListener)
        lastHeadingPublishTime = 0L
    }

    private fun notifyHome(function: String, vararg values: Number) {
        if (!::webView.isInitialized) return
        val arguments = values.joinToString(",") { it.toString() }
        runOnUiThread { webView.evaluateJavascript("window.$function && window.$function($arguments)", null) }
    }

    private fun stopLocationUpdates(clearRequest: Boolean) {
        runCatching { locationManager.removeUpdates(locationListener) }
        if (clearRequest) locationRequested = false
    }

    override fun onResume() {
        super.onResume()
        if (locationRequested) startLocationUpdates()
        if (mapOpen) startHeadingUpdates()
    }

    override fun onPause() {
        stopLocationUpdates(clearRequest = false)
        stopHeadingUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationUpdates(clearRequest = true)
        stopHeadingUpdates()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startLocationUpdates()
        else {
            locationRequested = false
            notifyHome("onLocationPermissionDenied")
        }
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

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun validCourseKey(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it.length <= 500 }

    private fun readCourseDetails(courseKey: String): JSONObject {
        val key = validCourseKey(courseKey) ?: return JSONObject().put("text", "").put("images", JSONArray())
        return runCatching { JSONObject(courseDetailPrefs.getString(key, "") ?: "") }
            .getOrElse { JSONObject() }
            .apply {
                if (!has("text")) put("text", "")
                if (optJSONArray("images") == null) put("images", JSONArray())
            }
    }

    private fun writeCourseDetails(courseKey: String, details: JSONObject) {
        val key = validCourseKey(courseKey) ?: return
        val text = details.optString("text")
        val images = details.optJSONArray("images") ?: JSONArray()
        if (text.isEmpty() && images.length() == 0) courseDetailPrefs.edit().remove(key).apply()
        else courseDetailPrefs.edit().putString(key, details.toString()).apply()
    }

    private fun chooseCourseImages(courseKey: String) {
        val key = validCourseKey(courseKey) ?: return
        pendingImageCourseKey = key
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, COURSE_IMAGE_REQUEST) }
            .onFailure {
                pendingImageCourseKey = null
                Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != COURSE_IMAGE_REQUEST) return
        val courseKey = pendingImageCourseKey.also { pendingImageCourseKey = null } ?: return
        if (resultCode != RESULT_OK || data == null) return
        val selected = buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            data.data?.let(::add)
        }.distinct()
        if (selected.isEmpty()) return

        val details = readCourseDetails(courseKey)
        val images = details.getJSONArray("images")
        val existing = (0 until images.length()).mapNotNull { images.optString(it).takeIf(String::isNotBlank) }.toMutableSet()
        selected.forEach { uri ->
            if (uri.scheme != "content") return@forEach
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (existing.add(uri.toString()) && existing.size <= MAX_IMAGES_PER_COURSE) images.put(uri.toString())
        }
        writeCourseDetails(courseKey, details)
        webView.evaluateJavascript(
            "window.onCourseImagesChanged && window.onCourseImagesChanged(${jsString(courseKey)}, ${jsString(details.toString())})",
            null
        )
    }

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
    override fun onBackPressed() = handleBackNavigation()

    private fun handleBackNavigation() {
        if (imagePreviewOpen) {
            imagePreviewOpen = false
            webView.evaluateJavascript("window.closeImagePreviewFromNative && window.closeImagePreviewFromNative()", null)
        } else if (courseDetailOpen) {
            courseDetailOpen = false
            webView.evaluateJavascript("window.closeCourseDetailFromNative && window.closeCourseDetailFromNative()", null)
        } else if (mapOpen) {
            mapOpen = false
            stopLocationUpdates(clearRequest = true)
            webView.evaluateJavascript("window.closeMapFromNative && window.closeMapFromNative()", null)
        } else if (webView.visibility != View.VISIBLE) {
            webView.visibility = View.VISIBLE
            webView.loadUrl("file:///android_asset/index.html")
        } else finish()
    }

    inner class HomeBridge {
        @JavascriptInterface fun beginSync() = runOnUiThread { this@MainActivity.beginSync() }
        @JavascriptInterface fun login(username: String, password: String) = runOnUiThread {
            if (username.isBlank() || password.isBlank()) return@runOnUiThread
            pendingCredentials = Credentials(username.trim(), password)
            this@MainActivity.beginSync()
        }
        @JavascriptInterface fun hasCredentials(): Boolean = loadCredentials() != null
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
        @JavascriptInterface fun getCourseDetails(courseKey: String): String = readCourseDetails(courseKey).toString()
        @JavascriptInterface fun saveCourseText(courseKey: String, text: String) {
            if (text.length > MAX_COURSE_TEXT_LENGTH) return
            writeCourseDetails(courseKey, readCourseDetails(courseKey).put("text", text))
        }
        @JavascriptInterface fun chooseCourseImages(courseKey: String) = runOnUiThread { this@MainActivity.chooseCourseImages(courseKey) }
        @JavascriptInterface fun removeCourseImage(courseKey: String, imageUri: String) {
            if (!imageUri.startsWith("content://")) return
            val details = readCourseDetails(courseKey)
            val current = details.getJSONArray("images")
            val kept = JSONArray()
            for (index in 0 until current.length()) {
                val value = current.optString(index)
                if (value != imageUri) kept.put(value)
            }
            details.put("images", kept)
            writeCourseDetails(courseKey, details)
        }
        @JavascriptInterface fun setCourseDetailOpen(open: Boolean) = runOnUiThread { courseDetailOpen = open }
        @JavascriptInterface fun setImagePreviewOpen(open: Boolean) = runOnUiThread { imagePreviewOpen = open }
        @JavascriptInterface fun setMapOpen(open: Boolean) = runOnUiThread {
            mapOpen = open
            if (open) startHeadingUpdates() else stopHeadingUpdates()
        }
        @JavascriptInterface fun requestLocation() = runOnUiThread { requestForegroundLocation() }
        @JavascriptInterface fun stopLocation() = runOnUiThread { stopLocationUpdates(clearRequest = true) }
    }

    inner class SchoolBridge {
        @JavascriptInterface fun captchaRequired(image: String) = runOnUiThread {
            captchaImage = image
            webView.visibility = View.VISIBLE
            webView.loadUrl("file:///android_asset/index.html?sync=captcha")
        }
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

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 10
        private const val COURSE_IMAGE_REQUEST = 11
        private const val MAX_IMAGES_PER_COURSE = 50
        private const val MAX_COURSE_TEXT_LENGTH = 100_000
        private const val HEADING_PUBLISH_INTERVAL_MS = 80L
    }
}
