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
    // Keys for storing password and PIN
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_PIN = "pin";

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

    /**
     * Stores the user-defined password in shared preferences. This method overwrites any
     * previously stored value.
     *
     * @param context a valid application or activity context
     * @param password the password to persist (may be empty)
     */
    public static void setPassword(Context context, String password) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PASSWORD, password != null ? password : "").apply();
    }

    /**
     * Retrieves the stored password.
     *
     * @param context a valid application or activity context
     * @return the stored password or an empty string if not set
     */
    public static String getPassword(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PASSWORD, "");
    }

    /**
     * Stores the user-defined PIN in shared preferences. This method overwrites any
     * previously stored value.
     *
     * @param context a valid application or activity context
     * @param pin the PIN to persist (may be empty)
     */
    public static void setPin(Context context, String pin) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PIN, pin != null ? pin : "").apply();
    }

    /**
     * Retrieves the stored PIN.
     *
     * @param context a valid application or activity context
     * @return the stored PIN or an empty string if not set
     */
    public static String getPin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PIN, "");
    }
}