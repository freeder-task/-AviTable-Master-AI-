package com.example.aibotgame

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** Floating Bengali AI assistant. Game answers are grounded in captured/accessibility data;
 * this service deliberately does not fabricate crash-game signals or cash-out predictions. */
class FloatingWindowService : Service(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var input: EditText
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(10, notification())
        tts = TextToSpeech(this, this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating Window চালাতে Overlay permission দিন", Toast.LENGTH_LONG).show()
            stopSelf(); return
        }
        createOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ai_casino_bot", "AI Voice Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, "ai_casino_bot")
        .setContentTitle("AI Voice Assistant")
        .setContentText("Floating assistant active — screen access only after your consent")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 32; y = 150 }

        val bubbleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 100f
            setColor(Color.parseColor("#CC111827"))
            setStroke(3, Color.parseColor("#3B82F6"))
        }
        bubble = TextView(this).apply {
            text = "🤖 PRO"
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(22, 20, 22, 20); background = bubbleBg
            contentDescription = "AI Assistant floating button; tap to open"
        }
        status = TextView(this).apply {
            text = "✨ এআই প্রো প্রস্তুত। লাইভ স্ক্রিন চালু থাকলে সর্বশেষ অনুমোদিত ফ্রেম বিশ্লেষণ করব।"
            textSize = 13f; setTextColor(Color.parseColor("#38BDF8")); setPadding(8, 8, 8, 12)
        }
        input = EditText(this).apply {
            hint = "বাংলায় প্রশ্ন লিখুন"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(false); minLines = 1; maxLines = 3
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.parseColor("#1E293B"))
                setStroke(1, Color.parseColor("#475569"))
            }
        }
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor("#EE0F172A"))
            setStroke(2, Color.parseColor("#334155"))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 16)
            background = panelBg; visibility = View.GONE
            addView(status); addView(input)
            val row1 = LinearLayout(this@FloatingWindowService).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(makeButton("🎤 কথা বলুন") { startVoiceCommand() }, weightedParams())
            row1.addView(makeButton("AI-কে জিজ্ঞাসা") { analyzeScreenAndQuestion(input.text.toString()) }, weightedParams())
            addView(row1)
            val row2 = LinearLayout(this@FloatingWindowService).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(makeButton("📷 লাইভ স্ক্রিন চালু") {
                status.text = "Main screen খুলে লাইভ Screen Capture অনুমতি দিন।"
                startActivity(Intent(this@FloatingWindowService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, weightedParams())
            row2.addView(makeButton("× বন্ধ") { stopSelf() }, weightedParams())
            addView(row2)
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bubble); addView(panel) }
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0; private var startY = 0; private var downX = 0f; private var downY = 0f
            private var dragged = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; dragged = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (abs(dx) > 6 || abs(dy) > 6) dragged = true
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        return true
                    }
                }
                return false
            }
        })
        try { wm.addView(root, params) } catch (e: Exception) {
            Toast.makeText(this, "Overlay window তৈরি হয়নি: ${e.localizedMessage ?: "permission error"}", Toast.LENGTH_LONG).show(); stopSelf()
        }
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(Color.parseColor("#2563EB"))
            setStroke(1, Color.parseColor("#60A5FA"))
        }
        setPadding(8, 8, 8, 8)
        setOnClickListener { action() }
    }

    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone permission নেই। Main app খুলে অনুমতি দিন।"
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "এই ডিভাইসে Speech Recognition উপলভ্য নয়।"; speakOut(status.text.toString()); return }
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { status.text = "শুনছি… বাংলায় বলুন।" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "কথা শনাক্ত করা হচ্ছে…" }
            override fun onError(error: Int) { status.text = "ভয়েস শনাক্ত হয়নি (কোড $error)। আবার চেষ্টা করুন।" }
            override fun onResults(results: android.os.Bundle?) {
                val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                input.setText(query)
                if (query.isNotBlank()) analyzeScreenAndQuestion(query) else status.text = "কোনো কথা শনাক্ত হয়নি।"
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(intent) } catch (e: Exception) { status.text = "মাইক্রোফোন শুরু করা যায়নি: ${e.localizedMessage ?: "error"}" }
    }

    private fun analyzeScreenAndQuestion(query: String) {
        if (query.isBlank()) { status.text = "প্রথমে প্রশ্ন লিখুন বা 🎤 চাপুন।"; return }
        status.text = if (ScreenCaptureService.isMonitoring) "লাইভ স্ক্রিনের সর্বশেষ ফ্রেম ও প্রশ্ন বিশ্লেষণ করছি…" else "প্রশ্ন ও সর্বশেষ উপলভ্য স্ক্রিন তথ্য বিশ্লেষণ করছি…"
        speakOut("আপনার প্রশ্ন বিশ্লেষণ করছি।")
        executor.execute {
            val imagePath = ScreenCaptureService.latestImagePath
            val answer = GeminiClient.ask(applicationContext, query, imagePath)
            mainHandler.post {
                if (!destroyed) {
                    status.text = answer
                    speakOut(answer)
                }
            }
        }
    }

    private fun speakOut(text: String) {
        try { tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "assistant_reply") } catch (_: Exception) {}
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale("bn", "BD"))
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale("bn")
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        try { if (::root.isInitialized) wm.removeView(root) } catch (_: Exception) {}
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
