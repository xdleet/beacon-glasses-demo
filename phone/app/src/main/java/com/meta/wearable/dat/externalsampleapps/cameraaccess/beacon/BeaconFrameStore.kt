/*
 * BeaconFrameStore - process-wide holder for the last captured glasses frame.
 *
 * WHY THIS EXISTS: the DAT capture path is UI-bound. CameraViewModel owns
 * the DeviceSession/stream (activity-scoped), a photo capture lands in
 * CapturePreviewScreen, and only then does BeaconViewModel.setGlassesFrame()
 * receive the bitmap. A WearableListenerService has no Activity and no
 * ViewModel, so it cannot drive a fresh DAT capture or read the
 * activity-scoped BeaconViewModel — and it must not try: starting a second
 * session from a background service would fight the foreground session.
 *
 * So the trigger path reuses the frame the user already captured in the
 * live session: setGlassesFrame() stores it here as well as in UI state,
 * and DemoTriggerListenerService reads it from here. Gallery picks and the
 * bundled sample frame are deliberately NOT stored — they are not a
 * glasses session, so a trigger with only those must take the error path
 * ("No glasses session — start session on phone first") rather than ask
 * about a non-glasses image and pretend otherwise.
 *
 * LIFETIME CAVEAT: this is in-memory only. If the companion process dies
 * (swiped away / reclaimed while backgrounded), the frame is gone and the
 * next trigger takes the error path until the user captures again on the
 * phone. That is the correct failure mode, not a silent no-op.
 *
 * THREADING: guarded by a lock; get() returns the stored reference.
 * BackendClient.askWithBitmap() never recycles its source bitmap (it only
 * recycles a downscaled copy), so the stored bitmap stays valid for the
 * UI's pendingBitmap display as well.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.graphics.Bitmap
import android.util.Log

object BeaconFrameStore {
  private const val TAG = "BeaconFrameStore"

  @Volatile
  private var frame: Bitmap? = null

  @Volatile
  private var frameAtMs: Long = 0L

  /** Stores a freshly captured glasses frame (called from setGlassesFrame). */
  fun set(bitmap: Bitmap) {
    synchronized(this) {
      frame = bitmap
      frameAtMs = System.currentTimeMillis()
    }
    Log.i(TAG, "glasses frame stored ${bitmap.width}x${bitmap.height}")
  }

  /** Returns the last stored glasses frame, or null when none is held. */
  fun get(): Bitmap? = synchronized(this) { frame }

  /** True when a glasses frame is currently held. */
  fun hasFrame(): Boolean = synchronized(this) { frame != null }

  /** Clears the held frame (called from BeaconViewModel.clearFrame). */
  fun clear() {
    synchronized(this) {
      frame = null
      frameAtMs = 0L
    }
  }

  /** Epoch millis the held frame was stored, 0 when none is held. */
  fun storedAtMs(): Long = frameAtMs
}
