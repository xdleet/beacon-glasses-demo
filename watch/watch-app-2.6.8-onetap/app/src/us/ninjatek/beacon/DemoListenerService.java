package us.ninjatek.beacon;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Data Layer receiver for the hackathon demo (v2.4, fixed v2.6).
 *
 * The companion app on the phone sends each Muse answer to every connected
 * watch node with MessageClient.sendMessage(nodeId, "/beacon/demo_answer",
 * jsonBytes), where json is {"question": "...", "answer": "...", "at": "..."}
 * — the same three fields the backend's GET /latest returns. This service
 * stores the answer and posts a tap-to-view card that opens
 * DemoAnswerActivity full-screen.
 *
 * Why a service, not polling: the watch has no Tailscale and cannot reach
 * the home-box backend directly, so the phone is the middleman. The Data
 * Layer rides the existing phone↔watch Bluetooth link — no watch Wi-Fi,
 * no new network path, no API key on this path at all.
 *
 * ROOT CAUSE of the Oct 30 "notification arrives, Demo screen stays empty"
 * symptom (diagnosed v2.6, code + docs evidence in the manifest comment):
 * Google Play Services routes Data Layer messages ONLY between apps whose
 * package name AND signing certificate both match across devices. This
 * watch app is us.ninjatek.beacon; the phone companion app ships as
 * us.ninjatek.beacon.glasses, so Play Services never delivers
 * /beacon/demo_answer (or the watch→phone trigger) at all — while the
 * phone notification still bridges via Wear OS, which does not check
 * packages. No watch-side code change can fix a package mismatch; the
 * phone track must align its applicationId AND signing cert with this
 * app. What v2.6 DOES fix on the watch, so the in-app path works the
 * moment that alignment lands:
 *   (1) Nothing here fails silently any more: every receipt, drop, and
 *       malformed payload is logged (tag BeaconDemo) for adb logcat,
 *       instead of the pre-2.6 catch (Exception ignored) that left a
 *       dead Demo button with zero evidence.
 *   (2) The duplicate-at filter no longer drops a legitimate answer
 *       that merely reuses a stamp: a delivery is dropped ONLY when its
 *       stamp AND its question AND its answer all match what is stored
 *       (a true re-delivery). A new answer reusing an old stamp is
 *       stored.
 *   (3) A trigger/one-tap failure ("/beacon/demo_trigger_error") no
 *       longer OVERWRITES the last good answer: it is stored in a
 *       separate Prefs slot, so the Demo button still shows the previous
 *       answer after a new Ask fails.
 *
 * Fallbacks, in order: (1) the phone notification relay (companion app
 * posts a notification, Wear OS bridges it to the watch — proven Oct 30);
 * (2) this in-app path; (3) the stored answer via the Demo button, which
 * works even if the notification permission is declined.
 */
public class DemoListenerService extends WearableListenerService {
    private static final String TAG = "BeaconDemo";
    public static final String PATH_DEMO_ANSWER = "/beacon/demo_answer";
    /** v2.5: phone's error reply to a /beacon/demo_trigger tap (and, per
     * the fixed contract, to a /beacon/one_tap tap too — same reply paths
     * for both). Payload is plain UTF-8 text (not JSON), displayed
     * verbatim on the watch. */
    public static final String PATH_DEMO_TRIGGER_ERROR = "/beacon/demo_trigger_error";
    /** Preset question for the v2.5 trigger / v2.6 one-tap (kept in sync
     * with MainActivity). */
    public static final String PRESET_QUESTION =
            "What am I looking at? What is wrong with it, and how do I fix it?";

    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event.getPath();
        Log.i(TAG, "onMessageReceived path=" + path
                + " from=" + event.getSourceNodeId());
        if (PATH_DEMO_TRIGGER_ERROR.equals(path)) {
            onTriggerError(event);
            return;
        }
        if (!PATH_DEMO_ANSWER.equals(path)) {
            Log.i(TAG, "ignoring non-demo path=" + path);
            return;
        }
        byte[] data = event.getData();
        if (data == null || data.length == 0) {
            Log.w(TAG, "dropping empty /beacon/demo_answer payload");
            return;
        }
        try {
            String raw = new String(data, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            String question = o.optString("question", "");
            String answer = o.optString("answer", "");
            String at = o.optString("at", "");
            if (answer.isEmpty()) {
                Log.w(TAG, "dropping /beacon/demo_answer with empty answer");
                return;
            }
            // True re-delivery only: same stamp AND same content. A new
            // answer that happens to reuse a stamp is NOT a duplicate
            // and must be stored (pre-2.6 dropped on stamp alone).
            String lastAt = Prefs.getDemoAt(this);
            if (!at.isEmpty() && at.equals(lastAt)
                    && question.equals(Prefs.getDemoQuestion(this))
                    && answer.equals(Prefs.getDemoAnswer(this))) {
                Log.i(TAG, "dropping exact re-delivery at=" + at);
                return;
            }
            Prefs.setDemoAnswer(this, question, answer, at);
            Log.i(TAG, "stored demo answer at=" + at
                    + " answerChars=" + answer.length());
            broadcastDemoUpdated();
            DemoAnswerActivity.postAnswerCard(this, question, answer);
        } catch (Throwable e) {
            // Never crash the listener — but never swallow silently either:
            // log the malformed payload so the next live test has evidence.
            Log.w(TAG, "malformed /beacon/demo_answer payload dropped", e);
        }
    }

    /**
     * v2.6.2: tell a foreground MainActivity that a new demo result is
     * stored, so it can render it in the MAIN transcript without a Demo
     * button tap. Package-scoped broadcast (only this app receives it);
     * MainActivity re-reads Prefs on receipt, so no answer text rides
     * the broadcast itself. If MainActivity is not foreground, the
     * broadcast is harmlessly missed — its onResume re-check covers
     * that case. Failures to send are logged, never fatal.
     */
    private void broadcastDemoUpdated() {
        try {
            Intent intent = new Intent(MainActivity.ACTION_DEMO_UPDATED);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Log.i(TAG, "broadcast ACTION_DEMO_UPDATED");
        } catch (Throwable e) {
            Log.w(TAG, "broadcast ACTION_DEMO_UPDATED failed", e);
        }
    }

    /**
     * v2.5 trigger-error reply, fixed v2.6: the phone sends the failure as
     * plain UTF-8 text on /beacon/demo_trigger_error. Show it as a Toast
     * on the main thread, and store it in the SEPARATE error slot + post
     * an error card so it stays visible on the Demo answer screen even
     * if the Toast is missed — WITHOUT overwriting the last good answer
     * (pre-2.6 stored the error as the answer, destroying it).
     */
    private void onTriggerError(MessageEvent event) {
        try {
            byte[] data = event.getData();
            final String text = data == null ? ""
                    : new String(data, StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                Log.w(TAG, "dropping empty /beacon/demo_trigger_error payload");
                return;
            }
            Log.i(TAG, "trigger/one-tap error: " + text);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(DemoListenerService.this, text,
                            Toast.LENGTH_LONG).show();
                }
            });
            Prefs.setDemoError(this, text);
            broadcastDemoUpdated();
            DemoAnswerActivity.postErrorCard(this, text);
        } catch (Throwable e) {
            Log.w(TAG, "malformed /beacon/demo_trigger_error payload dropped", e);
        }
    }
}
