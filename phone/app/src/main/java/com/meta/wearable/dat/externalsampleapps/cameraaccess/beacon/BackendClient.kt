/*
 * Beacon companion-app backend client.
 *
 * Talks to the home-box backend ONLY, over plain HTTP on the LAN / Tailscale
 * network. Fixed contract (do not invent a different one):
 *   GET  /health -> {"ok": true}
 *   POST /ask {"image_b64": str, "question": str} -> {"answer": str, "sam": str|null}
 *   GET  /latest -> {"question", "answer", "at"}
 *
 * No API key is ever held, requested, logged, or sent by this app. The backend
 * owns the Meta Model API key on the home box; this client sends only a JPEG
 * frame (base64) and a question string, and receives only answer text.
 *
 * No third-party HTTP/JSON dependency: HttpURLConnection + hand-rolled minimal
 * JSON escaping/parsing, so the app builds even if a dependency mirror is slow.
 * Sample data only: callers pass frames the user captured or picked, or the
 * bundled sample asset — never private data, credentials, or client data.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Result of POST /ask. `sam` is null when the backend had no SAM context. */
data class AskResult(val answer: String, val sam: String?)

/** Result of GET /latest. All fields may be null when nothing was asked yet. */
data class LatestResult(val question: String?, val answer: String?, val at: String?)

class BackendClient(private val baseUrl: String) {

  companion object {
    private const val TAG = "BeaconBackend"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 90_000
    // Downscale + JPEG so a glasses frame stays small on the LAN/Tailscale link.
    private const val MAX_SIDE_PX = 1280
    private const val JPEG_QUALITY = 85
  }

  /** Normalised base URL with no trailing slash. */
  val normalizedBaseUrl: String = baseUrl.trim().trimEnd('/')

  fun checkHealth(): Boolean {
    return try {
      val body = get("/health")
      body.contains("\"ok\"") && (body.contains("true"))
    } catch (e: Exception) {
      Log.w(TAG, "health check failed for $normalizedBaseUrl: ${e.message}")
      false
    }
  }

  /** Sends a frame + question. Throws IOException on transport/HTTP failure. */
  @Throws(IOException::class)
  fun ask(imageB64: String, question: String): AskResult {
    val payload =
        """{"image_b64":"$imageB64","question":"${escapeJson(question)}"}"""
    val body = post("/ask", payload)
    val answer = extractStringField(body, "answer")
        ?: throw IOException("Backend /ask reply had no 'answer' field: ${body.take(200)}")
    return AskResult(answer = answer, sam = extractStringField(body, "sam"))
  }

  /** Convenience: JPEG-encodes a bitmap (downscaled) and asks with it. */
  @Throws(IOException::class)
  fun askWithBitmap(bitmap: Bitmap, question: String): AskResult {
    return ask(bitmapToJpegBase64(bitmap), question)
  }

  fun fetchLatest(): LatestResult {
    val body = get("/latest")
    return LatestResult(
        question = extractStringField(body, "question"),
        answer = extractStringField(body, "answer"),
        at = extractStringField(body, "at"),
    )
  }

  @Throws(IOException::class)
  private fun get(path: String): String {
    val conn = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      setRequestProperty("Accept", "application/json")
    }
    return readResponse(conn)
  }

  @Throws(IOException::class)
  private fun post(path: String, jsonBody: String): String {
    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
    val conn = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      setFixedLengthStreamingMode(bytes.size)
    }
    conn.outputStream.use { it.write(bytes) }
    return readResponse(conn)
  }

  @Throws(IOException::class)
  private fun readResponse(conn: HttpURLConnection): String {
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    conn.disconnect()
    if (code !in 200..299) {
      throw IOException("Backend HTTP $code: ${body.take(300)}")
    }
    return body
  }
}

/** Downscale (long side <= 1280px) and JPEG-encode a bitmap to base64. */
fun bitmapToJpegBase64(bitmap: Bitmap): String {
  val scale =
      minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height).toFloat())
  val scaled =
      if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
      } else {
        bitmap
      }
  val out = ByteArrayOutputStream()
  scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
  if (scaled !== bitmap) scaled.recycle()
  return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Minimal JSON string escaper for the question field (image_b64 needs none). */
internal fun escapeJson(raw: String): String {
  val sb = StringBuilder(raw.length + 16)
  for (ch in raw) {
    when (ch) {
      '"' -> sb.append("\\\"")
      '\\' -> sb.append("\\\\")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      '\t' -> sb.append("\\t")
      else -> {
        if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
      }
    }
  }
  return sb.toString()
}

/**
 * Extracts a top-level JSON string field, honouring backslash escapes, or
 * returns null for a JSON null / a missing field. Deliberately small: the
 * backend replies are flat objects, and this avoids a JSON dependency.
 */
internal fun extractStringField(json: String, field: String): String? {
  val key = "\"$field\""
  var idx = json.indexOf(key)
  while (idx >= 0) {
    var i = idx + key.length
    while (i < json.length && json[i].isWhitespace()) i++
    if (i < json.length && json[i] == ':') {
      i++
      while (i < json.length && json[i].isWhitespace()) i++
      if (json.startsWith("null", i)) return null
      if (i < json.length && json[i] == '"') {
        val sb = StringBuilder()
        i++
        while (i < json.length) {
          val ch = json[i]
          if (ch == '\\' && i + 1 < json.length) {
            when (json[i + 1]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'u' -> {
                if (i + 5 < json.length) {
                  sb.append(json.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                  i += 4
                }
              }
              else -> sb.append(json[i + 1])
            }
            i += 2
          } else if (ch == '"') {
            return sb.toString()
          } else {
            sb.append(ch)
            i++
          }
        }
        return sb.toString()
      }
      return null
    }
    idx = json.indexOf(key, idx + 1)
  }
  return null
}
