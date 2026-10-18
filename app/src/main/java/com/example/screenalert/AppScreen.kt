package com.example.screenalert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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

// دالة لتشغيل الصوت وقت التجربة
fun testSound(ctx: Context, path: String) {
    try {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
        )
        mp.setDataSource(path)
        mp.setOnCompletionListener { it.release() }
        mp.prepare()
        mp.start()
    } catch (_: Exception) { }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(repo: ConfigRepository, onStart: () -> Unit) {
    var list by remember { mutableStateOf(repo.load()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WordEntry?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ارتباك") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showAdd = true },
                text = { Text("إضافة كلمة") },
                icon = { Text("+") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("▶ ابدأ المراقبة") }

            Spacer(Modifier.height(12.dp))
            Text("الكلمات (${list.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (list.isEmpty()) {
                Text("ما في كلمات. اضغط + حتى تضيف.", style = MaterialTheme.typography.bodySmall)
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
                                }) {
                                    Text("حذف", color = MaterialTheme.colorScheme.error)
                                }
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
    initial: WordEntry?,
    repo: ConfigRepository,
    onDismiss: () -> Unit,
    onSave: (WordEntry) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var cooldown by remember { mutableStateOf((initial?.cooldown ?: 15).toString()) }
    var paths by remember { mutableStateOf(initial?.soundPaths ?: emptyList()) }

    val pickSound = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
        val ext = when {
            mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
            mime.contains("wav") -> ".wav"
            mime.contains("ogg") -> ".ogg"
            mime.contains("m4a") || mime.contains("mp4") -> ".m4a"
            else -> ".mp3"
        }
        val dest = File(repo.soundsDir(), "${UUID.randomUUID()}$ext")
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
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("الكلمة أو الاسم") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter { c -> c.isDigit() } },
                    label = { Text("Cooldown (ثواني)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text("الأصوات (${paths.size})", style = MaterialTheme.typography.labelLarge)
                paths.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(p).name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { testSound(ctx, p) }) { Text("▶ تجربة") }
                        TextButton(onClick = { paths = paths - p }) { Text("حذف") }
                    }
                }
                OutlinedButton(
                    onClick = { pickSound.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ إضافة صوت من الجهاز") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val cd = cooldown.toIntOrNull() ?: 15
                    onSave(WordEntry(text.trim(), paths, cd, initial?.enabled ?: true))
                }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
