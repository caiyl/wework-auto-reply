package com.example.chaserpa.service

data class ChatMessage(
    val groupName: String,
    val sender: String,
    val content: String,
    val time: String? = null,
    val rawId: String? = null
)
