package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reset, export and import of the {@code @Tunable} preferences.
 *
 * Every tunable is stored under a key of the form
 * {@code pref_tunable_<classname>_<fieldname>}, all lower case, so the whole
 * set can be found by prefix without walking the annotated fields. The value's
 * own type (int, float, boolean, String) is what is stored and what is
 * exported, so a round trip keeps it.
 */
public class TunableSettingsManager {
    private static final String TAG = "TunableSettingsMgr";
    /** The prefix every @Tunable preference key starts with. */
    public static final String PREFIX = "pref_tunable_";
    private static final List<Class<?>> REGISTERED_CLASSES = new ArrayList<>();
    private static boolean autoRegistered = false;

    /**
     * Register a class for tunable management
     */
    public static void registerClass(Class<?> clazz) {
        if (!REGISTERED_CLASSES.contains(clazz)) {
            REGISTERED_CLASSES.add(clazz);
        }
    }

    /**
     * Automatically register all tunable classes.
     * This ensures classes are registered even if the tunable settings screen is never opened.
     */
    public static void ensureTunableClassesRegistered() {
        if (autoRegistered) {
            return; // Already registered
        }

        for (Class<?> clazz : TunableRegistry.TUNABLE_CLASSES) {
            registerClass(clazz);
        }

        autoRegistered = true;
        Log.d(TAG, "Auto-registered " + REGISTERED_CLASSES.size() + " tunable classes");
    }

    /**
     * Reset all tunable preferences by removing the persisted values, so the
     * annotation defaults are used again.
     */
    public static void resetAllToDefaults(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();

        int resetCount = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PREFIX)) {
                editor.remove(key);
                resetCount++;
            }
        }

        editor.apply();
        Log.d(TAG, "Reset " + resetCount + " tunable preferences (removed persisted values)");
    }

    /**
     * Get count of registered tunable classes
     */
    public static int getRegisteredClassCount() {
        return REGISTERED_CLASSES.size();
    }

    /**
     * Export every persisted tunable, keyed by its preference key. A tunable
     * that was never changed has no persisted value and so is not exported.
     */
    public static Map<String, Object> exportTunableSettings(Context context) {
        Map<String, Object> tunableSettings = new HashMap<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!key.startsWith(PREFIX) || value == null) {
                continue;
            }
            if (value instanceof String && ((String) value).isEmpty()) {
                continue;
            }
            tunableSettings.put(key, value);
        }

        Log.d(TAG, "Exported " + tunableSettings.size() + " tunable settings");
        return tunableSettings;
    }

    /**
     * Import tunable settings, storing each value under its own type. Keys in
     * the older "ClassName.fieldName" form are still accepted.
     */
    public static void importTunableSettings(Context context, Map<String, Object> tunableSettings) {
        if (tunableSettings == null || tunableSettings.isEmpty()) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();

        int importedCount = 0;
        for (Map.Entry<String, Object> entry : tunableSettings.entrySet()) {
            String prefKey = toPrefKey(entry.getKey());
            Object value = entry.getValue();
            if (prefKey == null || value == null) {
                continue;
            }

            if (value instanceof Boolean) {
                editor.putBoolean(prefKey, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(prefKey, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(prefKey, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(prefKey, (Float) value);
            } else if (value instanceof Double) {
                editor.putFloat(prefKey, ((Double) value).floatValue());
            } else {
                editor.putString(prefKey, value.toString());
            }
            importedCount++;
        }

        editor.apply();
        Log.d(TAG, "Imported " + importedCount + " tunable settings");
    }

    /** Accepts both a preference key and the older "ClassName.fieldName" label. */
    private static String toPrefKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (key.startsWith(PREFIX)) {
            return key;
        }
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            Log.w(TAG, "Invalid tunable setting key: " + key);
            return null;
        }
        return PREFIX + key.substring(0, dot).toLowerCase()
                + "_" + key.substring(dot + 1).toLowerCase();
    }
}
