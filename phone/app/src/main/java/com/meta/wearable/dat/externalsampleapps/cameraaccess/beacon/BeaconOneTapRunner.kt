/*
 * BeaconOneTapRunner - backgrounded one-tap fresh-frame flow (v1.2-onetap).
 *
 * Runs on its OWN CoroutineScope (not the listener service's lifecycle):
 * a WearableListenerService may be torn down after onMessageReceived
 * returns, but session + stream + capture + POST /ask take tens of
 * seconds, so the work must not die with the service instance.
 *
 * Flow, in order, on ONE "/beacon/one_tap" message (preset question only):
 *   1. If the controller already has a session/stream (foreground UI),
 *      reuse it: capture from it and leave it running. Otherwise start
 *      a session, await STARTED, check camera permission (query only —
 *      never a Meta AI redirect from a background service), start the
 *      stream, await STREAMING, capture a FRESH photo, then stop the
 *      stream and end the session in a finally block. Only the pieces
 *      this run started are torn down.
 *   2. Store the fresh frame in BeaconFrameStore (so the old re-ask path
 *      and the foreground Ask flow can reuse it) and POST /ask with it
 *      via BackendClient (same client + JPEG downscale as foreground).
 *   3. On success, fire the EXISTING relay paths exactly as the
 *      foreground flow does: DemoAnswerRelay.notifyAnswer (phone
 *      notification, bridged to the watch by Wear OS) +
 *      DemoAnswerWearSender.sendToWatch (/beacon/demo_answer Data Layer)
 *      + the package-scoped broadcast. No toast here: there is no
 *      foreground UI to toast from, and the watch is the audience.
 *   4. On any failure, send a short error on /beacon/demo_trigger_error
 *      (the watch's shared error path for trigger and one-tap) so the
 *      watch shows it instead of hanging. Never pop phone UI, dialogs,
 *      or a Meta AI redirect from this background path.
 *
 * Concurrency: a Mutex tryLock — a second one-tap while one runs gets
 * ERROR_ONE_TAP_BUSY immediately instead of queueing a second session
 * (DAT allows one session per device at a time). A partial wake lock is
 * held for the run because the foreground service start is best-effort
 * from background (see BeaconGlassesController) and must not be the
 * thing keeping the CPU awake for a seconds-long capture.
 *
 * No API key is used, stored, or transmitted anywhere in this file.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.PermissionStatus
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import us.ninjatek.beacon.DemoAnswerRelay
import us.ninjatek.beacon.DemoAnswerWearSender

object BeaconOneTapRunner {
  private const val TAG = "BeaconOneTapRunner"

  private const val SESSION_TIMEOUT_MS = 30_000L
  private const val STREAM_TIMEOUT_MS = 30_000L
  private const val CAPTURE_TIMEOUT_MS = 30_000L
  private const val SESSION_STOP_TIMEOUT_MS = 5_000L
  private const val WAKELOCK_TIMEOUT_MS = 5 * 60 * 1000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()

  /**
   * Handles one one-tap message. [sourceNodeId] is the watch node the
   * message came from (used for error replies); may be null, in which
   * case errors go to all connected nodes.
   */
  fun handleOneTap(context: Context, sourceNodeId: String?, payload: ByteArray?) {
    val appContext = context.applicationContext
    if (!mutex.tryLock()) {
      Log.i(TAG, "one-tap while one already running — sending busy error")
      BeaconTriggerRunner.sendError(appContext, sourceNodeId, ERROR_ONE_TAP_BUSY)
      return
    }
    scope.launch {
      try {
        runOneTap(appContext, sourceNodeId)
      } catch (e: Exception) {
        Log.w(TAG, "one-tap failed", e)
        BeaconTriggerRunner.sendError(
            appContext, sourceNodeId,
            "One-tap failed: ${e.message ?: e.javaClass.simpleName}")
      } finally {
        mutex.unlock()
      }
    }
  }

  private suspend fun runOneTap(appContext: Context, sourceNodeId: String?) {
    val controller = BeaconGlassesController
    val question = oneTapQuestion()
    controller.oneTapRunning = true
    val wakeLock = acquireWakeLock(appContext)
    var startedSession = false
    var startedStream = false
    try {
      // Reuse a live foreground session/stream when one exists; only
      // start (and later tear down) what is missing.
      val hadSession = controller.hasSession
      if (!hadSession) {
        controller.startSession()
        val started = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
          controller.sessionState.first {
            it == DeviceSessionState.STARTED
          }
          true
        } ?: false
        if (!started) {
          val detail = controller.lastError ?: "glasses session did not reach STARTED"
          BeaconTriggerRunner.sendError(appContext, sourceNodeId, "Glasses session failed: $detail")
          return
        }
        startedSession = true
      }
      if (!controller.isStreaming) {
        val status = controller.checkCameraPermission()
        if (status != PermissionStatus.Granted) {
          BeaconTriggerRunner.sendError(
              appContext, sourceNodeId,
              if (status == null) {
                "Camera permission check failed: ${controller.lastError ?: "unknown error"}"
              } else {
                ERROR_CAMERA_PERMISSION
              })
          return
        }
        controller.beginStream(background = true)
        val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
          controller.streamState.first {
            it == StreamState.STREAMING
          }
          true
        } ?: false
        if (!streaming) {
          val detail = controller.lastError ?: "glasses stream did not reach STREAMING"
          BeaconTriggerRunner.sendError(appContext, sourceNodeId, "Glasses stream failed: $detail")
          return
        }
        startedStream = true
      }
      val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
        controller.capturePhotoBitmap()
      }
      if (bitmap == null) {
        val detail = controller.lastError ?: "capture returned no photo"
        BeaconTriggerRunner.sendError(appContext, sourceNodeId, "Capture failed: $detail")
        return
      }
      // Fresh frame: publish for the old re-ask path / foreground Ask, then ask.
      BeaconFrameStore.set(bitmap)
      val backendUrl = BeaconViewModel.resolveBackendUrl(appContext)
      if (backendUrl.isBlank()) {
        BeaconTriggerRunner.sendError(appContext, sourceNodeId, "Ask failed: no backend URL set on phone")
        return
      }
      Log.i(TAG, "one-tap: asking backend at $backendUrl (${bitmap.width}x${bitmap.height} frame)")
      val result = runCatching {
        BackendClient(backendUrl).askWithBitmap(bitmap, question)
      }
      result.onSuccess { askResult ->
        val at = Instant.now().toString()
        DemoAnswerRelay.notifyAnswer(appContext, question, askResult.answer, at)
        DemoAnswerWearSender.sendToWatch(appContext, question, askResult.answer, at)
        val intent = Intent(BeaconViewModel.ACTION_ANSWER).apply {
          setPackage(BeaconViewModel.WATCH_PACKAGE)
          putExtra("question", question)
          putExtra("answer", askResult.answer)
          putExtra("at", System.currentTimeMillis())
        }
        appContext.sendBroadcast(intent)
        Log.i(TAG, "one-tap: answer relayed, answerChars=${askResult.answer.length}")
      }.onFailure { e ->
        Log.w(TAG, "one-tap: ask failed", e)
        BeaconTriggerRunner.sendError(appContext, sourceNodeId,
            "Ask failed: ${e.message ?: e.javaClass.simpleName}")
      }
    } finally {
      // Tear down only what this run started; a reused foreground
      // session/stream is left exactly as it was found.
      try {
        if (startedStream) {
          controller.stopStreaming()
          controller.clearStreamResources()
        }
        if (startedSession) {
          controller.endSession()
          withTimeoutOrNull(SESSION_STOP_TIMEOUT_MS) {
            controller.sessionState.first {
              it == DeviceSessionState.STOPPED ||
                  it == DeviceSessionState.IDLE
            }
          }
          // If STOPPED never arrived, still clear stream resources so a
          // half-torn-down stream cannot block the next addCamera().
          controller.clearStreamResources()
        }
      } catch (e: Exception) {
        Log.w(TAG, "one-tap cleanup failed", e)
      } finally {
        controller.oneTapRunning = false
        releaseWakeLock(wakeLock)
      }
    }
  }

  private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
    return try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeaconGlasses:OneTap").apply {
        acquire(WAKELOCK_TIMEOUT_MS)
      }
    } catch (e: Exception) {
      Log.w(TAG, "one-tap wake lock acquire failed (continuing)", e)
      null
    }
  }

  private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
    try {
      if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
    } catch (e: Exception) {
      Log.w(TAG, "one-tap wake lock release failed", e)
    }
  }

}
