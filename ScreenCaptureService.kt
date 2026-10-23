package com.example.aibotgame

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-consented continuous screen frame service.
 * Android's system consent dialog is required for every new MediaProjection session.
 * The service remains visible through a foreground notification and stops when the
 * user stops sharing, the OS revokes the session, or the service is stopped.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val cleaning = AtomicBoolean(false)
    @Volatile private var lastSavedAt = 0L

    override fun onCreate() {
        super.onCreate()
        startCaptureNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup(stopProjection = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = getProjectionIntent(intent)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi

            thread = HandlerThread("aibotgame-screen-capture").also { it.start() }
            captureHandler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup(stopProjection = false)
                    stopSelf()
                }
            }, captureHandler!!)

            reader?.setOnImageAvailableListener({ imageReader ->
                val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    // Save at most one frame per 1.5 seconds to limit CPU, storage and heat.
                    val now = System.currentTimeMillis()
                    if (now - lastSavedAt >= MIN_FRAME_INTERVAL_MS) {
                        saveImage(image, width, height)
                        lastSavedAt = now
                    }
                } catch (_: Exception) {
                    // Keep the session alive; a later frame may succeed.
                } finally {
                    image.close()
                }
            }, captureHandler)

            display = projection?.createVirtualDisplay(
                "AIBotGameLiveCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, captureHandler
            )
            isMonitoring = true
            updateNotification("Live screen monitoring active — tap Stop in the app to end")
        } catch (_: Exception) {
            cleanup(stopProjection = true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun saveImage(image: Image, width: Int, height: Int) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
            try {
                val target = File(cacheDir, "latest_screen.tmp")
                FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                val finalFile = File(cacheDir, "latest_screen.jpg")
                if (target.renameTo(finalFile)) {
                    latestImagePath = finalFile.absolutePath
                    latestImageTimestamp = System.currentTimeMillis()
                } else {
                    target.copyTo(finalFile, overwrite = true)
                    target.delete()
                    latestImagePath = finalFile.absolutePath
                    latestImageTimestamp = System.currentTimeMillis()
                }
            } finally { bitmap.recycle() }
        } finally { padded.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun getProjectionIntent(intent: Intent?): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    else intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

    private fun startCaptureNotification() {
        val channelId = "screen_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "AI screen monitoring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI Assistant — Screen Monitoring")
            .setContentText("Waiting for screen-sharing permission")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, "Stop", android.app.PendingIntent.getService(
                this, 82, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        val n = NotificationCompat.Builder(this, "screen_capture")
            .setContentTitle("AI Assistant — Screen Monitoring")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, "Stop", android.app.PendingIntent.getService(
                this, 82, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun cleanup(stopProjection: Boolean) {
        if (!cleaning.compareAndSet(false, true)) return
        isMonitoring = false
        try { display?.release() } catch (_: Exception) {}
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        if (stopProjection) try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        display = null; reader = null; projection = null; captureHandler = null; thread = null
        cleaning.set(false)
    }

    override fun onDestroy() {
        cleanup(stopProjection = true)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.aibotgame.STOP_SCREEN_MONITORING"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        private const val NOTIFICATION_ID = 21
        private const val MIN_FRAME_INTERVAL_MS = 1500L
        @Volatile var latestImagePath: String? = null
        @Volatile var latestImageTimestamp: Long = 0L
        @Volatile var isMonitoring: Boolean = false
    }
}
