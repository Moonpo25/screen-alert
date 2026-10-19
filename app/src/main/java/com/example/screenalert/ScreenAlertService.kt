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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // إنشاء الإشعار الدائم
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

    // دالة مساعدة لإنشاء الإشعار
    private fun getNotification(text: String): Notification {
        return Notification.Builder(this, "alert")
            .setContentTitle("ارتباك شغال")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    // دالة تحديث الإشعار الدائم
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
                val rowPadding = plane.rowStride - plane.pixelStride * sw
                val bmpW = sw + rowPadding / plane.pixelStride
                val bmp = Bitmap.createBitmap(bmpW, sh, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, sw, sh)
                processBitmap(cropped)
                bmp.recycle()
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
            bmp.recycle()
            busy = false
            return
        }

        try {
            tessApi?.setImage(bmp)
            val text = tessApi?.utF8Text ?: ""
            tessApi?.clear()
            
            // نرجع النص الممسوح
            val normalizedText = normalize(text)
            val found = checkWords(normalizedText, words)
            
            if (found != null) {
                // تحديث الإشعار الدائم عشان يبين للمستخدم
                updateNotification("✅ تم رصد: $found")
                // إعادة الإشعار لوضع المراقبة بعد 5 ثواني
                handler.postDelayed({
                    updateNotification("يراقب الشاشة...")
                }, 5000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            busy = false
            bmp.recycle()
        }
    }

    private fun checkWords(ocr: String, words: List<WordEntry>): String? {
        val now = System.currentTimeMillis()
        // نشيل كل المسافات من النص اللي قاريه
        val cleanOcr = ocr.replace(Regex("\\s+"), "")
        var foundWord: String? = null

        for (w in words) {
            val cleanKey = normalize(w.text).replace(Regex("\\s+"), "")
            if (cleanKey.isBlank() || !cleanOcr.contains(cleanKey)) continue
            
            foundWord = w.text

            val last = lastPlayed[w.text] ?: 0L
            // إذا مر وقت الكول داون، يشغل الصوت
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
