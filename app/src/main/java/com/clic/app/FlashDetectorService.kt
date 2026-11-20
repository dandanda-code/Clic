package com.clic.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat

/**
 * Servicio en primer plano que captura la pantalla (MediaProjection), mide su brillo medio
 * y, cuando detecta un aumento brusco (destello), pide al servicio de accesibilidad un clic.
 */
class FlashDetectorService : Service() {

    companion object {
        const val ACTION_START = "com.clic.app.START"
        const val ACTION_STOP = "com.clic.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "clic_detector"
        private const val NOTIFICATION_ID = 1

        /** Ventana en la que se busca la subida de brillo. */
        private const val RISE_WINDOW_MS = 300L

        /** Factor de reducción de la captura (más pequeño = más rápido). */
        private const val DOWNSCALE = 8

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var clickCount = 0
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private var threshold = 40f
    private var cooldownMs = 500L

    // Estado del detector
    private val history = ArrayDeque<Pair<Long, Float>>()
    private var lastLum = -1f
    private var armed = true
    private var lastClickAt = 0L
    private var lastNotifUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // En Android 14+ hay que estar en primer plano ANTES de obtener la proyección.
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification("Esperando destellos…"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                )
                startCapture(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        releaseCapture()
        threshold = settings.brightnessThreshold
        cooldownMs = settings.cooldownMs
        history.clear()
        lastLum = -1f
        armed = true
        clickCount = 0

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data) ?: run { stopEverything(); return }
        projection = proj

        val thread = HandlerThread("clic-capture").also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopEverything() }
            }
        }, mainHandler)

        val metrics = screenMetrics()
        val w = (metrics.widthPixels / DOWNSCALE).coerceAtLeast(16)
        val h = (metrics.heightPixels / DOWNSCALE).coerceAtLeast(16)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val lum = averageLuminance(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                onFrame(lum)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = proj.createVirtualDisplay(
            "clic-capture", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        isRunning = true
    }

    /** Brillo medio 0..255 de la imagen (muestreando 1 de cada 2 píxeles en cada eje). */
    private fun averageLuminance(
        buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): Float {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                val i = rowStart + x * pixelStride
                val r = buffer.get(i).toInt() and 0xFF
                val g = buffer.get(i + 1).toInt() and 0xFF
                val b = buffer.get(i + 2).toInt() and 0xFF
                sum += (r * 299 + g * 587 + b * 114)
                count++
                x += 2
            }
            y += 2
        }
        return if (count == 0) 0f else sum / (count * 1000f)
    }

    /** Lógica de detección: subida de brillo >= umbral respecto al mínimo reciente. */
    private fun onFrame(lum: Float) {
        val now = SystemClock.uptimeMillis()
        while (history.isNotEmpty() && now - history.first().first > RISE_WINDOW_MS) {
            history.removeFirst()
        }
        var reference = if (lastLum >= 0f) lastLum else lum
        for ((_, v) in history) if (v < reference) reference = v

        val rise = lum - reference
        if (armed && rise >= threshold && now - lastClickAt >= cooldownMs) {
            armed = false
            lastClickAt = now
            performClick()
        } else if (!armed && rise < threshold / 2f) {
            // El destello terminó (o el brillo se estabilizó): listo para el siguiente.
            armed = true
        }

        history.addLast(now to lum)
        lastLum = lum
    }

    private fun performClick() {
        mainHandler.post {
            val service = ClickAccessibilityService.instance
            if (service == null) {
                updateNotification("⚠ Activa el servicio de accesibilidad de Clic")
                return@post
            }
            val metrics = screenMetrics()
            val x = if (settings.tapX >= 0) settings.tapX else metrics.widthPixels / 2
            val y = if (settings.tapY >= 0) settings.tapY else metrics.heightPixels / 2
            if (service.tap(x.toFloat(), y.toFloat())) {
                clickCount++
                val now = SystemClock.uptimeMillis()
                if (now - lastNotifUpdate > 1000) {
                    lastNotifUpdate = now
                    updateNotification("Clics realizados: $clickCount")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun releaseCapture() {
        isRunning = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close(); imageReader = null
        workerThread?.quitSafely(); workerThread = null
        projection?.stop(); projection = null
    }

    private fun stopEverything() {
        val proj = projection
        projection = null           // evita recursión desde MediaProjection.Callback.onStop
        proj?.stop()
        releaseCapture()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseCapture()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Detector de destellos", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FlashDetectorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Clic está vigilando la pantalla")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
