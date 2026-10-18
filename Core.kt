package com.maria.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** একটা কথা/টেক্সট এলে কী হবে: AI-কে জিজ্ঞেস → কনফার্ম → কাজ → উত্তর */
object Core {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pending: JSONObject? = null

    private val RISKY = setOf("sms", "call", "yt_upload", "yt_apply")
    private val YES = listOf("yes", "yeah", "ok", "okay", "confirm", "send", "do it", "হ্যাঁ", "হ্যা", "হা ", "জি", "ঠিক আছে", "পাঠাও", "করো", "কর ")
    private val NO = listOf("no", "cancel", "stop", "না", "বাতিল", "থাক")

    fun hasPending() = pending != null

    fun handle(ctx: Context, text: String, spoken: Boolean) {
        val app = ctx.applicationContext
        Bus.emit("msg", "role" to "me", "text" to text)
        worker.execute {
            val p = pending
            if (p != null) {
                pending = null
                val low = " " + text.lowercase().trim() + " "
                if (NO.any { low.contains(it) }) { say(app, "ঠিক আছে, বাতিল করলাম।", spoken); return@execute }
                if (YES.any { low.contains(it) }) {
                    val r = ActionRunner.run(app, p)
                    say(app, if (r.isBlank()) "হয়ে গেছে।" else r, spoken)
                    return@execute
                }
                // হ্যাঁ/না ছাড়া অন্য কথা বললে নতুন অনুরোধ হিসেবে ধরা হবে
            }
            Bus.emit("state", "mode" to "think")
            val r = Brain.ask(app, text)
            val rem = r.optString("remember", "")
            if (rem.isNotBlank() && rem != "null") Mem.add(app, rem)

            var reply = r.optString("reply", "")
            val acts = r.optJSONArray("actions") ?: JSONArray()
            val needConfirm = Prefs.bool(app, "confirm", true)
            val notes = ArrayList<String>()
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                val type = a.optString("type")
                if (needConfirm && type in RISKY) {
                    pending = a
                    reply = confirmText(a)
                    break
                } else {
                    val s = ActionRunner.run(app, a)
                    if (s.isNotBlank()) notes.add(s)
                }
            }
            if (notes.isNotEmpty()) reply = (reply + " " + notes.joinToString(" ")).trim()
            say(app, reply.ifBlank { "বুঝতে পারিনি, আবার বলবেন?" }, spoken)
        }
    }

    private fun confirmText(a: JSONObject): String = when (a.optString("type")) {
        "sms" -> "${a.optString("value")}-কে এই মেসেজ পাঠাব: \"${a.optString("text")}\"? হ্যাঁ বললে পাঠাব।"
        "call" -> "${a.optString("value")}-কে কল করব? হ্যাঁ বলুন।"
        "yt_upload" -> "ভিডিও আপলোড করব (${a.optString("value", "private")})? হ্যাঁ বললে ভিডিও বাছাইয়ের পর্দা খুলব।"
        "yt_apply" -> "সর্বশেষ ভিডিওতে SEO বসিয়ে দেব? হ্যাঁ বলুন।"
        else -> "করব? হ্যাঁ বলুন।"
    }

    fun say(app: Context, text: String, spoken: Boolean) {
        Bus.emit("msg", "role" to "maria", "text" to text, "confirm" to (pending != null))
        if (spoken || Prefs.bool(app, "voice_reply", true)) {
            Bus.emit("state", "mode" to "speak")
            Voice.speak(app, text) {
                Bus.emit("state", "mode" to "idle")
                MariaService.resume()
            }
        } else {
            Bus.emit("state", "mode" to "idle")
            MariaService.resume()
        }
    }
}
