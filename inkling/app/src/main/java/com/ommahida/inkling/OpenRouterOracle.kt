package com.ommahida.inkling

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenRouter voice inside the page. OpenRouter is an OpenAI-compatible gateway, so this talks
 * to its `/chat/completions` endpoint over plain HTTPS — no SDK — sending the handwriting as a
 * base64 image and asking for a small JSON object back. Model choice (Claude, GPT, Gemini, …) is
 * whatever slug the user picked in settings; it must support image input.
 */
class OpenRouterOracle(private val config: Config) : DiaryOracle {

    override fun consult(ink: Bitmap, history: List<Pair<String, String>>): DiaryTurn {
        val png = ByteArrayOutputStream().use { out ->
            ink.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", config.persona))
        for ((written, said) in history) {
            messages.put(JSONObject().put("role", "user").put("content", "(handwritten) $written"))
            messages.put(JSONObject().put("role", "assistant").put("content", said))
        }
        val finalContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", USER_PROMPT))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl))
            )
        messages.put(JSONObject().put("role", "user").put("content", finalContent))

        val body = JSONObject()
            .put("model", config.openrouterModel)
            .put("max_tokens", 400)
            .put("messages", messages)
            .toString()

        val content = post(body)
        return parse(content)
    }

    private fun post(body: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.openrouterKey}")
            setRequestProperty("Content-Type", "application/json")
            // Optional attribution headers OpenRouter uses for its app leaderboard.
            setRequestProperty("HTTP-Referer", "https://ommahida.com/inkling")
            setRequestProperty("X-Title", "Inkling")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("OpenRouter HTTP $code: ${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Pull the assistant text out of the completion, then leniently read the JSON diary turn. */
    private fun parse(response: String): DiaryTurn {
        val message = JSONObject(response)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
        // content is usually a string; some models return an array of parts.
        val content = message.opt("content").let { c ->
            when (c) {
                is JSONArray -> (0 until c.length())
                    .mapNotNull { c.optJSONObject(it)?.optString("text") }
                    .joinToString(" ")
                else -> c?.toString() ?: ""
            }
        }

        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start in 0 until end) {
            try {
                val obj = JSONObject(content.substring(start, end + 1))
                val reply = obj.optString("reply").ifBlank { content.trim() }
                return DiaryTurn(transcription = obj.optString("transcription"), reply = reply)
            } catch (_: Exception) {
                // fall through to treating the whole content as the reply
            }
        }
        return DiaryTurn(transcription = "", reply = content.trim())
    }

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val USER_PROMPT =
            "Here is the fresh ink on your page. Read it and answer. " +
            "Reply ONLY with a JSON object of the exact form " +
            "{\"transcription\": \"<the handwriting, transcribed exactly>\", " +
            "\"reply\": \"<the diary's answer, at most sixty words>\"} and nothing else."
    }
}
