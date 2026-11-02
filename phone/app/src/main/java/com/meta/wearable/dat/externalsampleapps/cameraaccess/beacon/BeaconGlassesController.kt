/*
 * BeaconGlassesController - Application-owned glasses session/stream owner.
 *
 * SPIKE RESULT (Oct 30, verified in code + DAT docs, NOT on live glasses):
 * every step of the DAT lifecycle is Activity-free — Wearables.initialize
 * takes a Context (docs prescribe Application.onCreate), createSession
 * takes only a DeviceSelector, checkPermissionStatus is a query with no
 * redirect, addCamera/start take no Surface (the preview decoder in
 * CameraViewModel is only created when a Surface exists, so a headless
 * stream still captures), and capturePhoto is gated only on the stream
 * being STREAMING. The one step that is NOT guaranteed from a background
 * service is StreamingService.start(): Android 12+ throws
 * ForegroundServiceStartNotAllowedException for background FGS starts
 * unless an exemption holds, and a Wear Data Layer message is not one.
 * So the background path treats the FGS as best-effort (attempt, swallow
 * the failure, continue) and holds a partial wake lock in the runner
 * instead; a one-tap capture lasts seconds, not a recording session.
 * The foreground path keeps the original direct FGS start.
 *
 * OWNERSHIP: this singleton owns the DeviceSession, its Camera, and its
 * Stream. CameraViewModel delegates all lifecycle/capture calls here, so
 * there is exactly one session owner in the process — the DAT rule is
 * one session per device at a time, and a second session started while
 * the foreground session is live would fight it. The one-tap runner
 * therefore reuses a live controller session/stream when one exists
 * (capture only, no teardown) and owns + tears down only the session /
 * stream it started itself.
 *
 * STATE: session/stream states are StateFlows mirrored by CameraViewModel
 * into its UI state, so the foreground screen is unchanged. Stream
 * terminal handling (recording finalization, audio release, csd reset)
 * stays in CameraViewModel, which collects streamState; this controller
 * deliberately does NOT auto-clear on stream terminal, so recording
 * finalization is never raced. The one-tap path has no ViewModel, so it
 * cleans up explicitly in its finally block.
 *
 * No API key is used, stored, or transmitted anywhere in this file.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingService
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BeaconGlassesController {

  private const val TAG = "BeaconGlassesController"
  private const val FRAME_RATE = 24

  @Volatile private var app: Application? = null

  /** Idempotent; called from BeaconApplication.onCreate and, as a backup, CameraViewModel init. */
  fun attach(application: Application) {
    if (app == null) app = application
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  // 1.2.1 crash fix: this was eager — constructing AutoDeviceSelector() at
  // object-load time ran before Wearables.initialize and outside any
  // try/catch, so a fresh install died in Application.onCreate before any
  // frame (splash, then "keeps stopping"). Lazy, matching
  // WearablesViewModel, so the selector is only built on first session
  // start, after init and permissions.
  private val deviceSelector: com.meta.wearable.dat.core.selectors.DeviceSelector by lazy {
    AutoDeviceSelector()
  }

  private val _sessionState = MutableStateFlow(DeviceSessionState.IDLE)
  val sessionState: StateFlow<DeviceSessionState> = _sessionState.asStateFlow()

  private val _streamState = MutableStateFlow(StreamState.STOPPED)
  val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

  /** One-shot SDK error strings for the foreground screen (snackbar). Capture failures do NOT go here. */
  private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
  val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

  /** Last failure text (capture path + permission check); null when the last such call succeeded. */
  @Volatile var lastError: String? = null
    private set

  /** True while a one-tap run is in flight; CameraViewModel.onCleared must not tear the session down then. */
  @Volatile var oneTapRunning: Boolean = false

  /**
   * Preview/recording frame sink, set by CameraViewModel while it is alive.
   * Null on the background path: capturePhoto does not need video frames.
   * Invoked on a single-threaded dispatcher, in stream order, exactly like
   * the original CameraViewModel video collector.
   */
  @Volatile var frameListener: ((VideoFrame) -> Unit)? = null

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  @Volatile private var isCapturing = false

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  /** Mirrors CameraUiState.hasSession: a session exists and is not yet stopped. */
  val hasSession: Boolean
    get() = _sessionState.value == DeviceSessionState.STARTING ||
        _sessionState.value == DeviceSessionState.STARTED ||
        _sessionState.value == DeviceSessionState.PAUSED ||
        _sessionState.value == DeviceSessionState.STOPPING

  val isSessionActive: Boolean
    get() = _sessionState.value == DeviceSessionState.STARTED

  /** Mirrors CameraUiState.hasStream: a stream is attached and not terminal. */
  val hasStream: Boolean
    get() = _streamState.value != StreamState.STOPPED && _streamState.value != StreamState.CLOSED

  val isStreaming: Boolean
    get() = _streamState.value == StreamState.STREAMING

  // MARK: - Session

  /** Creates and starts a DeviceSession (no stream yet). No-op when one already exists. */
  fun startSession() {
    if (hasSession) return
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _sessionState.value = DeviceSessionState.STARTING
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to start session: ${error.description}")
          val msg = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
          lastError = msg
          _errorEvents.tryEmit(msg)
          cleanupSession()
        }
  }

  /**
   * Ends the device session. Stream teardown is NOT done here directly —
   * stopping the session drives the SDK's stream to a terminal state, and
   * the foreground collector (or the one-tap finally block) clears stream
   * resources from there, matching the original CameraViewModel split.
   */
  fun endSession() {
    val current = session ?: return
    _sessionState.value = DeviceSessionState.STOPPING
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = scope.launch {
      session.state.collect { state ->
        _sessionState.value = state
        if (state == DeviceSessionState.STOPPED) {
          cleanupSession()
        }
      }
    }
    sessionErrorJob = scope.launch {
      session.errors.collect { error ->
        Log.e(TAG, "Session error: ${error.description}")
        val msg = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
        lastError = msg
        _errorEvents.tryEmit(msg)
      }
    }
  }

  private fun cleanupSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
  }

  // MARK: - Permission (query only — never a redirect from here)

  /**
   * Camera permission status, queried only. A non-Granted result must NEVER
   * trigger a permission request from a background service (the request
   * redirects to the Meta AI app); callers send the watch an error instead.
   * Returns null on a query failure, with [lastError] set.
   */
  suspend fun checkCameraPermission(): PermissionStatus? {
    var result: PermissionStatus? = null
    var failure: String? = null
    try {
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status -> result = status }
          .onFailure { error, _ -> failure = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description }
    } catch (e: Exception) {
      failure = e.message ?: e.javaClass.simpleName
    }
    lastError = failure
    return result
  }

  // MARK: - Stream

  /**
   * Starts the camera stream (preview). Requires an active session and a
   * previously-granted camera permission — this function does NOT check
   * permission itself. [background] selects best-effort FGS start (the
   * one-tap path, where a background FGS start may be refused by the
   * system) vs the original direct start (foreground path).
   */
  fun beginStream(background: Boolean) {
    val current = session ?: return
    if (stream != null) return
    if (background) {
      try {
        app?.let { StreamingService.start(it) }
      } catch (e: Exception) {
        // Background FGS start refused (Android 12+): capture continues
        // without it — the runner holds a wake lock for the seconds this
        // takes. Never fail the capture on the FGS alone.
        Log.w(TAG, "background FGS start failed, continuing without it", e)
      }
    } else {
      app?.let { StreamingService.start(it) }
    }
    current
        .addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = FRAME_RATE,
                compressVideo = true,
            ),
        )
        .onSuccess { addedCamera ->
          camera = addedCamera
          val added = addedCamera.stream
          stream = added
          // Subscribe before start() so no initial transitions are missed.
          setupStreamListeners(added)
          _streamState.value = StreamState.STARTING
          added.start().onFailure { error, _ ->
            Log.e(TAG, "Failed to start stream: ${error.description}")
            val msg = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
            lastError = msg
            _errorEvents.tryEmit(msg)
            // A failed start leaves the stream attached and the FGS running
            // — tear both down so state doesn't stick at STARTING.
            clearStreamResources()
          }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to add camera: ${error.description}")
          val msg = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
          lastError = msg
          _errorEvents.tryEmit(msg)
          stopStreamingService()
        }
  }

  /** Stops the camera stream but keeps the [DeviceSession] connected. */
  fun stopStreaming() {
    val current = camera ?: return
    _streamState.value = StreamState.STOPPING
    current.stop()
    // The stream-state collector converges teardown when STOPPED/CLOSED arrives.
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob = scope.launch(frameDispatcher) {
      stream.videoStream.collect { frame ->
        frameListener?.invoke(frame)
      }
    }
    streamStateJob = scope.launch {
      stream.state.collect { state ->
        _streamState.value = state
      }
    }
    streamErrorJob = scope.launch {
      stream.errorStream.collect { error ->
        Log.e(TAG, "Stream error: ${error.description}")
        val msg = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
        lastError = msg
        _errorEvents.tryEmit(msg)
      }
    }
  }

  /**
   * Releases stream resources (jobs, FGS, camera). Idempotent; safe to call
   * from both the foreground terminal path and the one-tap finally block.
   * Does NOT reset the foreground ViewModel's decoder/csd state — that
   * stays with CameraViewModel, as before.
   */
  fun clearStreamResources() {
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    stopStreamingService()
    // STOPPED is restartable, so only stop() detaches the capability; without
    // it the next addCamera() fails with "a capability of this type is
    // already active". Stopping the camera cascades to its stream child.
    stopCamera(camera)
    camera = null
    stream = null
    _streamState.value = StreamState.STOPPED
  }

  private fun stopStreamingService() {
    val ctx = app ?: return
    try {
      // Original path: route stop through onStartCommand so a pending
      // start's foreground promise is satisfied before stopping.
      StreamingService.stop(ctx)
    } catch (e: Exception) {
      // Background stop-via-start can be refused exactly like a start;
      // fall back to a plain stopService, which has no such restriction.
      Log.w(TAG, "FGS stop-via-start failed, falling back to stopService", e)
      try {
        ctx.stopService(Intent(ctx, StreamingService::class.java))
      } catch (e2: Exception) {
        Log.w(TAG, "FGS stopService fallback failed", e2)
      }
    }
  }

  /**
   * Stops the camera by closing it. Accepting a [java.io.Closeable] parameter satisfies the
   * AutoCloseableUse detector, which skips methods that take an AutoCloseable argument — the camera
   * outlives any single `use {}` block, so it is stopped explicitly here rather than auto-closed.
   */
  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // MARK: - Capture

  /**
   * Captures one photo from the live stream and decodes it (with EXIF
   * rotation, as the foreground path did). Returns null when not
   * streaming, when a capture is already in flight, on SDK failure, or
   * when decoding fails; [lastError] carries the failure text. The
   * decoded bitmap is the caller's to use (and to store in
   * BeaconFrameStore); this function never recycles it.
   */
  suspend fun capturePhotoBitmap(): Bitmap? {
    if (isCapturing || !isStreaming) return null
    val currentStream = stream ?: return null
    lastError = null
    isCapturing = true
    try {
      // NOTE: capturePhoto() is a SUSPEND function in DAT 1.0.1, so it is
      // called directly here (not bridged via suspendCancellableCoroutine,
      // whose block is not a coroutine body). Its DatResult callbacks fire
      // before the suspend call returns, so capturing into locals is safe
      // — the same pattern as checkCameraPermission() above.
      var photoData: PhotoData? = null
      var captureFailed = false
      try {
        currentStream.capturePhoto()
            ?.onSuccess { data -> photoData = data }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Failed to capture photo: ${error.description}")
              lastError = app?.let { ctx -> error.getLocalizedDescription(ctx) } ?: error.description
              captureFailed = true
            }
            ?: run { captureFailed = true }
      } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        captureFailed = true
      }
      if (captureFailed || photoData == null) return null
      val captured: PhotoData = photoData
      val bitmap = withContext(Dispatchers.Default) { decodePhoto(captured) }
      if (bitmap == null) lastError = "Failed to decode captured photo"
      return bitmap
    } finally {
      isCapturing = false
    }
  }

  // MARK: - Photo decoding (moved verbatim from CameraViewModel)

  private fun decodePhoto(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
      }

  // The glasses store orientation in an EXIF tag that BitmapFactory/ImageDecoder don't apply for
  // HEIC, so read TAG_ORIENTATION and rotate — otherwise the preview and shared image are sideways.
  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      Log.e(TAG, "Failed to decode captured photo")
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    if (matrix.isIdentity) return bitmap

    // Rotating allocates a second full-size bitmap; recycle the source, and fall back to the
    // unrotated image if the device is too low on memory to make the copy.
    return try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
      }
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to rotate captured photo", e)
      bitmap
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
        try {
          ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read EXIF orientation", e)
          ExifInterface.ORIENTATION_NORMAL
        }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

}
