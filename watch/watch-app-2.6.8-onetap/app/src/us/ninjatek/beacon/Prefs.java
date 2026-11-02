package us.ninjatek.beacon;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String FILE = "beacon_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    // Latest demo answer relayed from the phone (v2.4). Stored in the same
    // private prefs file as the API key — but it is NOT a credential, just
    // the last demo Q+A, and it is never sent anywhere from the watch.
    private static final String KEY_DEMO_QUESTION = "demo_question";
    private static final String KEY_DEMO_ANSWER = "demo_answer";
    private static final String KEY_DEMO_AT = "demo_at";
    // Latest Ask failure text (v2.6). Kept SEPARATE from the answer on
    // purpose: a failed new Ask must never overwrite the last good
    // answer, so the Demo button still shows the previous answer after
    // a new Ask fails. A new good answer clears this slot.
    private static final String KEY_DEMO_ERROR = "demo_error";

    public static final String MODEL_SPARK = "muse-spark-1.3";
    public static final String MODEL_WONDER = "muse-wonder-1.0";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getApiKey(Context ctx) {
        String v = prefs(ctx).getString(KEY_API_KEY, ""); return v == null ? "" : v;
    }

    public static void setApiKey(Context ctx, String key) {
        prefs(ctx).edit().putString(KEY_API_KEY, key).apply();
    }

    public static String getModel(Context ctx) {
        String v = prefs(ctx).getString(KEY_MODEL, MODEL_SPARK); return v == null ? MODEL_SPARK : v;
    }

    public static void setModel(Context ctx, String model) {
        prefs(ctx).edit().putString(KEY_MODEL, model).apply();
    }

    public static String modelLabel(Context ctx) {
        return MODEL_WONDER.equals(getModel(ctx)) ? "Wonder" : "Spark";
    }

    public static String getDemoQuestion(Context ctx) {
        String v = prefs(ctx).getString(KEY_DEMO_QUESTION, ""); return v == null ? "" : v;
    }

    public static String getDemoAnswer(Context ctx) {
        String v = prefs(ctx).getString(KEY_DEMO_ANSWER, ""); return v == null ? "" : v;
    }

    public static String getDemoAt(Context ctx) {
        String v = prefs(ctx).getString(KEY_DEMO_AT, ""); return v == null ? "" : v;
    }

    public static void setDemoAnswer(Context ctx, String question, String answer, String at) {
        prefs(ctx).edit()
                .putString(KEY_DEMO_QUESTION, question == null ? "" : question)
                .putString(KEY_DEMO_ANSWER, answer == null ? "" : answer)
                .putString(KEY_DEMO_AT, at == null ? "" : at)
                .remove(KEY_DEMO_ERROR)
                .apply();
    }

    /**
     * v2.6: store a Q+A delivered without a stamp (e.g. tapping the
     * tap-to-view card) WITHOUT wiping the stored demo_at stamp. The
     * pre-2.6 code stored "" here, destroying the dedupe stamp the
     * listener relies on; preserving it keeps re-delivery dedupe working
     * across card taps. Never touches the error slot either.
     */
    public static void setDemoAnswerPreservingAt(Context ctx, String question, String answer) {
        prefs(ctx).edit()
                .putString(KEY_DEMO_QUESTION, question == null ? "" : question)
                .putString(KEY_DEMO_ANSWER, answer == null ? "" : answer)
                .remove(KEY_DEMO_ERROR)
                .apply();
    }

    public static String getDemoError(Context ctx) {
        String v = prefs(ctx).getString(KEY_DEMO_ERROR, ""); return v == null ? "" : v;
    }

    /** v2.6: record an Ask failure WITHOUT touching question/answer/at. */
    public static void setDemoError(Context ctx, String error) {
        prefs(ctx).edit()
                .putString(KEY_DEMO_ERROR, error == null ? "" : error)
                .apply();
    }
}
