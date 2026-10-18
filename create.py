# -*- coding: utf-8 -*-
import shutil
from pathlib import Path

ROOT = Path(".")
FILES = {}

FILES["settings.gradle.kts"] = '''pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ScreenAlert"
include(":app")
'''

FILES["build.gradle.kts"] = '''plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
'''

FILES["gradle.properties"] = '''org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
'''

FILES[".gitignore"] = '''.gradle/
build/
local.properties
.idea/
*.iml
*.apk
'''

FILES["app/build.gradle.kts"] = '''plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.screenalert"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.screenalert"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-arabic:16.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
'''

FILES["app/proguard-rules.pro"] = ""

FILES["app/src/main/AndroidManifest.xml"] = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application
        android:label="Screen Alert"
        android:icon="@android:drawable/ic_btn_speak_now"
        android:theme="@style/Theme.ScreenAlert">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".ScreenAlertService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
    </application>
</manifest>
'''

FILES["app/src/main/res/values/strings.xml"] = '''<resources>
    <string name="app_name">Screen Alert</string>
</resources>
'''

FILES["app/src/main/res/values/themes.xml"] = '''<resources>
    <style name="Theme.ScreenAlert" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
'''

FILES["app/src/main/java/com/example/screenalert/WordEntry.kt"] = '''package com.example.screenalert

data class WordEntry(
    val text: String,
    val soundPaths: List<String> = emptyList(),
    val cooldown: Int = 15,
    val enabled: Boolean = true,
)
'''

FILES["app/src/main/java/com/example/screenalert/ConfigRepository.kt"] = '''package com.example.screenalert

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ConfigRepository(private val ctx: Context) {
    private val file = File(ctx.filesDir, "words.json")
    private val gson = Gson()
    fun load(): List<WordEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<WordEntry>>() {}.type
            gson.fromJson<List<WordEntry>>(file.readText(), type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
    fun save(list: List<WordEntry>) { file.writeText(gson.toJson(list)) }
    fun soundsDir(): File {
        val d = File(ctx.filesDir, "sounds")
        if (!d.exists()) d.mkdirs()
        return d
    }
}
'''

FILES["app/src/main/java/com/example/screenalert/MainActivity.kt"] = '''package com.example.screenalert

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var repo: ConfigRepository

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenAlertService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("resultData", result.data)
            }
            ContextCompat.startForegroundService(this, svc)
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppScreen(repo) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }
}
'''

FILES["app/src/main/java/com/example/screenalert/AppScreen.kt"] = '''package com.example.screenalert

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(repo: ConfigRepository, onStart: () -> Unit) {
    var list by remember { mutableStateOf(repo.load()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WordEntry?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Screen Word Alert") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showAdd = true },
                text = { Text("إضافة كلمة") },
                icon = { Text("+") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Button(onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("▶ ابدأ المراقبة") }
            Spacer(Modifier.height(12.dp))
            Text("الكلمات (${list.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text("ما في كلمات. اضغط + حتى تضيف.",
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) { w ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.text, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f))
                                Switch(checked = w.enabled, onCheckedChange = { c ->
                                    val idx = list.indexOf(w)
                                    val nl = list.toMutableList()
                                    nl[idx] = w.copy(enabled = c)
                                    list = nl
                                    repo.save(nl)
                                })
                            }
                            Text("أصوات: ${w.soundPaths.size} | Cooldown: ${w.cooldown}s",
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editing = w; showAdd = true }) {
                                    Text("تعديل")
                                }
                                TextButton(onClick = {
                                    val nl = list.toMutableList()
                                    nl.remove(w)
                                    list = nl
                                    repo.save(nl)
                                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        WordEditorDialog(
            initial = editing, repo = repo,
            onDismiss = { showAdd = false },
            onSave = { entry ->
                val nl = list.toMutableList()
                if (editing != null) {
                    val idx = nl.indexOfFirst { it.text == editing!!.text }
                    if (idx >= 0) nl[idx] = entry else nl.add(entry)
                } else nl.add(entry)
                list = nl
                repo.save(nl)
                showAdd = false
            }
        )
    }
}

@Composable
fun WordEditorDialog(
    initial: WordEntry?, repo: ConfigRepository,
    onDismiss: () -> Unit, onSave: (WordEntry) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var cooldown by remember { mutableStateOf((initial?.cooldown ?: 15).toString()) }
    var paths by remember { mutableStateOf(initial?.soundPaths ?: emptyList()) }

    val pickSound = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.getType(uri)?.let {
            if (it.contains("mpeg")) ".mp3" else ".wav"
        } ?: ".wav"
        val dest = File(repo.soundsDir(), "${UUID.randomUUID()}$name")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            paths = paths + dest.absolutePath
        } catch (_: Exception) { }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "كلمة جديدة" else "تعديل") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("الكلمة أو الاسم") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = cooldown,
                    onValueChange = { cooldown = it.filter { c -> c.isDigit() } },
                    label = { Text("Cooldown (ثواني)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("الأصوات (${paths.size})",
                    style = MaterialTheme.typography.labelLarge)
                paths.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(p).name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { paths = paths - p }) { Text("حذف") }
                    }
                }
                OutlinedButton(
                    onClick = { pickSound.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ إضافة صوت") }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val cd = cooldown.toIntOrNull() ?: 15
                onSave(WordEntry(text.trim(), paths, cd, initial?.enabled ?: true))
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
'''

FILES["app/src/main/java/com/example/screenalert/ScreenAlertService.kt"] = '''package com.example.screenalert

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.arabic.ArabicTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer

class ScreenAlertService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var repo: ConfigRepository
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private val lastPlayed = mutableMapOf<String, Long>()
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val arabic = TextRecognition.getClient(
        ArabicTextRecognizerOptions.Builder().build())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("alert", "Screen Alert",
                NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, "alert")
            .setContentTitle("Screen Alert شغال")
            .setContentText("يراقب الشاشة...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else { startForeground(1, notif) }

        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("resultData")
            ?: return START_NOT_STICKY
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
            imageReader!!.surface, null, handler)
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
            } catch (_: Exception) { busy = false } finally { img.close() }
        }, handler)
    }

    private fun processBitmap(bmp: Bitmap) {
        val words = repo.load().filter { it.enabled }
        if (words.isEmpty()) { busy = false; return }
        val input = InputImage.fromBitmap(bmp, 0)
        val results = StringBuilder()
        var pending = 2
        val done: () -> Unit = { if (--pending == 0) checkWords(results.toString(), words) }
        arabic.process(input)
            .addOnSuccessListener { results.append(normalize(it.text)).append(' ') }
            .addOnCompleteListener { done() }
        latin.process(input)
            .addOnSuccessListener { results.append(normalize(it.text)).append(' ') }
            .addOnCompleteListener { done() }
    }

    private fun checkWords(ocr: String, words: List<WordEntry>) {
        val now = System.currentTimeMillis()
        for (w in words) {
            val key = normalize(w.text)
            if (key.isBlank() || !ocr.contains(key)) continue
            val last = lastPlayed[w.text] ?: 0L
            if (now - last < w.cooldown * 1000L) continue
            lastPlayed[w.text] = now
            for (p in w.soundPaths) playSound(p)
        }
        busy = false
    }

    private fun playSound(path: String) {
        try {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { it.release() }
                setOnErrorListener { p, _, _ -> p.release(); true }
                prepare(); start()
            }
        } catch (_: Exception) { }
    }

    private fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
        t = t.replace(Regex("[\\\\u064B-\\\\u065F\\\\u0670\\\\u0640]"), "")
        t = t.replace(Regex("[إأآٱا]"), "ا")
        t = t.replace(Regex("[ىئي]"), "ي")
        t = t.replace("ة", "ه")
        t = t.replace("ک", "ك")
        t = t.replace(Regex("\\\\s+"), " ")
        return t.trim().lowercase()
    }

    override fun onDestroy() {
        virtualDisplay?.release(); imageReader?.close(); projection?.stop()
        super.onDestroy()
    }
}
'''

for rel, content in FILES.items():
    p = Path(rel)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
    print("created:", rel)
print("DONE")
