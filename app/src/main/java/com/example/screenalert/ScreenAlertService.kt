package com.example.screenalert

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import cz.adaptech.tesseract4android.TessBaseAPI
import java.text.Normalizer

class ScreenAlertService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var repo: ConfigRepository
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private val lastPlayed = mutableMapOf<String, Long>()
    
    // محرك Tesseract
    private var tessApi: TessBaseAPI? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "alert", "ارتباك",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        
        try {
            tessApi = TessBaseAPI()
            tessApi?.init(assets, "tessdata", "ara+eng")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, "alert")
            .setContentTitle("ارتباك شغال")
            .setContentText("يراقب الشاشة...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("resultData") ?: return START_NOT_STICKY
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val sw = (metrics.widthPixels * 0.6).toInt()
        val sh = (metrics.heightPixels * 0.6).toInt()
        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenAlert", sw, sh, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            if (busy) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            busy = true
            val img = reader.acquireLatestImage()
            if (img == null) { busy = false; return@setOnImageAvailableListener }
            try {
                val plane = img.planes[0]
                val rowPadding = plane
