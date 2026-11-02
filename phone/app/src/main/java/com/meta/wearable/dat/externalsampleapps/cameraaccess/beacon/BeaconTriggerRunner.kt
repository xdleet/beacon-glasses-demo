/*
 * BeaconTriggerRunner - backgrounded trigger work, shared by the listener.
 *
 * Runs on its OWN CoroutineScope (not the listener service's lifecycle):
 * a WearableListenerService may be torn down after onMessageReceived
 * returns, but POST /ask takes seconds, so the work must not die with
 * the service instance. The scope lives in this object and outlives any
 * single service instance; it is cancelled only when the app process dies.
 *
 * Flow (reuses the existing Ask + relay path, no new backend contract):
 *   1. Read the last glasses frame from BeaconFrameStore. None -> send
 *      ERROR_NO_SESSION on /beacon/demo_trigger_error to the source node
 *      (never fail silently, never crash).
 *   2. Read the USER-SET backend URL via BeaconViewModel.resolveBackendUrl
 *      (SharedPreferences "beacon_glasses" / "backend_url", falling back to
 *      the baked-in BuildConfig default). The baked-in default is NOT
 *      changed by this build: the LAN IP can move under DHCP, and the
 *      persisted user setting is what the phone actually uses.
 *   3. POST /ask with the frame + resolved question via BackendClient
 *      (same client + JPEG downscale as the foreground Ask flow).
 *   4. On success, fire the EXISTING relay paths exactly as the
 *      foreground flow does: DemoAnswerRelay.notifyAnswer (phone
 *      notification, bridged to the watch by Wear OS) +
 *      DemoAnswerWearSender.sendToWatch (/beacon/demo_answer Data Layer)
 *      + the package-scoped broadcast. No toast here: there is no
 *      foreground UI to toast from, and the watch is the audience.
 *   5. On Ask failure, send a short error on /beacon/demo_trigger_error
 *      so the watch can show it instead of hanging. (Only the no-session
 *      text is contract-fixed; failure text is free-form and prefixed
 *      "Ask failed: ".)
 *
 * No API key is used, stored, or transmitted anywhere in this file.
 * Sample/demo data only: the frame is the user's own glasses capture.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.ninjatek.beacon.DemoAnswerRelay
import us.ninjatek.beacon.DemoAnswerWearSender

object BeaconTriggerRunner {
  private const val TAG = "BeaconTriggerRunner"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Handles one trigger message. [sourceNodeId] is the watch node the
   * message came from (used for error replies); may be null, in which
   * case errors go to all connected nodes.
   */
  fun handleTrigger(context: Context, sourceNodeId: String?, payload: ByteArray?) {
    val appContext = context.applicationContext
    val question = resolveTriggerQuestion(payload)
    scope.launch {
      val bitmap = BeaconFrameStore.get()
      if (bitmap == null) {
        Log.i(TAG, "trigger with no glasses frame — sending no-session error")
        sendError(appContext, sourceNodeId, ERROR_NO_SESSION)
        return@launch
      }
      val backendUrl = BeaconViewModel.resolveBackendUrl(appContext)
      if (backendUrl.isBlank()) {
        sendError(appContext, sourceNodeId, "Ask failed: no backend URL set on phone")
        return@launch
      }
      Log.i(TAG, "trigger: asking backend at $backendUrl " +
          "(${bitmap.width}x${bitmap.height} frame)")
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
        Log.i(TAG, "trigger: answer relayed, answerChars=${askResult.answer.length}")
      }.onFailure { e ->
        Log.w(TAG, "trigger: ask failed", e)
        sendError(appContext, sourceNodeId,
            "Ask failed: ${e.message ?: e.javaClass.simpleName}")
      }
    }
  }

  /**
   * Shared error reply, also used by the one-tap runner (same watch error
   * path). Sends are now failure-logged instead of silently dropped: a
   * failed error send means the watch hangs with no feedback, so that
   * failure must be visible in logcat.
   */
  internal fun sendError(context: Context, sourceNodeId: String?, text: String) {
    val payload = text.toByteArray(Charsets.UTF_8)
    try {
      val nodeClient = Wearable.getNodeClient(context)
      val messageClient = Wearable.getMessageClient(context)
      if (sourceNodeId != null) {
        messageClient.sendMessage(sourceNodeId, PATH_DEMO_TRIGGER_ERROR, payload)
            .addOnFailureListener { e ->
              Log.w(TAG, "trigger: error reply send failed", e)
            }
      } else {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
              for (node in nodes) {
                messageClient.sendMessage(node.id, PATH_DEMO_TRIGGER_ERROR, payload)
                    .addOnFailureListener { e ->
                      Log.w(TAG, "trigger: error reply send failed for node ${node.id}", e)
                    }
              }
            }
            .addOnFailureListener { e ->
              Log.w(TAG, "trigger: connected-nodes lookup failed for error reply", e)
            }
      }
    } catch (e: Exception) {
      Log.w(TAG, "trigger: error reply failed", e)
    }
  }
}
