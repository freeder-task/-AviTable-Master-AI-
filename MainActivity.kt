package com.example.aibotgame

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val captureRequest = 7401
    private val micRequest = 7402

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(248, 245, 255))
        }
        val title = TextView(this).apply {
            text = "AI Voice Assistant"
            textSize = 26f
            setTextColor(Color.rgb(76, 29, 149))
        }
        val info = TextView(this).apply {
            text = "বাংলা ভয়েস • Gemini AI • Floating Window • Screen Capture • Accessibility/OCR\n\nলাইভ স্ক্রিন পর্যবেক্ষণ চালু করলে Android-এর স্ক্রিন-শেয়ারিং অনুমতি দিন। চলমান অবস্থায় সর্বশেষ অনুমোদিত ফ্রেম থেকে প্রশ্নের উত্তর দেওয়া হবে। Accessibility আলাদাভাবে Settings থেকে চালু করতে হবে।"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 12, 0, 18)
        }
        root.addView(title); root.addView(info)
        root.addView(makeButton("১. Microphone permission দিন") { requestMic() })
        root.addView(makeButton("২. Floating Window চালু করুন") { startFloating() })
        root.addView(makeButton("৩. লাইভ Screen Monitoring চালু করুন") { requestCapture() })
        root.addView(makeButton("৪. লাইভ Screen Monitoring বন্ধ করুন") {
            stopService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            Toast.makeText(this, "স্ক্রিন পর্যবেক্ষণ বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
        })
        root.addView(makeButton("৫. Accessibility Settings খুলুন") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(makeButton("৬. Overlay permission Settings") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(makeButton("৭. সর্বশেষ স্ক্রিন বিশ্লেষণ") {
            Thread {
                val answer = GeminiClient.ask(this, "স্ক্রিনে বাস্তবে কী দেখা যাচ্ছে তা সংক্ষেপে বর্ণনা করুন। দৃশ্যমান নয় এমন তথ্য অনুমান করবেন না।", ScreenCaptureService.latestImagePath)
                runOnUiThread { Toast.makeText(this, answer.take(220), Toast.LENGTH_LONG).show() }
            }.start()
        })
        root.addView(makeButton("Floating Window বন্ধ করুন") { stopService(Intent(this, FloatingWindowService::class.java)) })
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), micRequest)
        } else Toast.makeText(this, "Microphone permission already granted", Toast.LENGTH_SHORT).show()
    }

    private fun startFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Overlay permission দিয়ে আবার Start চাপুন", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic(); return
        }
        ContextCompat.startForegroundService(this, Intent(this, FloatingWindowService::class.java))
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Activity result API kept for simple compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode); putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "লাইভ স্ক্রিন পর্যবেক্ষণ শুরু হয়েছে। বন্ধ করতে Stop চাপুন।", Toast.LENGTH_SHORT).show()
        }
    }
}
