package com.maria.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** ফোনে আসল কাজগুলো করে */
object ActionRunner {

    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    private fun go(c: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    /** নাম বা নম্বর থেকে ফোন নম্বর বের করা */
    private fun number(c: Context, v: String): String? {
        val digits = v.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6 && digits.length >= v.trim().length - 3) return digits
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return null
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${v.trim()}%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0).filter { ch -> ch.isDigit() || ch == '+' } }
        return null
    }

    fun run(c: Context, a: JSONObject): String {
        val v = a.optString("value")
        return try {
            when (a.optString("type")) {
                "call" -> {
                    if (!granted(c, Manifest.permission.CALL_PHONE)) return "কল করার অনুমতি দিন (Settings → Permissions)।"
                    val n = number(c, v) ?: return "\"$v\" এর নম্বর পাইনি।"
                    go(c, Intent(Intent.ACTION_CALL, Uri.parse("tel:$n"))); ""
                }
                "sms" -> {
                    if (!granted(c, Manifest.permission.SEND_SMS)) return "SMS পাঠানোর অনুমতি দিন (Settings → Permissions)।"
                    val n = number(c, v) ?: return "\"$v\" এর নম্বর পাইনি।"
                    val body = a.optString("text")
                    if (body.isBlank()) return "মেসেজে কী লিখব বুঝতে পারিনি।"
                    val sm = c.getSystemService(SmsManager::class.java)
                    sm.sendMultipartTextMessage(n, null, sm.divideMessage(body), null, null)
                    "মেসেজ পাঠিয়ে দিয়েছি।"
                }
                "whatsapp" -> {
                    val n = number(c, v)?.trimStart('+') ?: return "\"$v\" এর নম্বর পাইনি।"
                    go(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(a.optString("text"))))); ""
                }
                "youtube" -> { go(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(v)))); "" }
                "maps" -> { go(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(v)))); "" }
                "search" -> { go(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(v)))); "" }
                "url" -> {
                    if (!v.startsWith("https://")) return "শুধু https লিংক খুলি।"
                    go(c, Intent(Intent.ACTION_VIEW, Uri.parse(v))); ""
                }
                "app" -> openApp(c, v)
                "alarm" -> {
                    val p = v.split(":")
                    go(c, Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, p[0].trim().toInt())
                        .putExtra(AlarmClock.EXTRA_MINUTES, p.getOrElse(1) { "0" }.trim().toInt())
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); ""
                }
                "timer" -> {
                    go(c, Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, v.trim().toInt())
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); ""
                }
                "flash" -> {
                    val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return "ফ্ল্যাশ পাইনি।"
                    cm.setTorchMode(id, v.equals("on", true)); ""
                }
                "volume" -> {
                    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = when (v) {
                        "up" -> AudioManager.ADJUST_RAISE
                        "down" -> AudioManager.ADJUST_LOWER
                        else -> AudioManager.ADJUST_MUTE
                    }
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI); ""
                }
                "settings" -> {
                    val act = when (v) {
                        "wifi" -> Settings.ACTION_WIFI_SETTINGS
                        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS
                        "sound" -> Settings.ACTION_SOUND_SETTINGS
                        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    go(c, Intent(act)); ""
                }
                // ---- YouTube ----
                "yt_seo" -> {
                    val seo = JSONObject()
                        .put("title", a.optString("title"))
                        .put("description", a.optString("description"))
                        .put("tags", a.optJSONArray("tags") ?: org.json.JSONArray())
                        .put("thumb", a.optString("thumb"))
                    Prefs.put(c, "seo", seo.toString())
                    Bus.emit("seo", "seo" to seo)
                    "SEO তৈরি, YouTube ট্যাবে দেখুন।"
                }
                "yt_upload" -> {
                    if (v in listOf("private", "unlisted", "public")) Prefs.put(c, "privacy", v)
                    go(c, Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra("pick_video", true))
                    "ভিডিও বেছে নিন।"
                }
                "yt_apply" -> {
                    go(c, Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra("yt_apply", true))
                    "সর্বশেষ ভিডিওতে SEO বসাচ্ছি।"
                }
                else -> ""
            }
        } catch (e: Exception) {
            "কাজটা করতে পারিনি: ${e.message}"
        }
    }

    private fun openApp(c: Context, name: String): String {
        val pm = c.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = list.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: list.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return "\"$name\" অ্যাপ পাইনি।"
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "অ্যাপটা খুলতে পারিনি।"
        go(c, i)
        return ""
    }
}
