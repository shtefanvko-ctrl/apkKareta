package kz.kareta.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class OfflineQueue {
    private static final String PREFS = "kareta_native_offline";
    private static final String KEY = "items_v2";
    private static final int MAX_ITEMS = 100;
    private final SharedPreferences prefs;

    OfflineQueue(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized JSONObject state() {
        JSONArray items = read();
        JSONObject out = new JSONObject();
        try {
            out.put("count", items.length());
            out.put("durable", true);
            out.put("acknowledgeRequired", true);
        } catch (JSONException ignored) {}
        return out;
    }

    synchronized JSONObject enqueue(JSONObject payload) {
        JSONArray items = read();
        JSONObject item = new JSONObject();
        try {
            item.put("id", "nq_" + UUID.randomUUID());
            item.put("createdAt", System.currentTimeMillis());
            item.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException ignored) {}

        JSONArray next = new JSONArray();
        int start = Math.max(0, items.length() - (MAX_ITEMS - 1));
        for (int i = start; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row != null) next.put(row);
        }
        next.put(item);
        write(next);
        return item;
    }

    synchronized JSONObject peek() {
        JSONObject out = new JSONObject();
        try {
            out.put("items", read());
            out.put("destructive", false);
        } catch (JSONException ignored) {}
        return out;
    }

    synchronized JSONObject acknowledge(JSONArray confirmed) {
        Set<String> ids = new HashSet<>();
        if (confirmed != null) {
            for (int i = 0; i < confirmed.length(); i++) {
                JSONObject row = confirmed.optJSONObject(i);
                String id = row != null ? row.optString("id", "") : confirmed.optString(i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        }

        JSONArray current = read();
        JSONArray keep = new JSONArray();
        int removed = 0;
        for (int i = 0; i < current.length(); i++) {
            JSONObject row = current.optJSONObject(i);
            if (row == null) continue;
            if (ids.contains(row.optString("id", ""))) removed++;
            else keep.put(row);
        }
        write(keep);

        JSONObject out = new JSONObject();
        try {
            out.put("removed", removed);
            out.put("count", keep.length());
        } catch (JSONException ignored) {}
        return out;
    }

    synchronized JSONObject restore(JSONArray incoming) {
        JSONArray current = read();
        Set<String> ids = new HashSet<>();
        JSONArray merged = new JSONArray();

        for (int i = 0; i < current.length(); i++) {
            JSONObject row = current.optJSONObject(i);
            if (row == null) continue;
            ids.add(row.optString("id", ""));
            merged.put(row);
        }
        if (incoming != null) {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject row = incoming.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", "");
                if (!id.isEmpty() && ids.add(id)) merged.put(row);
            }
        }

        JSONArray limited = new JSONArray();
        int start = Math.max(0, merged.length() - MAX_ITEMS);
        for (int i = start; i < merged.length(); i++) limited.put(merged.optJSONObject(i));
        write(limited);
        return state();
    }

    synchronized JSONObject clear() {
        prefs.edit().remove(KEY).apply();
        return state();
    }

    private JSONArray read() {
        String raw = prefs.getString(KEY, "[]");
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    @SuppressLint("ApplySharedPref") // Durable queue must reach storage before the bridge acknowledges enqueue/ack.
    private void write(JSONArray items) {
        prefs.edit().putString(KEY, items.toString()).commit();
    }
}
