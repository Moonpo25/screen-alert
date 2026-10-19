package com.example.screenalert

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

class ScreenAlertService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var repo: ConfigRepository
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private val lastPlayed = mutableMapOf<String, Long>()
    
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
            val dataPath = filesDir.absolutePath
            val tessDataDir = File(dataPath, "tessdata")
            if (!tessDataDir.exists()) {
                tessDataDir.mkdirs()
                val assetsList = assets.list("tessdata") ?: emptyArray()
                for (file in assetsList) {
                    val inStream = assets.open("tessdata/$file")
                    val outStream = FileOutputStream(File(tessDataDir, file))
                    inStream.copyTo(outStream)
                    inStream.close()
                    outStream.close()
                }
            }
            tessApi = TessBaseAPI()
            tessApi?.init(dataPath, "ara+eng")
            
            // تحسين إعدادات Tesseract عشان يقرأ النصوص المتناثرة (مثل الشاشات)
            tessApi?.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification("يراقب الشاشة...")
        
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, getNotification("يراقب الشاشة..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, getNotification("يراقب الشاشة..."))
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

    private fun getNotification(text: String): Notification {
        return Notification.Builder(this, "alert")
            .setContentTitle("ارتباك شغال")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, getNotification(text))
        } catch (_: Exception) { }
    }

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        
        // التعديل هنا: رجعنا الدقة كاملة 100% بدل 60% عشان الحروف تكون واضحة
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        
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
                val rowPadding = plane.rowStride - plane.pixelStride * sw
                val bmpW = sw + rowPadding / plane.pixelStride
                val bmp = Bitmap.createBitmap(bmpW, sh, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, sw, sh)
                
                // تحويل الصورة لأبيض وأسود (Grayscale) عشان تسهل القراءة
                val grayBmp = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grayBmp)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f) // 0 يعني إزالة الألوان
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(cropped, 0f, 0f, paint)

                processBitmap(grayBmp)
                
                // تنظيف الذاكرة
                bmp.recycle()
                cropped.recycle()
                grayBmp.recycle()
            } catch (_: Exception) {
                busy = false
            } finally {
                img.close()
            }
        }, handler)
    }

    private fun processBitmap(bmp: Bitmap) {
        val words = repo.load().filter { it.enabled }
        if (words.isEmpty()) {
            busy = false
            return
        }

        try {
            tessApi?.setImage(bmp)
            val text = tessApi?.utF8Text ?: ""
            tessApi?.clear()
            
            val normalizedText = normalize(text)
            val found = checkWords(normalizedText, words)
            
            if (found != null) {
                updateNotification("✅ تم رصد: $found")
                handler.postDelayed({
                    updateNotification("يراقب الشاشة...")
                }, 5000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            busy = false
        }
    }

    private fun checkWords(ocr: String, words: List<WordEntry>): String? {
        val now = System.currentTimeMillis()
        val cleanOcr = ocr.replace(Regex("\\s+"), "")
        var foundWord: String? = null

        for (w in words) {
            val cleanKey = normalize(w.text).replace(Regex("\\s+"), "")
            if (cleanKey.isBlank() || !cleanOcr.contains(cleanKey)) continue
            
            foundWord = w.text

            val last = lastPlayed[w.text] ?: 0L
            if (now - last >= w.cooldown * 1000L) {
                lastPlayed[w.text] = now
                for (p in w.soundPaths) {
                    playSound(p)
                }
            }
        }
        return foundWord
    }

    private fun playSound(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA) 
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener { it.release() }
                setOnErrorListener { p, _, _ -> p.release(); true }
                prepare()
                start()
            }
        } catch (_: Exception) { }
    }

    private fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
        t = t.replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        t = t.replace(Regex("[إأآٱا]"), "ا")
        t = t.replace(Regex("[ىئي]"), "ي")
        t = t.replace("ة", "ه")
        t = t.replace("ک", "ك")
        return t.trim().lowercase()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        tessApi?.clear()
        super.onDestroy()
    }
}
