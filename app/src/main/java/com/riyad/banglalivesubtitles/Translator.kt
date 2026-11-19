package com.riyad.banglalivesubtitles

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Translates text using free endpoints, no API key and no account.
 * Google's public gtx endpoint first, MyMemory as fallback.
 * Both are unofficial/free services, so they can be slow or rate-limited;
 * every failure returns null instead of crashing, and the caller shows
 * "অনুবাদ হয়নি" in that case.
 */
object Translator {

    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 8000

    fun translate(text: String, from: String, to: String): String? {
        if (text.isBlank()) return null
        translateGtx(text, from, to)?.let { return it }
        return translateMyMemory(text, from, to)
    }

    private fun translateGtx(text: String, from: String, to: String): String? {
        return try {
            val url =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                )
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // Response looks like: [[["অনুবাদ","原文",null,null,1]],null,"ja"]
            val start = body.indexOf("[[[\"")
            if (start < 0) return null
            parseJsonString(body, start + 4)
        } catch (e: Exception) {
            null
        }
    }

    private fun translateMyMemory(text: String, from: String, to: String): String? {
        return try {
            val url =
                "https://api.mymemory.translated.net/get?q=" +
                    URLEncoder.encode(text, "UTF-8") + "&langpair=$from|$to"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val key = "\"translatedText\":\""
            val start = body.indexOf(key)
            if (start < 0) return null
            val out = parseJsonString(body, start + key.length)
            if (out.isNullOrBlank() || out.startsWith("MYMEMORY WARNING")) return null
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses one JSON string starting at [start] (the first character after
     * the opening quote) and returns its unescaped value.
     */
    private fun parseJsonString(body: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                val n = body[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < body.length) {
                            val code = body.substring(i + 2, i + 6).toIntOrNull(16)
                            sb.append(code?.toChar() ?: '?')
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        val out = sb.toString()
        return if (out.isBlank()) null else out
    }
}
