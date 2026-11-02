package app.forge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The only network code in Press: one POST to Gemini's generateContent (Google AI Studio, free tier). Only
 * derived facts and the note are ever sent. Every failure comes back as a [Failure] for the caller to fall
 * back on; nothing here throws.
 */
object Gemini {
    private const val TAG = "Gemini"
    private const val TIMEOUT_MS = 15_000
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    /** Why there's no reply, in words that can be shown as they are. */
    class Failure(message: String) : Exception(message)

    val hasKey: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends [body] to [BuildConfig.GEMINI_MODEL], or to the fallback model if the main one is rate-limited (429)
     * or overloaded (5xx: on 2 Nov 2026 the main model answered 503 "high demand"). Returns the reply's text,
     * which the caller validates.
     */
    suspend fun generate(body: JsonObject): Result<String> = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext Result.failure(Failure("No Gemini key in this build"))
        val first = post(BuildConfig.GEMINI_MODEL, body)
        val code = (first.exceptionOrNull() as? Http)?.code
        val result = if (code == 429 || (code != null && code >= 500)) {
            Log.w(TAG, "$code from ${BuildConfig.GEMINI_MODEL}; trying ${BuildConfig.GEMINI_MODEL_FALLBACK}")
            post(BuildConfig.GEMINI_MODEL_FALLBACK, body)
        } else first
        result.exceptionOrNull()?.let { Log.w(TAG, "Gemini call failed: ${it.message}") }
        result.recoverCatching { throw if (it is Failure) it else Failure(it.message ?: "Gemini didn't answer") }
    }

    private class Http(val code: Int, message: String) : Exception(message)

    private fun post(model: String, body: JsonObject): Result<String> = runCatching {
        val c = URL(ENDPOINT.format(model)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300).orEmpty()
                throw Http(code, "HTTP $code from $model $detail".trim())
            }
            val reply = json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
            reply["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw Failure("Gemini sent no text (${reply["promptFeedback"] ?: "no candidates"})")
        } catch (e: SocketTimeoutException) {
            throw Failure("Gemini took longer than ${TIMEOUT_MS / 1000} s")
        } finally {
            c.disconnect()
        }
    }
}
