/*
 * BeaconTrigger - fixed contract for the watch -> phone demo trigger.
 *
 * Watch track (Beacon Wear) sends a Data Layer message on PATH_TRIGGER;
 * this phone track receives it in DemoTriggerListenerService. The contract
 * is deliberately narrow (coordinator-fixed, Oct 30):
 *
 *   - Path: /beacon/demo_trigger, payload = UTF-8 bytes of the preset
 *     question. If the payload is empty, the preset question is used.
 *   - Error path: /beacon/demo_trigger_error, payload = UTF-8 text. The
 *     no-session case sends exactly ERROR_NO_SESSION.
 *
 * SCOPE (Oct 30 ~4 PM, binding): preset question ONLY. No voice input and
 * no custom typed question enter the trigger path. The only producer of a
 * trigger message is the watch's fixed trigger button, which sends exactly
 * the preset question below; the phone's typed-field and speech-recogniser
 * UI (BeaconAskScreen) is untouched by this file. resolveTriggerQuestion()
 * is pure (no Android APIs) so it is unit-testable on the JVM.
 *
 * No API key is used, stored, or transmitted anywhere in this file.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.beacon

/** Data Layer path the watch sends a trigger on. */
const val PATH_DEMO_TRIGGER = "/beacon/demo_trigger"

/** Data Layer path the phone replies on when a trigger cannot run. */
const val PATH_DEMO_TRIGGER_ERROR = "/beacon/demo_trigger_error"

/** Data Layer path the watch sends a one-tap fresh-frame request on (v2.6-onetap watch track). */
const val PATH_ONE_TAP = "/beacon/one_tap"

/** Error text when the glasses camera permission is not already granted (one-tap never redirects). */
const val ERROR_CAMERA_PERMISSION =
    "Camera permission not granted on phone — allow camera in the Meta AI app, then try again"

/** Error text when a second one-tap arrives while one is already running. */
const val ERROR_ONE_TAP_BUSY = "One-tap already running — try again in a few seconds"

/** The one preset question the trigger path ever asks. */
const val PRESET_QUESTION =
    "What am I looking at? What is wrong with it, and how do I fix it?"

/**
 * Exact error text sent on PATH_DEMO_TRIGGER_ERROR when no glasses frame
 * is held (no session/frame active on the phone).
 */
const val ERROR_NO_SESSION = "No glasses session — start session on phone first"

/**
 * Resolves the question for a trigger message. Empty / blank / missing
 * payload falls back to the preset question exactly; a non-empty payload
 * is decoded as UTF-8 and trimmed (per contract it IS the preset question,
 * sent by the watch's fixed trigger button — never a typed/voice input).
 */
fun resolveTriggerQuestion(payload: ByteArray?): String {
  if (payload == null || payload.isEmpty()) return PRESET_QUESTION
  val text = payload.toString(Charsets.UTF_8).trim()
  return text.ifBlank { PRESET_QUESTION }
}

/**
 * The one-tap question. Deliberately payload-blind: the one-tap path asks
 * the preset question ONLY (no voice, no custom typed question), so the
 * payload — preset bytes or empty, per the watch contract — is never
 * allowed to substitute a different question. Pure, unit-testable.
 */
fun oneTapQuestion(): String = PRESET_QUESTION
