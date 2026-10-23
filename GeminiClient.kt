package com.example.aibotgame

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object GeminiClient {
    // For testing only. Do not ship a real API key inside an APK; use your own backend proxy in production.
    private const val API_KEY = "PASTE_YOUR_GEMINI_API_KEY_HERE"
    private const val MODEL = "gemini-2.5-flash"

    fun ask(context: Context, question: String, imagePath: String? = null): String {
        if (API_KEY.startsWith("PASTE_")) return "Gemini API key সেট করা হয়নি। GeminiClient.kt ফাইলে API key দিন (পরীক্ষার জন্য); প্রোডাকশনে backend ব্যবহার করুন।"
        val visibleText = ScreenTextAccessibilityService.latestVisibleText
        val imageFile = imagePath?.let { File(it) }
        val frameAgeMs = if (imageFile?.exists() == true && ScreenCaptureService.latestImageTimestamp > 0L)
            (System.currentTimeMillis() - ScreenCaptureService.latestImageTimestamp).coerceAtLeast(0L) else Long.MAX_VALUE
        val freshImagePath = if (imageFile?.exists() == true && frameAgeMs <= 15_000L) imageFile.absolutePath else null
        val ocrText = if (freshImagePath != null) runOcr(freshImagePath) else ""
        val frameStatus = when {
            freshImagePath == null && ScreenCaptureService.isMonitoring -> "লাইভ স্ক্রিনের সাম্প্রতিক ফ্রেম পাওয়া যায়নি; স্ক্রিন-ভিত্তিক সিদ্ধান্ত দেবেন না।"
            freshImagePath == null -> "সাম্প্রতিক স্ক্রিন ফ্রেম পাওয়া যায়নি; স্ক্রিন-ভিত্তিক সিদ্ধান্ত দেবেন না।"
            else -> "সর্বশেষ ফ্রেমের বয়স প্রায় ${frameAgeMs / 1000.0} সেকেন্ড।"
        }
        val prompt = """আপনি বাংলা ভাষার স্ক্রিন-সহায়ক। উত্তর bn-BD বাংলায় দিন। কেবল দেওয়া বাস্তব তথ্যের ভিত্তিতে বলুন; স্ক্রিনে যা নেই তা বানাবেন না। Aviator/FlyX/crash game-এর পরবর্তী ফল বা নিশ্চিত cash-out সময় ভবিষ্যদ্বাণী করবেন না। স্ক্রিনের ফ্রেম না থাকলে সেটি স্পষ্ট বলুন এবং দৃশ্যমান প্রমাণ ছাড়া বর্তমান multiplier, trend, bet বা cash-out পরামর্শ উদ্ভাবন করবেন না।
স্ক্রিন ফ্রেমের অবস্থা: $frameStatus
প্রশ্ন: $question
Accessibility থেকে দৃশ্যমান লেখা:
${visibleText.take(5000)}
OCR (ডিভাইসে চেষ্টা করা হয়েছে; প্রধানত Latin লেখা শনাক্ত হতে পারে):
${ocrText.take(3000)}"""
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (freshImagePath != null) {
            try {
                val bytes = File(freshImagePath).readBytes()
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
            } catch (_: Exception) { }
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 500))
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$API_KEY").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 25000
                setRequestProperty("Content-Type", "application/json; charset=utf-8"); doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) "Gemini API error ($code): ${response.take(300)}" else {
                val candidates = JSONObject(response).optJSONArray("candidates")
                val content = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                buildString { if (content != null) for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text", "").orEmpty()) }
                    .ifBlank { "Gemini কোনো টেক্সট উত্তর দেয়নি।" }
            }
        } catch (e: Exception) { "AI সংযোগ ব্যর্থ: ${e.localizedMessage ?: "unknown error"}" }
        finally { conn?.disconnect() }
    }

    private fun runOcr(path: String): String {
        val bitmap = BitmapFactory.decodeFile(path) ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result = it.text }
                .addOnFailureListener { result = "" }
                .addOnCompleteListener { latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: Exception) { } finally { recognizer.close(); bitmap.recycle() }
        return result
    }
}
