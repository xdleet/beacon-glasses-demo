package us.ninjatek.beacon;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal client for the Meta Model API (OpenAI-compatible chat completions).
 * No API key is stored here — the key comes from the watch's own settings.
 *
 * Defensive by design: a missing, JSON-null, or empty reply is never returned
 * as the literal string "null". Reasoning models (e.g. Spark 1.3) can answer
 * with content == null and the text in a reasoning field, so that is checked
 * as a fallback before giving up.
 */
public class ApiClient {

    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class Result {
        final int code;
        final String raw;

        Result(int code, String raw) {
            this.code = code;
            this.raw = raw;
        }
    }

    public static String chat(String model, String apiKey, List<Message> history, String userText)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "You are a helpful assistant. Keep replies short and plain — they are read on a watch screen.");
        messages.put(system);
        for (Message m : history) {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            o.put("content", m.content);
            messages.put(o);
        }
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userText);
        messages.put(user);
        body.put("messages", messages);
        body.put("max_tokens", 2000);

        Result r = post(model, apiKey, body);
        if (r.code == 401) {
            throw new Exception("API key rejected — check the key in Settings");
        }
        if (r.code < 200 || r.code >= 300) {
            throw new Exception("Server error (HTTP " + r.code + ")");
        }
        JSONObject resp = new JSONObject(r.raw);
        JSONArray choices = resp.optJSONArray("choices");
        String finish = null;
        JSONObject choice0 = null;
        JSONObject msgObj = null;
        if (choices != null && choices.length() > 0) {
            choice0 = choices.optJSONObject(0);
            if (choice0 != null) {
                finish = stringField(choice0, "finish_reason");
                msgObj = choice0.optJSONObject("message");
            }
        }
        String content = null;
        if (msgObj != null) {
            // Reasoning models do not always put the answer in "content" —
            // check every plausible text field in order and take the first
            // non-empty one, instead of guessing which field Spark uses.
            // (stringField also joins content-parts arrays, so a
            // [{text: ...}] content value is covered for each field name.)
            String[] fields = {
                "content", "text", "reasoning_content", "reasoning",
                "reasoning_text", "thinking", "thought", "output"
            };
            for (String f : fields) {
                content = stringField(msgObj, f);
                if (content != null) {
                    break;
                }
            }
        }
        if (content == null && choice0 != null) {
            // Last resort: completions-style text directly on the choice.
            content = stringField(choice0, "text");
        }
        if (content == null) {
            // Diagnostic: the server answered OK but with no readable text.
            // Show the HTTP code, finish reason, and a raw snippet so the
            // next screenshot reveals exactly what the server sent. The
            // raw body holds no key material (the key is only a header).
            throw new Exception(noContentDebug(r.code, finish, r.raw));
        }
        return content;
    }

    /**
     * Builds the no-content diagnostic line: HTTP code, finish reason, and
     * the first ~300 chars of the raw response body.
     */
    private static String noContentDebug(int code, String finish, String raw) {
        String snippet = raw == null ? "" : raw;
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300);
        }
        return "No reply text came back — try again. Debug: HTTP " + code
                + ", finish: " + (finish == null ? "?" : finish)
                + ", raw: " + snippet;
    }

    /**
     * Minimal auth check: one tiny call. Any 2xx means the key is accepted
     * (content may legitimately be empty on a tiny token budget — that is
     * still a pass). 401 means the key is wrong. Throws on network failure.
     * Runs on the watch only, with the key Alan typed in Settings.
     */
    public static void testKey(String model, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "Hi");
        messages.put(user);
        body.put("messages", messages);
        body.put("max_tokens", 20);

        Result r = post(model, apiKey, body);
        if (r.code == 401) {
            throw new Exception("Key rejected — retype it");
        }
        if (r.code < 200 || r.code >= 300) {
            throw new Exception("Server error (HTTP " + r.code + ")");
        }
    }

    private static Result post(String model, String apiKey, JSONObject body) throws Exception {
        URL url = new URL("https://api.meta.ai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = conn.getOutputStream();
        out.write(payload);
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
        }
        return new Result(code, sb.toString());
    }

    /**
     * Reads a string field defensively: absent, JSON-null, empty, or the
     * literal text "null" all count as missing. A content-parts array
     * ([{text: ...}, ...]) is joined into one string.
     */
    private static String stringField(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) {
            return null;
        }
        Object v = obj.opt(key);
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof JSONObject) {
                    String t = stringField((JSONObject) part, "text");
                    if (t != null) {
                        sb.append(t);
                    }
                } else if (part instanceof String) {
                    sb.append((String) part);
                }
            }
            String joined = sb.toString().trim();
            return joined.isEmpty() ? null : joined;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || s.equals("null")) {
            return null;
        }
        return s;
    }
}
