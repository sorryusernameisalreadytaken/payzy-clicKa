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
    // Key for storing the username
    private static final String KEY_USERNAME = "username";

    // Keys for storing wallet and coins values
    private static final String KEY_WALLET_VALUE = "wallet_value";
    private static final String KEY_COINS_VALUE = "coins_value";

    // Key for storing the comma-separated list of allowed package names used by watchers
    private static final String KEY_ALLOWED_PACKAGES = "allowed_packages";

    // Keys for storing user‑defined thresholds.  These values allow the user
    // to configure how much Guthaben (wallet balance) should remain in the
    // wallet before an automatic top‑up is attempted and how many coins need
    // to accumulate before a redemption watcher should run.  The values are
    // stored as plain strings and parsed by the watchers.
    private static final String KEY_MIN_WALLET = "min_wallet";
    private static final String KEY_MIN_COINS = "min_coins";

    // Key for storing the last processed transaction identifier.  When the
    // transactions watcher runs it will stop scanning once it encounters
    // this identifier again.  Persisting the last ID prevents duplicate
    // entries when exporting the latest transactions to CSV.
    private static final String KEY_LAST_TRANSACTION_ID = "last_transaction_id";

    // Keys for storing thresholds for wallet top‑up and coin redemption
    private static final String KEY_MIN_WALLET = "min_wallet";
    private static final String KEY_MIN_COINS = "min_coins";

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

    /**
     * Stores the user-defined username in shared preferences. This method overwrites any
     * previously stored value.
     *
     * @param context a valid application or activity context
     * @param username the user name to persist (may be empty)
     */
    public static void setUsername(Context context, String username) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USERNAME, username != null ? username : "").apply();
    }

    /**
     * Retrieves the stored username.
     *
     * @param context a valid application or activity context
     * @return the stored username or an empty string if not set
     */
    public static String getUsername(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, "");
    }

    /**
     * Stores the current wallet value in shared preferences. This method overwrites any
     * previously stored value.
     *
     * @param context a valid application or activity context
     * @param value the wallet balance to persist (may be empty)
     */
    public static void setWalletValue(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_WALLET_VALUE, value != null ? value : "").apply();
    }

    /**
     * Retrieves the stored wallet value.
     *
     * @param context a valid application or activity context
     * @return the stored wallet value or an empty string if not set
     */
    public static String getWalletValue(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_WALLET_VALUE, "");
    }

    /**
     * Stores the current coins amount in shared preferences. This method overwrites any
     * previously stored value.
     *
     * @param context a valid application or activity context
     * @param value the coins amount to persist (may be empty)
     */
    public static void setCoinsValue(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_COINS_VALUE, value != null ? value : "").apply();
    }

    /**
     * Retrieves the stored coins amount.
     *
     * @param context a valid application or activity context
     * @return the stored coins amount or an empty string if not set
     */
    public static String getCoinsValue(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_COINS_VALUE, "");
    }

    /**
     * Stores a comma-separated list of allowed package names. Watchers will only
     * interact with screens whose root package matches one of these values. An
     * empty string disables package filtering.
     *
     * @param context a valid context
     * @param packagesCsv a comma-separated list of package names
     */
    public static void setAllowedPackages(Context context, String packagesCsv) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ALLOWED_PACKAGES, packagesCsv != null ? packagesCsv : "").apply();
    }

    /**
     * Retrieves the stored comma-separated list of allowed package names.
     *
     * @param context a valid context
     * @return the stored list or an empty string if not set
     */
    public static String getAllowedPackages(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ALLOWED_PACKAGES, "");
    }

    /**
     * Parses the stored comma-separated package list into an array of trimmed strings.
     *
     * @param context a valid context
     * @return an array of allowed package names; if none are specified, returns an empty array
     */
    public static String[] getAllowedPackagesList(Context context) {
        String csv = getAllowedPackages(context);
        if (csv == null || csv.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Stores the minimum wallet balance as configured by the user.  The
     * value should be a numeric string representing euros (e.g. "10.00").  An
     * empty string indicates no automatic top‑up should occur.
     */
    public static void setMinWallet(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MIN_WALLET, value != null ? value : "").apply();
    }

    /**
     * Retrieves the minimum wallet balance.  If not set, returns an empty
     * string.  Parsing of this value into a numeric type is performed by
     * watchers when needed.
     */
    public static String getMinWallet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MIN_WALLET, "");
    }

    /**
     * Stores the minimum coin count required before the redemption watcher
     * engages.  The string should represent an integer value (e.g. "50").
     */
    public static void setMinCoins(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MIN_COINS, value != null ? value : "").apply();
    }

    /**
     * Retrieves the minimum coin count.  Returns an empty string when
     * undefined.  Watchers handle parsing and defaulting behaviour.
     */
    public static String getMinCoins(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MIN_COINS, "");
    }

    /**
     * Stores the last processed transaction identifier.  This identifier is
     * extracted from the transaction list by the transactions watcher.  It
     * should uniquely identify a transaction in the Payzy app.
     */
    public static void setLastTransactionId(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_TRANSACTION_ID, id != null ? id : "").apply();
    }

    /**
     * Retrieves the last processed transaction identifier.  Returns an empty
     * string if no transaction has been processed previously.
     */
    public static String getLastTransactionId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_TRANSACTION_ID, "");
    }

    /**
     * Stores the user‑defined minimum wallet balance (threshold at which a top‑up
     * should be initiated) as a string.  The value may contain a comma or dot
     * and will be parsed on demand.
     *
     * @param context a valid application or activity context
     * @param value   the threshold to persist (may be empty)
     */
    public static void setMinWallet(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MIN_WALLET, value != null ? value : "").apply();
    }

    /**
     * Retrieves the stored minimum wallet balance threshold.  Returns an empty
     * string when no value has been stored.  Callers should handle parsing and
     * provide sensible defaults when the returned value is empty.
     *
     * @param context a valid application or activity context
     * @return the stored threshold or an empty string if not set
     */
    public static String getMinWallet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MIN_WALLET, "");
    }

    /**
     * Stores the user‑defined minimum coin amount required to trigger an
     * automatic redemption.  The value is stored as a string to allow empty
     * values.  Callers should validate the string when reading.
     *
     * @param context a valid context
     * @param value   the coin threshold to persist (may be empty)
     */
    public static void setMinCoins(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MIN_COINS, value != null ? value : "").apply();
    }

    /**
     * Retrieves the stored minimum coin threshold.  Returns an empty string
     * when no value has been stored.  Callers should provide a default when
     * necessary.
     *
     * @param context a valid context
     * @return the stored coin threshold or an empty string
     */
    public static String getMinCoins(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MIN_COINS, "");
    }
}