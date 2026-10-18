package com.maria.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.Identity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var afterAuth: ((String) -> Unit)? = null
    private var ytToken: String? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        push("perms")
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) withToken { t -> Thread { YouTube.upload(applicationContext, uri, t) }.start() }
    }
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            val res = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data)
            res.accessToken?.let { ytToken = it; afterAuth?.invoke(it); Bus.emit("yt", "connected" to true) }
        } catch (e: Exception) {
            Bus.emit("status", "text" to "YouTube লগইন হয়নি: ${e.message}")
        }
        afterAuth = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.setBackgroundColor(0xFF070A1F.toInt())
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("pick_video", false)) { i.removeExtra("pick_video"); pickVideo.launch("video/*") }
        if (i.getBooleanExtra("yt_apply", false)) {
            i.removeExtra("yt_apply")
            withToken { t -> Thread { YouTube.applyToLatest(applicationContext, t) }.start() }
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.sink = { json -> web.evaluateJavascript("window.onMaria&&window.onMaria(" + JSONObject.quote(json) + ")", null) }
    }

    override fun onPause() {
        Bus.sink = null
        super.onPause()
    }

    private fun push(type: String) { Bus.emit(type, "s" to settingsJson()) }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun settingsJson(): JSONObject = JSONObject()
        .put("key", Prefs.str(this, "key"))
        .put("persona", Prefs.str(this, "persona", "friendly"))
        .put("lang", Prefs.str(this, "lang", "bn-BD"))
        .put("confirm", Prefs.bool(this, "confirm", true))
        .put("voice_reply", Prefs.bool(this, "voice_reply", true))
        .put("privacy", Prefs.str(this, "privacy", "private"))
        .put("channel", Prefs.str(this, "channel"))
        .put("theme", Prefs.str(this, "theme", "aurora"))
        .put("always", Prefs.bool(this, "always", false))
        .put("seo", try { JSONObject(Prefs.str(this, "seo", "{}")) } catch (_: Exception) { JSONObject() })
        .put("memory", Mem.all(this))
        .put("yt", ytToken != null)
        .put("perm", JSONObject()
            .put("mic", has(Manifest.permission.RECORD_AUDIO))
            .put("contacts", has(Manifest.permission.READ_CONTACTS))
            .put("sms", has(Manifest.permission.SEND_SMS))
            .put("call", has(Manifest.permission.CALL_PHONE))
            .put("overlay", Settings.canDrawOverlays(this)))

    private fun askPerms() {
        val list = ArrayList<String>()
        list += listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    /** YouTube টোকেন নিয়ে কাজ চালানো (দরকারে লগইন স্ক্রিন) */
    private fun withToken(block: (String) -> Unit) {
        Identity.getAuthorizationClient(this).authorize(YouTube.authRequest())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    afterAuth = block
                    authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else {
                    val t = r.accessToken
                    if (t != null) { ytToken = t; Bus.emit("yt", "connected" to true); block(t) }
                }
            }
            .addOnFailureListener {
                Bus.emit("status", "text" to "YouTube সংযোগ হয়নি। README-র Google Cloud ধাপ দেখুন। (${it.message})")
            }
    }

    inner class Bridge {
        @JavascriptInterface fun ask(t: String) { if (t.isNotBlank()) Core.handle(applicationContext, t.trim(), false) }

        @JavascriptInterface fun mic() = runOnUiThread {
            if (!has(Manifest.permission.RECORD_AUDIO)) { askPerms(); return@runOnUiThread }
            Voice.stop()
            MariaService.start(this@MainActivity, true)
        }

        @JavascriptInterface fun alwaysOn(on: Boolean) = runOnUiThread {
            Prefs.putBool(applicationContext, "always", on)
            if (on) {
                if (!has(Manifest.permission.RECORD_AUDIO)) { askPerms(); return@runOnUiThread }
                MariaService.start(this@MainActivity, false)
            } else MariaService.stop(this@MainActivity)
        }

        @JavascriptInterface fun stopAll() = runOnUiThread { Voice.stop(); MariaService.stop(this@MainActivity) }

        @JavascriptInterface fun settings(): String = settingsJson().toString()

        @JavascriptInterface fun save(json: String) {
            val o = JSONObject(json)
            val c = applicationContext
            for (k in listOf("key", "persona", "lang", "privacy", "channel", "theme")) if (o.has(k)) Prefs.put(c, k, o.getString(k))
            for (k in listOf("confirm", "voice_reply")) if (o.has(k)) Prefs.putBool(c, k, o.getBoolean(k))
            if (o.has("seo")) Prefs.put(c, "seo", o.getJSONObject("seo").toString())
        }

        @JavascriptInterface fun perms() = runOnUiThread { askPerms() }

        @JavascriptInterface fun overlay() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun ytConnect() = runOnUiThread { withToken { } }
        @JavascriptInterface fun ytPick() = runOnUiThread { pickVideo.launch("video/*") }
        @JavascriptInterface fun ytApply() = runOnUiThread {
            withToken { t -> Thread { YouTube.applyToLatest(applicationContext, t) }.start() }
        }

        @JavascriptInterface fun clearMemory() { Mem.clear(applicationContext) }

        @JavascriptInterface fun battery(): Int =
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
