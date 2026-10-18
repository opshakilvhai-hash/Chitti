package com.maria.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle

/**
 * ব্যাকগ্রাউন্ডে সবসময় শোনে। "Maria" / "মারিয়া" বললে কমান্ড নেয়।
 * once = true হলে শুধু একটা কথা শোনে (UI-র মাইক বোতাম)।
 */
class MariaService : Service() {
    private var sr: SpeechRecognizer? = null
    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var once = false
    private var awaiting = false
    private var misses = 0

    companion object {
        private const val CH = "maria_listen"
        private val WAKE = listOf("hey maria", "maria", "mariya", "marya", "মারিয়া", "মেরিয়া", "মারিআ", "মারীয়া")
        @Volatile private var instance: MariaService? = null

        fun start(c: Context, once: Boolean) {
            val i = Intent(c, MariaService::class.java).putExtra("once", once)
            androidx.core.content.ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) { c.stopService(Intent(c, MariaService::class.java)) }

        /** কথা শেষ হলে আবার শোনা শুরু */
        fun resume() { instance?.listenSoon(250) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        once = intent?.getBooleanExtra("once", false) ?: false
        awaiting = once
        misses = 0
        goForeground()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Core.say(this, "এই ফোনে Speech Recognition নেই। Google অ্যাপ আপডেট করুন।", false)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Bus.emit("state", "mode" to "listen", "always" to !once)
        listenSoon(100)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Maria listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("Maria")
            .setContentText(if (once) "শুনছি…" else "\"Maria\" বললেই আমি শুনব")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun listenSoon(ms: Long) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ listen() }, ms)
    }

    private fun listen() {
        if (!running) return
        if (Voice.speaking) { listenSoon(600); return }
        if (Core.hasPending()) awaiting = true
        try {
            sr?.destroy()
            sr = SpeechRecognizer.createSpeechRecognizer(this)
            sr!!.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) onMiss() else process(t)
                }
                override fun onError(error: Int) {
                    onMiss(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 350)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.str(this, "lang", "bn-BD"))
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            sr!!.startListening(i)
        } catch (_: Exception) {
            listenSoon(2000)
        }
    }

    private fun onMiss(delay: Long = 350) {
        if (once) {
            if (++misses >= 2) { Bus.emit("state", "mode" to "idle"); stopSelf(); return }
        }
        listenSoon(delay)
    }

    private fun process(t: String) {
        val low = t.lowercase()
        if (awaiting) {
            awaiting = false
            dispatch(t)
            return
        }
        // wake word খোঁজা
        var at = -1; var len = 0
        for (w in WAKE) {
            val i = low.indexOf(w)
            if (i >= 0 && (at < 0 || i < at)) { at = i; len = w.length }
        }
        if (at < 0) { listenSoon(200); return }
        val rest = t.substring(at + len).trim(' ', ',', '।', '.', '?', '!')
        if (rest.length > 1) dispatch(rest)
        else {
            awaiting = true
            Bus.emit("state", "mode" to "listen")
            Voice.speak(this, "জি, বলুন।") { listenSoon(150) }
        }
    }

    private fun dispatch(cmd: String) {
        Core.handle(this, cmd, true)
        if (once) { running = false; stopSelf() }
        // always-on মোডে Core উত্তর শেষ হলে resume() ডাকবে
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        sr = null
        if (instance === this) instance = null
        Bus.emit("state", "mode" to "idle", "always" to false)
        super.onDestroy()
    }
}
