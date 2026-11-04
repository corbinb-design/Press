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

/** Why the template was used: [why] is shown; [detail] is the raw error, behind a tap in debug builds only. */
data class Fallback(val why: Gemini.Why, val detail: String)

/**
 * The only network code in Press: one POST to Gemini's generateContent (Google AI Studio, free tier). Only
 * derived facts and the note are ever sent. Every failure comes back as a [Failure] for the caller to fall
 * back on; nothing here throws.
 */
object Gemini {
    private const val TAG = "Gemini"
    private const val TIMEOUT_MS = 15_000
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    /** Why there's no reply. [message] is raw detail (debug builds only); [why] is what people see. */
    class Failure(message: String, val why: Why = Why.CHECKS) : Exception(message)

    /** Why an AI reply wasn't used, in friendly words: never JSON, HTTP bodies or hostnames. */
    enum class Why(val line: String, val short: String) {
        NO_KEY("No AI key in this build, showing the standard issue", "no AI key in this build"),
        OFFLINE("Offline, showing the standard issue", "offline"),
        BUSY("AI busy, showing the standard issue", "AI busy"),
        UNAVAILABLE("AI unavailable, showing the standard issue", "AI unavailable"),
        NUMBER("AI reply dropped: it used a number Press didn’t compute", "the AI used a number Press didn’t compute"),
        CHECKS("AI reply dropped: it didn’t pass Press’s checks", "the AI reply didn’t pass Press’s checks"),
    }

    /** The friendly reason for any failure from [generate] or from validating its reply. */
    fun why(t: Throwable): Why = when (t) {
        is Failure -> t.why
        is app.forge.data.Coach.Invalid -> if (t.badNumber) Why.NUMBER else Why.CHECKS
        else -> Why.CHECKS
    }

    /** Tests that drive the UI set this so they never call Gemini (debug builds carry the key). */
    @androidx.annotation.VisibleForTesting @Volatile var offline = false

    val hasKey: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank() && !offline

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends [body] to [BuildConfig.GEMINI_MODEL], or to the fallback model if the main one is rate-limited (429)
     * or overloaded (5xx: on 2 Nov 2026 the main model answered 503 "high demand"), or slower than the 15 s limit
     * (the weekly issue call often was). Each request has its own limit. Returns the reply's text, for the caller to validate.
     */
    suspend fun generate(body: JsonObject): Result<String> = withContext(Dispatchers.IO) {
        if (!hasKey) return@withContext Result.failure(Failure("No Gemini key in this build", Why.NO_KEY))
        val first = post(BuildConfig.GEMINI_MODEL, body)
        val code = (first.exceptionOrNull() as? Http)?.code
        val result = if (code == 429 || (code != null && code >= 500) || first.exceptionOrNull() is Timeout) {
            Log.w(TAG, "${code ?: "timeout"} from ${BuildConfig.GEMINI_MODEL}; trying ${BuildConfig.GEMINI_MODEL_FALLBACK}")
            post(BuildConfig.GEMINI_MODEL_FALLBACK, body)
        } else first
        result.exceptionOrNull()?.let { Log.w(TAG, "Gemini call failed: ${it.message}") }
        result.recoverCatching {
            throw when (it) {
                is Failure -> it
                is Http -> Failure(it.message.orEmpty(), if (it.code == 429 || it.code >= 500) Why.BUSY else Why.UNAVAILABLE)
                is Timeout -> Failure(it.message.orEmpty(), Why.BUSY)
                is java.io.IOException -> Failure(it.toString(), Why.OFFLINE) // unknown host, no route, refused, reset
                else -> Failure(it.message ?: "Gemini didn't answer")
            }
        }
    }

    private class Http(val code: Int, message: String) : Exception(message)
    private class Timeout(message: String) : Exception(message)

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
            throw Timeout("$model took longer than ${TIMEOUT_MS / 1000} s")
        } finally {
            c.disconnect()
        }
    }
}
