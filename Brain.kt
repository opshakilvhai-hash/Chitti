package com.maria.assistant

import android.content.Context
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini এর সাথে কথা বলে JSON উত্তর ও অ্যাকশন আনে */
object Brain {
    private val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-3-flash-preview")
    private val hist = ArrayList<Pair<String, String>>()

    private fun persona(c: Context): String = when (Prefs.str(c, "persona", "friendly")) {
        "concise" -> "Be brief, clear and practical."
        "witty" -> "Add light humour while staying helpful."
        else -> "Be warm and friendly, like a good friend."
    }

    private fun system(c: Context): String {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val mem = Mem.all(c).toString()
        val channel = Prefs.str(c, "channel", "")
        return """
You are "Maria", a voice assistant on the user's Android phone. ${persona(c)}
Reply in the same language the user used (Bengali if they wrote Bengali). Keep replies to 1-3 short plain sentences, no markdown.
Return ONLY one JSON object: {"reply":"...","actions":[],"remember":null}
"remember": one short sentence if the user asked you to remember something, else null.
"actions" is a list (usually 0 or 1) of objects {"type":..., "value":..., "text":...}. Types:
- call: value = phone number or contact name
- sms: value = phone number or contact name, text = the message to send (written in the user's language)
- whatsapp: value = phone number or contact name, text = message (opens chat prefilled)
- youtube: value = search words
- maps: value = place to search
- search: value = Google search words
- url: value = https link
- app: value = app name in English (e.g. "WhatsApp")
- alarm: value = "HH:MM" 24-hour
- timer: value = seconds
- flash: value = "on" or "off"
- volume: value = "up" | "down" | "mute"
- settings: value = wifi|bluetooth|display|sound|battery|general
- yt_seo: user wants SEO for a YouTube video. Put fields directly in the action: {"type":"yt_seo","title":"<=70 chars, keyword first, clickable","description":"3-5 short paragraphs with keywords naturally, a call to subscribe, and 3-5 hashtags at the end","tags":["10-15 tags"],"thumb":"3-5 word thumbnail text"}. Use the channel info below and the topic the user gave; if the topic is missing, ask for it instead.
- yt_upload: user wants to upload a video. The app opens the video picker and uploads it with the last SEO. value = "private"|"unlisted"|"public" (default "private" unless user says otherwise).
- yt_apply: apply the last SEO to the channel's newest uploaded video.
Sending messages, calls and uploads are done by the app after the user confirms, so speak as if you will do it ("I'll send ... — confirm?").
Never invent phone numbers; ask if missing.
Battery: $bat%. Now: $now.
Things the user asked you to remember: $mem
YouTube channel info from user: ${if (channel.isBlank()) "(none)" else channel}
""".trimIndent()
    }

    private fun err(msg: String) = JSONObject().put("reply", msg)

    fun ask(c: Context, text: String): JSONObject {
        val key = Prefs.str(c, "key")
        if (key.isBlank()) return err("Settings-এ আপনার Gemini API key বসান, তাহলে আমি কাজ শুরু করতে পারব।")
        hist.add("user" to text)
        while (hist.size > 16) hist.removeAt(0)
        val contents = JSONArray()
        for ((role, t) in hist) {
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(c)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString().toByteArray()

        for (m in MODELS) {
            try {
                val cn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .openConnection() as HttpURLConnection
                cn.requestMethod = "POST"
                cn.doOutput = true
                cn.connectTimeout = 15000
                cn.readTimeout = 45000
                cn.setRequestProperty("Content-Type", "application/json")
                cn.setRequestProperty("x-goog-api-key", key)
                cn.outputStream.use { it.write(body) }
                val code = cn.responseCode
                val txt = (if (code in 200..299) cn.inputStream else cn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val t = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    val o = JSONObject(t.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                    hist.add("model" to o.optString("reply"))
                    return o
                }
                if (code == 400 || code == 401 || code == 403) {
                    hist.removeAt(hist.size - 1)
                    return err("API key কাজ করছে না। Settings-এ ঠিক key দিন।")
                }
            } catch (_: Exception) { /* পরের মডেল চেষ্টা */ }
        }
        hist.removeAt(hist.size - 1)
        return err("AI-এর সাথে যোগাযোগ করা যাচ্ছে না। ইন্টারনেট দেখে আবার বলুন।")
    }
}
