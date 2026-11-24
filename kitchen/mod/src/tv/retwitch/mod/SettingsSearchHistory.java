package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

/** Recent searches stay on the device, outside the exported mod preferences. */
final class SettingsSearchHistory {
    private static final int LIMIT = 20;

    private SettingsSearchHistory() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings_search_history", Context.MODE_PRIVATE);
    }

    static ArrayList<String> entries(Context context) {
        ArrayList<String> entries = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(context).getString("terms", "[]"));
            for (int i = 0; i < saved.length() && i < LIMIT; i++) {
                entries.add(saved.getString(i));
            }
        } catch (JSONException ignored) {
            entries.clear();
        }
        return entries;
    }

    static void remember(Context context, String text) {
        String term = text.trim();
        if (term.isEmpty()) {
            return;
        }
        ArrayList<String> entries = entries(context);
        entries.removeIf(old -> old.equalsIgnoreCase(term));
        entries.add(0, term);
        if (entries.size() > LIMIT) {
            entries.remove(LIMIT);
        }
        prefs(context).edit().putString("terms", new JSONArray(entries).toString()).apply();
    }

    static void remove(Context context, String term) {
        ArrayList<String> entries = entries(context);
        entries.remove(term);
        prefs(context).edit().putString("terms", new JSONArray(entries).toString()).apply();
    }

    static void clear(Context context) {
        prefs(context).edit().remove("terms").apply();
    }
}
