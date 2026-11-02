package us.ninjatek.beacon;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Full-screen demo-answer screen (hackathon demo, v2.4).
 *
 * Shows the latest answer relayed from the phone: the question the glasses
 * heard plus Muse's answer, stored by DemoListenerService (Data Layer) or
 * delivered directly via intent extras (EXTRA_DEMO_QUESTION /
 * EXTRA_DEMO_ANSWER). The answer persists in Prefs, so the screen still
 * shows it if the app is reopened for a second camera take.
 *
 * No API key is used or shown here. This screen never calls the model API;
 * it only displays what the phone relay already delivered.
 */
public class DemoAnswerActivity extends Activity {
    public static final String EXTRA_DEMO_QUESTION = "us.ninjatek.beacon.DEMO_QUESTION";
    public static final String EXTRA_DEMO_ANSWER = "us.ninjatek.beacon.DEMO_ANSWER";

    /** Channel + id for the tap-to-view card DemoListenerService posts. */
    public static final String CHANNEL_ID = "beacon_demo_answer";
    public static final int NOTIF_ID = 9002;

    private TextView body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(24, 24, 24, 24);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView header = new TextView(this);
        header.setText("MUSE ANSWER");
        header.setTextSize(13);
        header.setTextColor(0xFFAAAAAA);
        header.setGravity(Gravity.CENTER);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        body = new TextView(this);
        body.setTextSize(18);
        body.setTextColor(0xFFFFFFFF);
        body.setPadding(4, 12, 4, 4);
        scroll.addView(body);
        root.addView(scroll);

        TextView brand = new TextView(this);
        brand.setText("NINJA-TEK.US");
        brand.setTextSize(11);
        brand.setTextColor(0xFF888888);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand);

        setContentView(root);

        // An intent delivery wins over the stored answer and is stored too,
        // so a later reopen shows the same answer. v2.6: preserve the
        // stored demo_at stamp (pre-2.6 stored "" here, wiping the dedupe
        // stamp the listener relies on).
        Intent intent = getIntent();
        String q = intent != null ? intent.getStringExtra(EXTRA_DEMO_QUESTION) : null;
        String a = intent != null ? intent.getStringExtra(EXTRA_DEMO_ANSWER) : null;
        if (q != null && a != null) {
            Prefs.setDemoAnswerPreservingAt(this, q, a);
        }
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = intent != null ? intent.getStringExtra(EXTRA_DEMO_QUESTION) : null;
        String a = intent != null ? intent.getStringExtra(EXTRA_DEMO_ANSWER) : null;
        if (q != null && a != null) {
            Prefs.setDemoAnswerPreservingAt(this, q, a);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        // Notification permission (Wear OS 4 / API 33+) is needed for the
        // tap-to-view card. Ask once, here, where the card matters — the
        // stored answer still shows via the Demo button even if declined.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void render() {
        String q = Prefs.getDemoQuestion(this);
        String a = Prefs.getDemoAnswer(this);
        String err = Prefs.getDemoError(this);
        if (q == null) q = "";
        if (a == null) a = "";
        if (err == null) err = "";
        if (body == null) return;
        if (q.isEmpty() && a.isEmpty() && err.isEmpty()) {
            body.setText("No demo answer yet.\n\nAsk Muse through the glasses, "
                    + "then open this screen again — the answer lands here.");
        } else if (a.isEmpty()) {
            // A failed Ask with no good answer stored yet: show the error
            // verbatim (it is the only thing to show) plus the placeholder.
            body.setText(err + "\n\nNo demo answer yet.");
        } else {
            // The stored answer always wins the screen: a failed later Ask
            // must never hide it. A failure note, if any, rides as a footer.
            String text = "Q: " + q + "\n\n" + a;
            if (!err.isEmpty()) {
                text += "\n\n— Last Ask note: " + err;
            }
            body.setText(text);
        }
    }

    /** v2.6: tap-to-view card for an Ask failure. Carries NO extras, so
     * opening it renders from Prefs: the stored previous answer (if any)
     * plus the failure note — it never overwrites the stored answer. */
    public static void postErrorCard(Context ctx, String error) {
        if (ctx == null) return;
        if (error == null) error = "";
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Beacon demo answer",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Muse answers relayed from the phone for the demo");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, DemoAnswerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Beacon — Ask failed")
                .setContentText(error)
                .setStyle(new Notification.BigTextStyle().bigText(error))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 300, 150, 300});
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) {
            // 2.6.3: nm.notify can throw more than SecurityException
            // (e.g. BadNotificationException); a card must never crash
            // the listener/service that posted it.
            // POST_NOTIFICATIONS not granted: the error is still stored
            // and visible via the Demo button on the main screen.
        }
    }

    /** Posts the tap-to-view card for a freshly relayed answer. */
    public static void postAnswerCard(Context ctx, String question, String answer) {
        if (ctx == null) return;
        if (question == null) question = "";
        if (answer == null || answer.isEmpty()) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Beacon demo answer",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Muse answers relayed from the phone for the demo");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, DemoAnswerActivity.class);
        open.putExtra(EXTRA_DEMO_QUESTION, question);
        open.putExtra(EXTRA_DEMO_ANSWER, answer);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Beacon — Muse answer")
                .setContentText(answer)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("Q: " + question + "\n\n" + answer))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 300, 150, 300});
        if (Build.VERSION.SDK_INT >= 26) {
            // Local notification on the watch: no bridge needed, it is
            // already on the wrist. The vibration is the camera cue.
        }
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) {
            // 2.6.3: nm.notify can throw more than SecurityException
            // (e.g. BadNotificationException); a card must never crash
            // the listener/service that posted it.
            // POST_NOTIFICATIONS not granted: the answer is still stored
            // and visible via the Demo button on the main screen.
        }
    }
}
