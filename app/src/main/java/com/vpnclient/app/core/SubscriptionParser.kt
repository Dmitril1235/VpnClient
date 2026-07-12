package com.vpnclient.app.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Разбирает текст, который пользователь вставил из буфера обмена: это может быть
 * одна ссылка, несколько ссылок построчно, base64-подписка целиком — или URL,
 * по которому нужно скачать подписку (это уже делает fetchAndParseUrl).
 */
object SubscriptionParser {

    fun parseLinksBlob(text: String): List<ParsedServer> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1) Пробуем как обычные ссылки построчно
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
        val direct = lines.mapNotNull { LinkParser.parse(it) }
        if (direct.isNotEmpty()) return direct

        // 2) Иначе пробуем как base64-подписку (список ссылок закодирован целиком)
        return try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT))
            decoded.lines().mapNotNull { LinkParser.parse(it.trim()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isSubscriptionUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://")
    }

    /** Скачивает подписку по URL и парсит её. Вызывать из корутины (IO уже внутри). */
    suspend fun fetchAndParseUrl(url: String): List<ParsedServer> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseLinksBlob(body)
        } finally {
            connection.disconnect()
        }
    }
}
