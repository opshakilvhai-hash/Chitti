package com.maria.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** YouTube Data API v3: ভিডিও আপলোড + SEO বসানো */
object YouTube {
    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(
                Scope("https://www.googleapis.com/auth/youtube.upload"),
                Scope("https://www.googleapis.com/auth/youtube")
            ))
            .build()

    private fun call(method: String, url: String, token: String, body: String? = null): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.requestMethod = method
        cn.connectTimeout = 20000
        cn.readTimeout = 60000
        cn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            cn.doOutput = true
            cn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            cn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = cn.responseCode
        val txt = (if (code in 200..299) cn.inputStream else cn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to txt
    }

    private fun tagsOf(seo: JSONObject): JSONArray {
        // YouTube ট্যাগ মোট ৫০০ অক্ষরের মধ্যে
        val src = seo.optJSONArray("tags") ?: JSONArray()
        val out = JSONArray()
        var n = 0
        for (i in 0 until src.length()) {
            val t = src.optString(i).trim()
            if (t.isEmpty() || n + t.length + 1 > 450) continue
            out.put(t); n += t.length + 1
        }
        return out
    }

    fun upload(c: Context, uri: Uri, token: String) {
        try {
            var size = 0L
            var name = "video"
            c.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val si = it.getColumnIndex(OpenableColumns.SIZE)
                    val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (si >= 0) size = it.getLong(si)
                    if (ni >= 0) name = it.getString(ni) ?: name
                }
            }
            if (size <= 0) { Bus.emit("status", "text" to "ভিডিওর সাইজ পাওয়া যায়নি।"); return }

            val seo = try { JSONObject(Prefs.str(c, "seo", "{}")) } catch (_: Exception) { JSONObject() }
            val snippet = JSONObject()
                .put("title", seo.optString("title").ifBlank { name.substringBeforeLast('.') }.take(100))
                .put("description", seo.optString("description"))
                .put("tags", tagsOf(seo))
                .put("categoryId", "22")
            val meta = JSONObject()
                .put("snippet", snippet)
                .put("status", JSONObject()
                    .put("privacyStatus", Prefs.str(c, "privacy", "private"))
                    .put("selfDeclaredMadeForKids", false))

            Bus.emit("progress", "pct" to 0, "text" to "আপলোড শুরু…")
            val init = URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
                .openConnection() as HttpURLConnection
            init.requestMethod = "POST"
            init.doOutput = true
            init.setRequestProperty("Authorization", "Bearer $token")
            init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            init.setRequestProperty("X-Upload-Content-Length", size.toString())
            init.setRequestProperty("X-Upload-Content-Type", "video/*")
            init.outputStream.use { it.write(meta.toString().toByteArray()) }
            if (init.responseCode != 200) {
                val e = init.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Bus.emit("status", "text" to "আপলোড শুরু করা যায়নি: ${init.responseCode} $e")
                return
            }
            val loc = init.getHeaderField("Location")

            val up = URL(loc).openConnection() as HttpURLConnection
            up.requestMethod = "PUT"
            up.doOutput = true
            up.readTimeout = 0
            up.setFixedLengthStreamingMode(size)
            up.setRequestProperty("Content-Type", "video/*")
            var sent = 0L
            var lastPct = -1
            c.contentResolver.openInputStream(uri)!!.use { ins ->
                up.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val pct = (sent * 100 / size).toInt()
                        if (pct != lastPct) { lastPct = pct; Bus.emit("progress", "pct" to pct, "text" to "আপলোড হচ্ছে $pct%") }
                    }
                }
            }
            val code = up.responseCode
            val txt = (if (code in 200..299) up.inputStream else up.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                val id = JSONObject(txt).optString("id")
                Bus.emit("progress", "pct" to 100, "text" to "আপলোড শেষ")
                Core.say(c, "ভিডিও আপলোড হয়ে গেছে। লিংক: https://youtu.be/$id", true)
            } else {
                Bus.emit("status", "text" to "আপলোড ব্যর্থ: $code $txt")
            }
        } catch (e: Exception) {
            Bus.emit("status", "text" to "আপলোডে সমস্যা: ${e.message}")
        }
    }

    /** চ্যানেলের সর্বশেষ ভিডিওতে সর্বশেষ SEO বসানো */
    fun applyToLatest(c: Context, token: String) {
        try {
            val seo = JSONObject(Prefs.str(c, "seo", "{}"))
            if (seo.optString("title").isBlank()) { Core.say(c, "আগে SEO বানাতে বলুন, তারপর বসাব।", true); return }

            val ch = call("GET", "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&mine=true", token)
            if (ch.first != 200) { Bus.emit("status", "text" to "চ্যানেল পাইনি: ${ch.second}"); return }
            val uploads = JSONObject(ch.second).getJSONArray("items").getJSONObject(0)
                .getJSONObject("contentDetails").getJSONObject("relatedPlaylists").getString("uploads")

            val pl = call("GET", "https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=1&playlistId=$uploads", token)
            val vid = JSONObject(pl.second).getJSONArray("items").getJSONObject(0)
                .getJSONObject("contentDetails").getString("videoId")

            val vd = call("GET", "https://www.googleapis.com/youtube/v3/videos?part=snippet&id=$vid", token)
            val snippet = JSONObject(vd.second).getJSONArray("items").getJSONObject(0).getJSONObject("snippet")
            snippet.put("title", seo.optString("title").take(100))
            snippet.put("description", seo.optString("description"))
            snippet.put("tags", tagsOf(seo))

            val body = JSONObject().put("id", vid).put("snippet", snippet).toString()
            val r = call("PUT", "https://www.googleapis.com/youtube/v3/videos?part=snippet", token, body)
            if (r.first in 200..299) Core.say(c, "সর্বশেষ ভিডিওতে SEO বসিয়ে দিয়েছি: https://youtu.be/$vid", true)
            else Bus.emit("status", "text" to "SEO বসানো যায়নি: ${r.first} ${r.second}")
        } catch (e: Exception) {
            Bus.emit("status", "text" to "SEO বসাতে সমস্যা: ${e.message}")
        }
    }
}
