package com.example.screenalert

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
