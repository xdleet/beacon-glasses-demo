/*
 * BeaconViewModel - companion-app state for the Ask-Muse flow.
 *
 * Owns NOTHING about the DAT session/stream (CameraViewModel does that). It
 * owns: the backend URL (editable, persisted), the question text, the in-flight
 * ask, and the last answer + SAM context + /latest snapshot.
 *
 * Frame sources, in priority order:
 *  1. A just-captured glasses photo (CapturePreviewScreen hands its bitmap in).
 *  2. An image the user picks from the gallery — this is the OBS/RTMP fallback:
 *     save a frame from the MetaLensAI OBS stream, pick it here, ask about it.
 *  3. The bundled sample asset (plant.png) — no-glasses smoke test / demo.
 *
 * Watch relay (wired Oct 30): on every Ask success the answer goes out on
 * BOTH live paths — (1) a phone notification via DemoAnswerRelay, which
 * Wear OS bridges to the watch automatically (the proven path), and
 * (2) a Data Layer message on /beacon/demo_answer via DemoAnswerWearSender,
 * which Beacon Wear v2.4's DemoListenerService receives for its in-app
 * full-screen card. The old package-scoped broadcast is kept as a third,
 * harmless path. The backend's GET /latest remains readable as well.
 *
 * Watch trigger (wired Oct 30, v1.1-trigger): DemoTriggerListenerService
 * receives /beacon/demo_trigger while backgrounded and reuses this flow
 * without a ViewModel — it reads the last glasses frame from
 * BeaconFrameStore (populated by setGlassesFrame below), the backend URL
 * via resolveBackendUrl below, and the same BackendClient + relay calls.
 * The trigger path asks the preset question ONLY (no voice, no typed
 * question); see BeaconTrigger.kt for the fixed contract.
 * No API key is ever stored, logged, or transmitted by this ViewModel.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import us.ninjatek.beacon.DemoAnswerRelay
import us.ninjatek.beacon.DemoAnswerWearSender
import java.time.Instant
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BeaconUiState(
    val backendUrl: String = "",
    val healthOk: Boolean? = null, // null = not checked yet
    val healthChecking: Boolean = false,
    val question: String = DEFAULT_QUESTION,
    val pendingBitmap: Bitmap? = null,
    val frameSource: String = "none", // glasses | gallery | sample | none
    val isAsking: Boolean = false,
    val answer: String? = null,
    val sam: String? = null,
    val latestQuestion: String? = null,
    val latestAnswer: String? = null,
    val latestAt: String? = null,
    val error: String? = null,
) {
  companion object {
    const val DEFAULT_QUESTION =
        "What am I looking at? What is wrong with it, and how do I fix it?"
  }
}

class BeaconViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "BeaconViewModel"
    private const val PREFS = "beacon_glasses"
    private const val KEY_BACKEND_URL = "backend_url"

    /** Broadcast the watch app listens for; package-scoped, answer text only. */
    const val ACTION_ANSWER = "us.ninjatek.beacon.ACTION_ANSWER"

    /** Package of the Beacon Wear OS app (wrist display). */
    const val WATCH_PACKAGE = "us.ninjatek.beacon"

    /**
     * Backend URL the phone actually uses, for callers with no ViewModel
     * (the background trigger runner): the user-set value from
     * SharedPreferences "beacon_glasses" / "backend_url" when present,
     * else the baked-in BuildConfig default. Keeping this here — next to
     * the prefs key — is what stops the key from drifting between the
     * foreground flow and the trigger flow. Survives an update install:
     * the app never clears this prefs file, and the key is unchanged.
     */
    fun resolveBackendUrl(context: Context): String {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      return prefs.getString(KEY_BACKEND_URL, null)
          ?.trim()?.trimEnd('/')?.ifBlank { null }
          ?: BuildConfig.BEACON_BACKEND_URL
    }
  }

  private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private val _uiState = MutableStateFlow(
      BeaconUiState(
          backendUrl = prefs.getString(KEY_BACKEND_URL, null)
              ?: BuildConfig.BEACON_BACKEND_URL,
      ),
  )
  val uiState: StateFlow<BeaconUiState> = _uiState.asStateFlow()

  fun setBackendUrl(url: String) {
    val clean = url.trim().trimEnd('/')
    _uiState.update { it.copy(backendUrl = clean, healthOk = null) }
    prefs.edit().putString(KEY_BACKEND_URL, clean).apply()
  }

  fun setQuestion(question: String) {
    _uiState.update { it.copy(question = question) }
  }

  /** Frame handed in from the glasses capture preview. */
  fun setGlassesFrame(bitmap: Bitmap) {
    // Also publish to the process-wide store so the backgrounded watch
    // trigger can reuse this frame without a ViewModel (gallery/sample
    // frames are deliberately NOT stored — see BeaconFrameStore).
    BeaconFrameStore.set(bitmap)
    _uiState.update {
      it.copy(pendingBitmap = bitmap, frameSource = "glasses", error = null)
    }
  }

  /** Frame picked from the gallery (incl. an OBS/RTMP snapshot). */
  fun setGalleryFrame(bitmap: Bitmap) {
    _uiState.update {
      it.copy(pendingBitmap = bitmap, frameSource = "gallery", error = null)
    }
  }

  /** Bundled sample asset — the no-glasses smoke-test frame. */
  fun useSampleFrame() {
    viewModelScope.launch(Dispatchers.Default) {
      val bitmap = try {
        getApplication<Application>().assets.open("plant.png").use { input ->
          BitmapFactory.decodeStream(input)
        }
      } catch (e: Exception) {
        Log.w(TAG, "sample asset failed", e)
        null
      }
      withContext(Dispatchers.Main) {
        if (bitmap != null) {
          _uiState.update {
            it.copy(pendingBitmap = bitmap, frameSource = "sample", error = null)
          }
        } else {
          _uiState.update { it.copy(error = "Sample frame is missing from the app assets.") }
        }
      }
    }
  }

  fun clearFrame() {
    BeaconFrameStore.clear()
    _uiState.update { it.copy(pendingBitmap = null, frameSource = "none") }
  }

  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  fun checkHealth() {
    val url = _uiState.value.backendUrl
    if (url.isBlank()) {
      _uiState.update { it.copy(error = "Enter the backend URL first.") }
      return
    }
    _uiState.update { it.copy(healthChecking = true, error = null) }
    viewModelScope.launch(Dispatchers.IO) {
      val ok = try {
        BackendClient(url).checkHealth()
      } catch (e: Exception) {
        Log.w(TAG, "health check error", e)
        false
      }
      withContext(Dispatchers.Main) {
        _uiState.update {
          it.copy(healthChecking = false, healthOk = ok,
              error = if (ok) null else "Backend not reachable at $url — is it running on port 8901?")
        }
      }
    }
  }

  /** Sends the pending frame + question to POST /ask. No-op without a frame. */
  fun ask() {
    val state = _uiState.value
    val bitmap = state.pendingBitmap
    if (bitmap == null) {
      _uiState.update {
        it.copy(error = "No frame yet — capture from the glasses, pick an image, or use the sample frame.")
      }
      return
    }
    if (state.backendUrl.isBlank()) {
      _uiState.update { it.copy(error = "Enter the backend URL first.") }
      return
    }
    val question = state.question.ifBlank { BeaconUiState.DEFAULT_QUESTION }
    _uiState.update { it.copy(isAsking = true, error = null, answer = null, sam = null) }
    viewModelScope.launch(Dispatchers.IO) {
      val result = runCatching {
        BackendClient(state.backendUrl).askWithBitmap(bitmap, question)
      }
      withContext(Dispatchers.Main) {
        result.onSuccess { askResult ->
          _uiState.update {
            it.copy(isAsking = false, answer = askResult.answer, sam = askResult.sam)
          }
          sendToWatch(question, askResult.answer)
        }.onFailure { e ->
          Log.w(TAG, "ask failed", e)
          _uiState.update {
            it.copy(isAsking = false,
                error = "Ask failed: ${e.message ?: e.javaClass.simpleName}. Check the backend URL and that the backend is up.")
          }
        }
      }
    }
  }

  /** Pulls GET /latest (what the watch display can also read). */
  fun refreshLatest() {
    val url = _uiState.value.backendUrl
    if (url.isBlank()) return
    viewModelScope.launch(Dispatchers.IO) {
      val latest = runCatching { BackendClient(url).fetchLatest() }.getOrNull() ?: return@launch
      withContext(Dispatchers.Main) {
        _uiState.update {
          it.copy(
              latestQuestion = latest.question,
              latestAnswer = latest.answer,
              latestAt = latest.at,
          )
        }
      }
    }
  }

  /** Re-broadcasts the last question/answer toward the watch, if there is one. */
  fun resendToWatch() {
    val state = _uiState.value
    val answer = state.answer ?: state.latestAnswer ?: return
    sendToWatch(state.question, answer)
  }

  /**
   * Watch relay, all live paths, every time — either one alone delivers the
   * answer on camera:
   *  1. Notification via DemoAnswerRelay (primary, proven): Wear OS bridges
   *     phone notifications to the watch automatically. Relay now sets its
   *     own small icon (app launcher icon) — the shipped-relay bug where a
   *     missing icon drops the notification is fixed in the ported copy.
   *  2. Data Layer via DemoAnswerWearSender: {"question","answer","at"} on
   *     /beacon/demo_answer, received by Beacon Wear v2.4's
   *     DemoListenerService for the full-screen card.
   *  3. The original package-scoped broadcast (kept: harmless, and a
   *     fallback if anything ever listens for it again).
   * Plus the backend's GET /latest, refreshed separately. No path carries
   * anything but question/answer text — no keys, no private data.
   * If POST_NOTIFICATIONS is not granted (Android 13+), notifyAnswer()
   * returns false and the toast says so plainly instead of pretending
   * the watch got it; the Data Layer send still fires.
   */
  private fun sendToWatch(question: String, answer: String) {
    val context = getApplication<Application>()
    val at = Instant.now().toString()
    val notified = DemoAnswerRelay.notifyAnswer(context, question, answer, at)
    DemoAnswerWearSender.sendToWatch(context, question, answer, at)
    val intent = Intent(ACTION_ANSWER).apply {
      setPackage(WATCH_PACKAGE)
      putExtra("question", question)
      putExtra("answer", answer)
      putExtra("at", System.currentTimeMillis())
    }
    context.sendBroadcast(intent)
    val notifNote = if (notified) {
      "watch notification posted + Data Layer message sent"
    } else {
      "watch notification BLOCKED — grant Notifications for this app, then " +
          "\"Send to watch again\" (Data Layer message still sent)"
    }
    Toast.makeText(
        context,
        "Answer sent to watch: $notifNote. Also on backend /latest.",
        Toast.LENGTH_LONG,
    ).show()
    Log.i(TAG, "sendToWatch: notified=$notified, answerChars=${answer.length}")
  }

  /** True when the phone may post the relay notification (pre-13: always). */
  fun canPostNotifications(): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        getApplication(),
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(BeaconViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return BeaconViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
