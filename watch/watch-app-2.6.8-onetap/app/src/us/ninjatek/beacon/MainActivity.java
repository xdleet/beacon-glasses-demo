package us.ninjatek.beacon;

import android.app.Activity;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.speech.RecognizerIntent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 100;

    /** v2.5 trigger contract (fixed by the coordinator; phone track builds
     * to the same path + payload). Preset question ONLY — no voice input
     * and no typed question on this path. */
    public static final String PATH_DEMO_TRIGGER = "/beacon/demo_trigger";
    /** v2.6 one-tap contract (fixed by the coordinator; phone track builds
     * to this path). Same preset question ONLY, same reply paths as the
     * trigger ("/beacon/demo_answer" on success, "/beacon/demo_trigger_error"
     * on failure). One tap = one motion: the phone starts the session,
     * previews/streams, snaps a fresh picture, sends to Muse, and stops
     * the session after capture; the answer arrives asynchronously. No
     * voice, no custom typed question on this path. Payload = UTF-8 bytes
     * of the preset question (empty also means preset, per contract). */
    public static final String PATH_ONE_TAP = "/beacon/one_tap";
    public static final String PRESET_QUESTION =
            "What am I looking at? What is wrong with it, and how do I fix it?";

    /**
     * v2.6.2: local broadcast sent by DemoListenerService whenever a demo
     * answer (or a demo error) is stored. MainActivity listens while it is
     * foreground so the latest result lands in the MAIN transcript the
     * moment it arrives — no Demo button tap required. Package-scoped, so
     * only this app receives it. The Demo button/screen remain as
     * history/fallback; they are no longer the primary result path.
     */
    public static final String ACTION_DEMO_UPDATED =
            "us.ninjatek.beacon.DEMO_UPDATED";

    private TextView transcript;
    private TextView modelLabel;
    // 2.6.4: version label at the bottom of the main screen (below the
    // brand, outside the drawer). Text comes from BuildConfig, which
    // build.sh generates from the manifest at build time — never a
    // hardcoded version string in code.
    private TextView versionLabel;
    private EditText input;
    private ScrollView scroll;
    private View drawer;
    private boolean drawerHidden = false;
    private final List<ApiClient.Message> history = new ArrayList<ApiClient.Message>();
    /** v2.6.2: dedupe key for the last demo result rendered into the main
     * transcript (at + question + answer + error). Prevents re-appending
     * the same result on every onResume; a new result has a new key. */
    private String lastShownDemoKey = "";
    private BroadcastReceiver demoReceiver = null;
    private static final String TAG = "BeaconMain";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 2.6.3: never let a null/empty exception message render as "null". */
    private static String safeMsg(Throwable e) {
        if (e == null) return "unknown error";
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.toString() : m;
    }
    private static String safeMsg(String m) {
        return (m == null || m.trim().isEmpty()) ? "unknown error" : m;
    }
    /** 2.6.7 verbose error: class + message + cause chain, so the next
     * "Failed resolution" names the exact missing class instead of
     * just its message. Toasts stay short; the transcript gets this. */
    private static String verboseError(Throwable e) {
        if (e == null) return "unknown error";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
        Throwable c = e.getCause();
        int depth = 0;
        while (c != null && depth < 3) {
            sb.append(" | caused by ").append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }
    /** 2.6.7 fallback: on a send failure, retry ONCE after 900ms
     * (transient Play Services not-ready), then report verbosely.
     * No-phone-connected is not retried: there is nothing to retry to. */
    private void handleTriggerFailure(final Throwable t, final String path,
            final String label, final int attempt) {
        Log.e(TAG, "one-tap send failed (attempt " + attempt + ")", t);
        if (attempt == 0) {
            appendLine("Beacon: Fallback \u2014 retrying once\u2026");
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    sendPresetToPhoneAttempt(path, label, 1);
                }
            }, 900);
            return;
        }
        safeToast("Trigger failed: " + safeMsg(t), Toast.LENGTH_LONG);
        appendLine("Beacon: Error \u2014 Trigger failed: " + safeMsg(t));
        appendLine("Beacon: Detail \u2014 " + verboseError(t));
    }
    /** 2.6.3: Toasts must run on the main thread and must not use a
     * destroyed Activity as context (BadToken / window-token crashes
     * after a tap if the wrist goes down and the activity pauses or
     * is destroyed before an async callback fires). Application
     * context + main-thread posting + try/catch covers all three. */
    private void safeToast(final String msg, final int duration) {
        final String text = msg == null ? "unknown error" : msg;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = getApplicationContext();
                    if (ctx == null) ctx = MainActivity.this;
                    Toast.makeText(ctx, text, duration).show();
                } catch (Throwable t) {
                    Log.w(TAG, "safeToast failed", t);
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        transcript = findViewById(R.id.transcript);
        modelLabel = findViewById(R.id.model_label);
        // 2.6.4: version label, null-guarded like every other view.
        versionLabel = findViewById(R.id.version_label);
        if (versionLabel != null) {
            try {
                versionLabel.setText("Beacon " + BuildConfig.VERSION_NAME
                        + " (" + BuildConfig.VERSION_CODE + ")");
            } catch (Throwable t) {
                Log.w(TAG, "version label failed", t);
            }
        }
        input = findViewById(R.id.input);
        scroll = findViewById(R.id.scroll);
        drawer = findViewById(R.id.drawer);

        // Ninja background: faint mark behind the chat, gently pulsing like
        // the splash icon (a real animated GIF would cost more than it is
        // worth on a watch — the pulse reads as "alive" for free).
        View ninjaBg = findViewById(R.id.ninja_bg);
        if (ninjaBg != null) {
            ObjectAnimator nx = ObjectAnimator.ofFloat(ninjaBg, "scaleX", 1.0f, 1.08f, 1.0f);
            ObjectAnimator ny = ObjectAnimator.ofFloat(ninjaBg, "scaleY", 1.0f, 1.08f, 1.0f);
            nx.setDuration(2400);
            ny.setDuration(2400);
            nx.setRepeatCount(ObjectAnimator.INFINITE);
            ny.setRepeatCount(ObjectAnimator.INFINITE);
            AnimatorSet pulse = new AnimatorSet();
            pulse.playTogether(nx, ny);
            pulse.start();
        }

        // Tap the readout to hide the input + buttons for a full-screen
        // readout; tap again to bring them back.
        if (transcript != null) transcript.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDrawer();
            }
        });

        Button sendBtn = findViewById(R.id.btn_send);
        Button micBtn = findViewById(R.id.btn_mic);
        // 2.6.3: a missing view must never NPE the whole launch. Every
        // button below is null-checked before its listener is set, so a
        // layout/id drift degrades to a dead button, not a crash.
        // No btn_web in 2.6.1: Web Chat button removed at Alan's direction
        // Oct 30 ("it's broken anyway"). Do NOT re-add a findViewById for
        // it here — the view no longer exists and the call would NPE.
        Button settingsBtn = findViewById(R.id.btn_settings);
        Button demoBtn = findViewById(R.id.btn_demo);
        Button triggerBtn = findViewById(R.id.btn_trigger);
        Button oneTapBtn = findViewById(R.id.btn_onetap);

        if (sendBtn != null) sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input == null || input.getText() == null ? "" : input.getText().toString().trim();
                if (!text.isEmpty()) {
                    sendMessage(text);
                }
            }
        });

        if (micBtn != null) micBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoiceInput();
            }
        });

        if (settingsBtn != null) settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // Demo answer (v2.4): shows the latest answer relayed from the phone
        // for the hackathon demo — no model call, no key needed on this path.
        if (demoBtn != null) demoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DemoAnswerActivity.class));
            }
        });

        // Trigger (v2.5): one tap sends the preset question to the phone
        // over the Data Layer; the phone captures the glasses frame, runs
        // Ask Muse, and relays the answer back (existing demo-answer path).
        if (triggerBtn != null) triggerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendGlassesTrigger();
            }
        });

        // One-tap (v2.6): a single tap sends the preset question on
        // /beacon/one_tap. The phone owns the whole motion (start session,
        // preview/stream, snap a fresh picture, Ask Muse, stop session);
        // the watch only sends this one message and waits for the async
        // answer on the existing demo-answer path. No voice, no typing.
        if (oneTapBtn != null) oneTapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendOneTap();
            }
        });

        appendLine("Beacon: Hi — tap Mic or type below. Tap this readout to hide/show the buttons.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (modelLabel != null) modelLabel.setText("Model: " + Prefs.modelLabel(this) + " — tap readout to hide buttons");
        } catch (Throwable t) {
            Log.w(TAG, "onResume modelLabel failed", t);
        }
        // v2.6.2: show the latest demo result in the MAIN transcript on
        // every resume — this covers answers that arrived while this
        // activity was not foreground (e.g. the user was on Settings or
        // the app was reopened after an Ask). The dedupe key inside
        // showLatestDemoAnswerIfNew() keeps a resume from re-appending
        // the same result twice in one activity lifetime.
        try {
            showLatestDemoAnswerIfNew();
        } catch (Throwable t) {
            Log.w(TAG, "onResume showLatestDemoAnswerIfNew failed", t);
        }
        if (demoReceiver == null) {
            demoReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        showLatestDemoAnswerIfNew();
                    } catch (Throwable t) {
                        Log.w(TAG, "broadcast showLatestDemoAnswerIfNew failed", t);
                    }
                }
            };
            try {
                // 2.6.3: Android 14 (Wear OS 5, target 34) requires an
                // explicit exported flag for dynamic receivers. This
                // broadcast is package-scoped (DemoListenerService sets
                // setPackage), so NOT_EXPORTED is correct; the old
                // no-flag call can throw SecurityException on resume
                // after the activity pauses (e.g. wrist down after a tap).
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(demoReceiver, new IntentFilter(ACTION_DEMO_UPDATED),
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(demoReceiver, new IntentFilter(ACTION_DEMO_UPDATED));
                }
            } catch (Throwable t) {
                Log.w(TAG, "registerReceiver failed", t);
                demoReceiver = null;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (demoReceiver != null) {
            try {
                unregisterReceiver(demoReceiver);
            } catch (Throwable ignored) {
                // Receiver was never registered or already unregistered.
            }
            demoReceiver = null;
        }
    }

    /**
     * v2.6.2: render the latest stored demo result directly into the main
     * transcript, without requiring a Demo button tap. Called on resume
     * and on every ACTION_DEMO_UPDATED broadcast from DemoListenerService,
     * so it fires for both the one-tap path and the older trigger path
     * (both deliver on /beacon/demo_answer). A result is appended once
     * per key; a failed Ask with a previous answer stored shows the
     * previous answer plus the failure as a footer (mirroring the Demo
     * screen, which stays as history/fallback). A failed Ask with no
     * answer ever stored shows the error text alone. Empty state (no
     * answer, no error) renders nothing — the greeting / chat transcript
     * is left untouched.
     */
    private void showLatestDemoAnswerIfNew() {
        String q = Prefs.getDemoQuestion(this);
        String a = Prefs.getDemoAnswer(this);
        String at = Prefs.getDemoAt(this);
        String err = Prefs.getDemoError(this);
        // 2.6.3: Prefs getters can return null on a corrupted/legacy
        // store; isEmpty() on null is an NPE on the resume/broadcast path.
        if (q == null) q = "";
        if (a == null) a = "";
        if (at == null) at = "";
        if (err == null) err = "";
        if (a.isEmpty() && err.isEmpty()) {
            return;
        }
        String key = at + "|" + q + "|" + a + "|" + err;
        if (key.equals(lastShownDemoKey)) {
            return;
        }
        lastShownDemoKey = key;
        if (!a.isEmpty()) {
            appendLine("Muse answer — Q: " + q + "\n" + a);
            if (!err.isEmpty()) {
                appendLine("— Last Ask note: " + err);
            }
        } else {
            appendLine("Muse answer — Error: " + err);
        }
    }

    private void toggleDrawer() {
        drawerHidden = !drawerHidden;
        if (drawer != null) drawer.setVisibility(drawerHidden ? View.GONE : View.VISIBLE);
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now");
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (Throwable e) {
            safeToast("Voice input not available on this watch", Toast.LENGTH_LONG);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                input.setText(spoken);
                sendMessage(spoken);
            }
        }
    }

    /**
     * v2.5 trigger: sends the preset question to every connected phone node
     * via MessageClient on /beacon/demo_trigger, payload = UTF-8 bytes of
     * the preset question exactly. Immediate on-watch feedback (Toast +
     * transcript line) happens before any network round-trip, so Alan can
     * see on camera that the tap registered. No API key is used here.
     * Kept working unchanged in v2.6 (same path, payload, feedback).
     */
    private void sendGlassesTrigger() {
        sendPresetToPhone(PATH_DEMO_TRIGGER, "glasses");
    }

    /** v2.6 one-tap: same preset, same feedback, new path /beacon/one_tap. */
    private void sendOneTap() {
        sendPresetToPhone(PATH_ONE_TAP, "one tap");
    }

    /**
     * Shared sender for the trigger and one-tap buttons: one tap sends the
     * preset question to every connected phone node on the given path.
     * The label only changes the transcript line, so both buttons read
     * identically on camera ("Asking Muse…") apart from that line.
     */
    private void sendPresetToPhone(final String path, final String label) {
        sendPresetToPhoneAttempt(path, label, 0);
    }

    private void sendPresetToPhoneAttempt(final String path, final String label, final int attempt) {
        // 2.6.3 hardening of the whole One-tap/trigger send path. The
        // 2.6.2 code was "guarded" only in the sense that DemoListenerService
        // catches Exception; this sender had NO try/catch at all:
        //   (a) Wearable.getNodeClient().getConnectedNodes() and
        //       MessageClient.sendMessage() can throw synchronously
        //       (Play Services unavailable/disconnected, bad node id)
        //       AFTER the "Asking Muse..." toast, so the app would
        //       crash and the phone would never receive the message
        //       -- exactly Alan's reported symptom;
        //   (b) every async callback touched the UI directly (Toast +
        //       appendLine) with the Activity as Toast context. If the
        //       wrist goes down / the activity pauses or is destroyed
        //       before a callback fires, that is a window-token /
        //       CalledFromWrongThread crash, not a delivered message;
        //   (c) e.getMessage() can be null, and "Trigger failed: " + null
        //       is cosmetic, but a null Exception itself in a listener
        //       would NPE. All three are now closed: whole send in
        //       try/catch(Throwable), all UI via safeToast/appendLine
        //       (main-thread, application context, null-guarded), and
        //       null node / null node-id are treated as "no phone"
        //       instead of a send attempt.
        // User-visible flow is UNCHANGED: same preset, same path,
        // same "Asking Muse..." feedback, same reply paths.
        try {
            safeToast("Asking Muse\u2026", Toast.LENGTH_SHORT);
            appendLine("Beacon: Asking Muse (" + label + ")\u2026");
            final byte[] payload = PRESET_QUESTION.getBytes(StandardCharsets.UTF_8);
            Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override
                        public void onSuccess(List<Node> nodes) {
                            try {
                                if (nodes == null || nodes.isEmpty()) {
                                    safeToast("No phone connected", Toast.LENGTH_LONG);
                                    appendLine("Beacon: Error \u2014 No phone connected");
                                    return;
                                }
                                boolean sent = false;
                                for (Node node : nodes) {
                                    if (node == null || node.getId() == null) {
                                        continue;
                                    }
                                    sent = true;
                                    Wearable.getMessageClient(MainActivity.this)
                                            .sendMessage(node.getId(), path, payload)
                                            .addOnFailureListener(new OnFailureListener() {
                                                @Override
                                                public void onFailure(Exception e) {
                                                    handleTriggerFailure(e, path, label, attempt);
                                                }
                                            });
                                }
                                if (!sent) {
                                    safeToast("No phone connected", Toast.LENGTH_LONG);
                                    appendLine("Beacon: Error \u2014 No phone connected");
                                }
                            } catch (Throwable t) {
                                handleTriggerFailure(t, path, label, attempt);
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            handleTriggerFailure(e, path, label, attempt);
                        }
                    });
        } catch (Throwable t) {
            handleTriggerFailure(t, path, label, attempt);
        }
    }

    private void sendMessage(final String text) {
        input.setText("");
        appendLine("You: " + text);
        final String apiKey = Prefs.getApiKey(this);
        if (apiKey.isEmpty()) {
            appendLine("Beacon: Set your API key in Settings first.");
            return;
        }
        final String model = Prefs.getModel(this);
        final List<ApiClient.Message> snapshot = new ArrayList<ApiClient.Message>(history);
        appendLine("Beacon: …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String reply = ApiClient.chat(model, apiKey, snapshot, text);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            trimThinkingLine();
                            // Belt and suspenders: a null/empty/"null" reply must
                            // never render as "Beacon: null" in the transcript.
                            if (reply == null || reply.trim().isEmpty()
                                    || reply.trim().equals("null")) {
                                appendLine("Beacon: Error — No reply text came back — try again");
                                return;
                            }
                            appendLine("Beacon: " + reply);
                            history.add(new ApiClient.Message("user", text));
                            history.add(new ApiClient.Message("assistant", reply));
                            trimHistory();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            trimThinkingLine();
                            appendLine("Beacon: Error — " + safeMsg(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void trimThinkingLine() {
        if (transcript == null) return;
        String t = transcript.getText() == null ? "" : transcript.getText().toString();
        String marker = "Beacon: …\n";
        if (t.endsWith(marker)) {
            transcript.setText(t.substring(0, t.length() - marker.length()));
        }
    }

    private void trimHistory() {
        while (history.size() > 10) {
            history.remove(0);
        }
    }

    private void appendLine(final String line) {
        // 2.6.3: appendLine is called from UI code, from broadcast
        // receivers, AND from Play Services Task callbacks, whose
        // thread is not guaranteed to be the UI thread. Touching a
        // TextView off the UI thread is CalledFromWrongThreadException
        // -- a crash after a tap, with no message sent. Route
        // everything through the main handler, and never touch a
        // null/detached view.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    appendLine(line);
                }
            });
            return;
        }
        try {
            if (transcript == null) return;
            transcript.append(line + "\n");
            String t = transcript.getText() == null ? "" : transcript.getText().toString();
            if (t.length() > 8000) {
                transcript.setText(t.substring(t.length() - 8000));
            }
            if (scroll == null) return;
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (transcript == null || scroll == null) return;
                        int target = transcript.getBottom() - scroll.getHeight() + 60;
                        if (target < 0) {
                            target = 0;
                        }
                        scroll.scrollTo(0, target);
                    } catch (Throwable t) {
                        Log.w(TAG, "appendLine scroll failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "appendLine failed", t);
        }
    }
}
