package com.pugplayz.wordscapesauto

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Optional extra word suggestions; no screenshot or device data is uploaded. */
object DatamuseClient {
    fun supplementalWords(letters: String): List<String> {
        val result = LinkedHashSet<String>()
        // Datamuse doesn't have an anagram endpoint. Query common words beginning
        // with a few puzzle letters, then filter locally to use ONLY available letters.
        // Offline dictionary remains the primary solver.
        for (prefix in letters.uppercase(Locale.US).toSet().take(4)) {
            try {
                val url = URL("https://api.datamuse.com/words?sp=${prefix.lowercaseChar()}*&max=1000")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1800
                    readTimeout = 2000
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode != 200) continue
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    val array = JSONArray(text)
                    for (i in 0 until array.length()) {
                        val word = array.optJSONObject(i)?.optString("word")?.uppercase(Locale.US) ?: continue
                        if (WordEngine.isValid(word, letters)) result.add(word)
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) { /* Keep offline solver working on lost connectivity. */ }
        }
        return result.toList()
    }
}
