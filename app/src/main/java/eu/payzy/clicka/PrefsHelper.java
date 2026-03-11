package eu.payzy.clicka;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Helper class to persist and retrieve simple configuration for the Payzy clicKa app.
 * At the moment it stores a single boolean flag indicating whether event
 * recording is active. Additional preferences can be added as needed.
 */
public final class PrefsHelper {
    private static final String PREF_NAME = "clicka_prefs";
    private static final String KEY_RECORDING = "is_recording";

    private PrefsHelper() {
        // Prevent instantiation
    }

    /**
     * Returns whether recording is currently enabled.
     *
     * @param context a valid application or activity context
     * @return true when recording is active, false otherwise
     */
    public static boolean isRecording(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_RECORDING, false);
    }

    /**
     * Updates the recording flag.
     *
     * @param context a valid application or activity context
     * @param recording the new recording state
     */
    public static void setRecording(Context context, boolean recording) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_RECORDING, recording).apply();
    }
}