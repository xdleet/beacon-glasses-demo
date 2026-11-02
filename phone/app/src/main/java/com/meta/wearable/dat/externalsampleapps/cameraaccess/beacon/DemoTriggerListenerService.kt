/*
 * DemoTriggerListenerService - phone-side watch trigger listener.
 *
 * Receives Data Layer messages while the companion app is backgrounded
 * and runs the Ask flow without any phone UI interaction, so the phone
 * can sit on a desk untouched while the companion app runs in the
 * background.
 *
 * Registered in AndroidManifest.xml with the BIND_LISTENER intent filter
 * (the standard WearableListenerService registration), which is what
 * lets Play Services deliver messages to this app while backgrounded.
 * No new permission is required for this service.
 *
 * Paths (see BeaconTrigger.kt for the fixed contract):
 *   - /beacon/demo_trigger (v1.1): re-ask the LAST captured frame. The
 *     service itself is thin by design: it filters on the path and hands
 *     off to BeaconTriggerRunner, whose own CoroutineScope outlives this
 *     service instance. This service never starts a DAT session, never
 *     touches the camera directly, and never shows UI — see
 *     BeaconFrameStore for why the frame comes from the last capture in
 *     the live session instead.
 *   - /beacon/one_tap (v1.2): capture a FRESH frame now, then ask. Hands
 *     off to BeaconOneTapRunner, which drives BeaconGlassesController
 *     (the Application-owned session/stream owner) through
 *     startSession / startStreaming / capturePhoto / endSession and
 *     then the same backend + relay path. Still never shows UI and
 *     never redirects to Meta AI from a background service.
 *
 * Messages on any other path are ignored. No API key is used, stored, or
 * transmitted anywhere in this file.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class DemoTriggerListenerService : WearableListenerService() {

  companion object {
    private const val TAG = "DemoTriggerListener"
  }

  override fun onMessageReceived(event: MessageEvent) {
    when (event.path) {
      PATH_DEMO_TRIGGER -> {
        Log.i(TAG, "trigger received from node=${event.sourceNodeId} " +
            "payloadBytes=${event.data?.size ?: 0}")
        try {
          BeaconTriggerRunner.handleTrigger(this, event.sourceNodeId, event.data)
        } catch (e: Exception) {
          // A trigger must never crash the service: log and move on. The
          // runner itself also reports failures to the watch on the error path.
          Log.w(TAG, "trigger handling failed", e)
        }
      }
      PATH_ONE_TAP -> {
        Log.i(TAG, "one-tap received from node=${event.sourceNodeId} " +
            "payloadBytes=${event.data?.size ?: 0}")
        try {
          BeaconOneTapRunner.handleOneTap(this, event.sourceNodeId, event.data)
        } catch (e: Exception) {
          // Same rule as the trigger path: never crash the service. The
          // runner itself reports failures to the watch on the error path.
          Log.w(TAG, "one-tap handling failed", e)
        }
      }
      else -> {
        // Not ours (e.g. another Data Layer path) — ignore, do not crash.
        super.onMessageReceived(event)
      }
    }
  }
}
