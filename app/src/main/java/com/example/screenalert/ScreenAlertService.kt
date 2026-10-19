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
import android.os.HandlerThread
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastPlayed = mutableMapOf<String, Long>()
    
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var tessApi: TessBaseAPI? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository(this)
        
        bgThread = HandlerThread("OCRThread").apply { start() }
        bgHandler = Handler(bgThread.looper)

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
        }, mainHandler)
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
        
        val sw = (metrics.widthPixels * 0.75).toInt()
        val sh = (metrics.heightPixels * 0.75).toInt()
        
        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenAlert", sw, sh, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage()
            if (img == null) return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * sw
                val bmpW = sw + rowPadding / plane.pixelStride
                val bmp = Bitmap.createBitmap(bmpW, sh, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, sw, sh)
                
                // معالجة الصورة: تحويل لأبيض وأسود + عكس الألوان إذا كان الوضع ليلي
                val grayBmp = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grayBmp)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                
                // 1. إزالة الألوان (Grayscale)
                colorMatrix.setSaturation(0f)

                // 2. فحص إذا كانت الخلفية سودة (الوضع الليلي)
                val pixels = IntArray(cropped.width * cropped.height)
                cropped.getPixels(pixels, 0, cropped.width, 0, 0, cropped.width, cropped.height)
                var sum = 0
                var count = 0
                // نأخذ عينة من البكسلات عشان نسرع العملية
                for (i in 0 until pixels.size step 100) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    sum += (r + g + b) / 3
                    count++
                }
                val avg = if (count > 0) sum / count else 255

                // إذا متوسط السطوع أقل من 128 يعني خلفية غامقة (وضع ليلي)
                if (avg < 128) {
                    // نعكس الألوان (نخلي الأسود أبيض والأبيض أسود)
                    colorMatrix.set(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(cropped, 0f, 0f, paint)

                processBitmap(grayBmp)
                
                bmp.recycle()
                cropped.recycle()
                grayBmp.recycle()
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, bgHandler)
    }

    private fun processBitmap(bmp: Bitmap) {
        val words = repo.load().filter { it.enabled }
        if (words.isEmpty()) return

        try {
            tessApi?.setImage(bmp)
            val text = tessApi?.utF8Text ?: ""
            tessApi?.clear()
            
            val normalizedText = normalize(text)
            val found = checkWords(normalizedText, words)
            
            if (found != null) {
                updateNotification("✅ تم رصد: $found")
                mainHandler.postDelayed({
                    updateNotification("يراقب الشاشة...")
                }, 5000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            Thread.sleep(800) // تأخير بسيط لراحة المعالج
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
        bgThread.quitSafely()
        super.onDestroy()
    }
}
