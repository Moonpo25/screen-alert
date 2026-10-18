package com.example.screenalert

data class WordEntry(
    val text: String,
    val soundPaths: List<String> = emptyList(),
    val cooldown: Int = 15,
    val enabled: Boolean = true,
)
