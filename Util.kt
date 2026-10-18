package com.maria.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** সাধারণ সেটিংস (SharedPreferences) */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("maria", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** মনে রাখা তথ্য */
object Mem {
    fun all(c: Context): JSONArray = try { JSONArray(Prefs.str(c, "mem", "[]")) } catch (_: Exception) { JSONArray() }
    fun add(c: Context, s: String) {
        val a = all(c)
        a.put(s)
        val out = JSONArray()
        val start = maxOf(0, a.length() - 30)
        for (i in start until a.length()) out.put(a.get(i))
        Prefs.put(c, "mem", out.toString())
    }
    fun clear(c: Context) = Prefs.put(c, "mem", "[]")
}

/** ব্যাকগ্রাউন্ড থেকে UI-তে ইভেন্ট পাঠানো */
object Bus {
    private val h = Handler(Looper.getMainLooper())
    @Volatile var sink: ((String) -> Unit)? = null

    fun emit(type: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        val s = o.toString()
        h.post { sink?.invoke(s) }
    }
}

/** কথা বলা (Text-to-Speech) */
object Voice {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile var speaking = false

    fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    }

    fun speak(c: Context, text: String, done: () -> Unit = {}) {
        init(c)
        val t = tts
        if (t == null || !ready || text.isBlank()) { done(); return }
        t.language = Locale.forLanguageTag(Prefs.str(c, "lang", "bn-BD"))
        speaking = true
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { speaking = false; done() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking = false; done() }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maria")
    }

    fun stop() { tts?.stop(); speaking = false }
}
