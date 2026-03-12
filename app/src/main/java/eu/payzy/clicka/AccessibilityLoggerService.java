package eu.payzy.clicka;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import eu.payzy.clicka.MainActivity;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * AccessibilityLoggerService listens for all accessibility events when enabled and,
 * if recording is active, writes a simple CSV record to a file on external storage.
 * Each line contains the timestamp, event type, package name and content description.
 */
public class AccessibilityLoggerService extends AccessibilityService {
    private static final String TAG = "AccessibilityLogger";

    /**
     * Writer for CSV log lines. Created lazily when the first event is logged.
     */
    private FileWriter csvWriter;

    /**
     * Writer for JSONL log lines. Each line is a JSON object representing a single event.
     */
    private FileWriter jsonWriter;

    /**
     * Resets the CSV and JSON writers.  When a new recording session starts we
     * want to close any open file handles and rotate the previous logs to
     * prevent stale writers from blocking future writes.  This method renames
     * existing log files with a timestamp suffix, clears the last event
     * timestamp and sets the writers to {@code null} so that they are
     * recreated on demand.  It can safely be called from the main thread.
     */
    public synchronized void resetWriters() {
        // Close any existing writers
        try {
            if (csvWriter != null) {
                csvWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing CSV writer", e);
        }
        try {
            if (jsonWriter != null) {
                jsonWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing JSON writer", e);
        }
        csvWriter = null;
        jsonWriter = null;
        // Rotate previous log files by renaming them with the current timestamp
        try {
            File baseDir = Environment.getExternalStorageDirectory();
            File dir = new File(baseDir, "clicka/payzy");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            long now = System.currentTimeMillis();
            // rename CSV file if it exists
            File csvFile = new File(dir, "events.csv");
            if (csvFile.exists()) {
                File dest = new File(dir, "events-" + now + ".csv");
                boolean ok = csvFile.renameTo(dest);
                if (!ok) {
                    Log.w(TAG, "Could not rename CSV log file");
                }
            }
            // rename JSONL file if it exists
            File jsonFile = new File(dir, "events.jsonl");
            if (jsonFile.exists()) {
                File dest = new File(dir, "events-" + now + ".jsonl");
                boolean ok = jsonFile.renameTo(dest);
                if (!ok) {
                    Log.w(TAG, "Could not rename JSON log file");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rotating log files", e);
        }
        // Reset the last event timestamp so that deltas start fresh
        lastEventTimestamp = -1;
    }

    /**
     * Timestamp of the last recorded event. Used to compute deltas between events.
     */
    private long lastEventTimestamp = -1;

    /**
     * Holds a reference to the currently running instance of this service. Because
     * accessibility services are instantiated by the system, we cannot create
     * instances directly. This static reference enables the main activity to
     * communicate with the service when it is active. It will be assigned in
     * {@link #onServiceConnected()} and cleared in {@link #onDestroy()}.
     */
    private static AccessibilityLoggerService instance;

    /**
     * Tracks the current login attempt. Each call to {@link #performLoginApproach} increments
     * this ID. Background scanning loops check this value to determine whether they
     * should continue running or be cancelled in favour of a newer login attempt.
     */
    private int currentLoginAttemptId = 0;

    /**
     * Indicates whether the login watcher is currently active. When true, the service
     * continuously scans the UI for login elements and performs the login when found.
     */
    private volatile boolean loginWatcherActive = false;

    /**
     * Unique identifier for the current login watcher thread. Incremented each time
     * the watcher is toggled. Used to cancel a running watcher when a new one starts.
     */
    private volatile int loginWatcherId = 0;

    /**
     * Indicates whether the wallet watcher is currently active. When true, the service
     * scans the UI for elements containing the word "Guthaben" and updates the
     * displayed balance when found.
     */
    private volatile boolean walletWatcherActive = false;

    /**
     * Unique identifier for the current wallet watcher thread.
     */
    private volatile int walletWatcherId = 0;

    /**
     * Indicates whether the coins watcher is currently active. When true, the service
     * scans the UI for elements containing the word "Coins" and updates the
     * displayed coin count when found.
     */
    private volatile boolean coinsWatcherActive = false;

    /**
     * Unique identifier for the current coins watcher thread.
     */
    private volatile int coinsWatcherId = 0;

    /**
     * Indicates whether the wallet top‑up watcher is currently active.  When true,
     * the service monitors the wallet balance and attempts to initiate an
     * auto top‑up when the balance falls below the configured threshold.
     */
    private volatile boolean walletTopupWatcherActive = false;

    /**
     * Unique identifier for the current wallet top‑up watcher thread.
     */
    private volatile int walletTopupWatcherId = 0;

    /**
     * Indicates whether the coins redemption watcher is active.  When true, the
     * service monitors the coin balance and attempts to redeem coins once the
     * balance meets or exceeds the configured minimum.
     */
    private volatile boolean coinsRedeemWatcherActive = false;

    /**
     * Unique identifier for the current coins redemption watcher thread.
     */
    private volatile int coinsRedeemWatcherId = 0;

    /**
     * Indicates whether the transactions watcher is active.  When true, the
     * service scans the transaction list in the Payzy app, exports new
     * transactions to a CSV file and updates the last processed transaction ID.
     */
    private volatile boolean transactionsWatcherActive = false;

    /**
     * Unique identifier for the current transactions watcher thread.
     */
    private volatile int transactionsWatcherId = 0;

    /**
     * Records the timestamp of the last coin redemption attempt.  Used to
     * prevent repeated redemption attempts in quick succession.  A value of
     * zero means no redemption has been attempted yet.
     */
    private volatile long lastCoinsRedeemTimestamp = 0L;

    /**
     * Records the timestamp of the last successful login performed by the login watcher.
     * This is used to avoid repeated login attempts in quick succession. A value of
     * zero indicates no login has been performed yet.
     */
    private long lastLoginWatcherTimestamp = 0L;

    /**
     * Determines whether the given package name is allowed for automated interactions.
     * The list of allowed packages is configured by the user via the main UI. If the
     * list is empty, all packages except this app's own package are allowed. When the
     * root window's package is not in the allowed list, watchers will silently skip
     * any actions for that window.
     *
     * @param packageName the package name of the active window
     * @return true if interactions should be permitted
     */
    private boolean isPackageAllowed(String packageName) {
        if (packageName == null) {
            return false;
        }
        String[] allowed = PrefsHelper.getAllowedPackagesList(this);
        // Do not interact with our own app
        if (packageName.equals(getPackageName())) {
            return false;
        }
        // If no packages specified, allow all others
        if (allowed == null || allowed.length == 0) {
            return true;
        }
        for (String p : allowed) {
            if (p != null && !p.isEmpty() && p.equalsIgnoreCase(packageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // Configure the service to listen for all events. This call is optional if the
        // static xml configuration already specifies the same flags, but helps ensure
        // consistency when the service is restarted programmatically.
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.DEFAULT;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!PrefsHelper.isRecording(this)) {
            return;
        }
        // Log extended information about the event. This includes coordinates, class name,
        // view ID and textual content. The logged record is written both as CSV and
        // as JSON for easier post‑processing.
        logEvent(event);
    }

    @Override
    public void onInterrupt() {
        // No special interrupt handling required.
    }

    /**
     * Returns the currently active instance of this service, or {@code null} if
     * the service has not been started by the user. The returned instance can be
     * used to perform automated interactions such as entering credentials and
     * clicking buttons.
     */
    public static AccessibilityLoggerService getInstance() {
        return instance;
    }

    /**
     * Displays a short toast message on the main UI thread. The service runs on
     * its own thread, so toasts must be posted to the main looper.
     *
     * @param message the message to display
     */
    private void showToast(final String message) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(AccessibilityLoggerService.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Searches the view hierarchy for nodes matching the specified criteria. This helper
     * method collects all password fields in the tree by checking the class name and
     * password property. Matching nodes are cloned and added to the provided list. The
     * original nodes should not be recycled while still in use.
     *
     * @param node the root node to search
     * @param out a list to collect matching nodes
     */
    private void collectPasswordFields(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        try {
            CharSequence className = node.getClassName();
            if (className != null && "android.widget.EditText".contentEquals(className)) {
                if (node.isPassword()) {
                    out.add(AccessibilityNodeInfo.obtain(node));
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                collectPasswordFields(child, out);
                if (child != null) child.recycle();
            }
        } catch (Exception e) {
            // Ignore exceptions while traversing
        }
    }

    /**
     * Performs the login automation by detecting the welcome screen, filling in the password
     * and clicking the login button. This method runs on the service thread and
     * interacts with the accessibility nodes directly. If the required UI elements
     * cannot be found, the method silently returns.
     *
     * @param username the user name used to detect the welcome screen
     * @param password the password to enter into the password field
     */
    public void performLogin(String username, String password) {
        performLoginApproach(1, username, password);
    }

    /**
     * Performs the login automation using the specified approach. This method delegates
     * to different internal strategies based on the {@code approach} parameter. See
     * individual strategies for details. Unknown approaches are ignored.
     *
     * @param approach an integer between 1 and 5 denoting the strategy
     * @param username the user name (used by some strategies)
     * @param password the password to enter into the password field
     */
    public void performLoginApproach(final int approach, final String username, final String password) {
        // Increment the login attempt ID to cancel any previous background loops
        final int attemptId = ++currentLoginAttemptId;
        showToast("Login Ansatz " + approach + " gestartet");
        // Launch scanning loop in a background thread so as not to block the service
        new Thread(new Runnable() {
            @Override
            public void run() {
                runLoginLoop(approach, username, password, attemptId);
            }
        }).start();
    }

    /**
     * Toggles the login watcher on or off. When enabled, the service will repeatedly
     * attempt to detect the login screen and perform the login using a combination
     * of strategies 2 and 4 (password field detection and partial text matching).
     *
     * @param username the user name to use when performing the login
     * @param password the password to enter
     * @return {@code true} if the watcher is now active, {@code false} if it was
     *         stopped
     */
    public synchronized boolean toggleLoginWatcher(final String username, final String password) {
        if (loginWatcherActive) {
            // Stop existing watcher
            loginWatcherActive = false;
            loginWatcherId++;
            showToast("Login-Watcher gestoppt");
            return false;
        } else {
            // Start new watcher
            loginWatcherActive = true;
            final int watcherId = ++loginWatcherId;
            showToast("Login-Watcher gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoginWatcherLoop(username, password, watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Toggles the wallet watcher on or off. When enabled, the service will scan
     * continuously for UI elements containing the word "Guthaben", extract the
     * balance value, update the main UI and click the element. When disabled,
     * the scanning thread is cancelled.
     *
     * @return {@code true} if the watcher is now active, {@code false} if it was
     *         stopped
     */
    public synchronized boolean toggleWalletWatcher() {
        if (walletWatcherActive) {
            walletWatcherActive = false;
            walletWatcherId++;
            showToast("Wallet-Watcher gestoppt");
            return false;
        } else {
            walletWatcherActive = true;
            final int watcherId = ++walletWatcherId;
            showToast("Wallet-Watcher gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runWalletWatcherLoop(watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Toggles the coins watcher on or off. When enabled, the service will scan
     * continuously for UI elements containing the word "Coins", extract the
     * coins amount, update the main UI and click the element. When disabled,
     * the scanning thread is cancelled.
     *
     * @return {@code true} if the watcher is now active, {@code false} if it was
     *         stopped
     */
    public synchronized boolean toggleCoinsWatcher() {
        if (coinsWatcherActive) {
            coinsWatcherActive = false;
            coinsWatcherId++;
            showToast("Coins-Watcher gestoppt");
            return false;
        } else {
            coinsWatcherActive = true;
            final int watcherId = ++coinsWatcherId;
            showToast("Coins-Watcher gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runCoinsWatcherLoop(watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Toggles the wallet top‑up watcher on or off. When enabled, the service
     * continuously monitors the wallet balance and compares it against the
     * configured minimum threshold.  When the balance falls below this
     * threshold, the service attempts to initiate a top‑up by navigating to
     * the wallet screen, entering the appropriate amount and confirming
     * payment with the stored PIN.  When disabled, the scanning thread is
     * cancelled.
     *
     * @return {@code true} if the watcher is now active, {@code false} if it
     *         was stopped
     */
    public synchronized boolean toggleWalletTopupWatcher() {
        if (walletTopupWatcherActive) {
            walletTopupWatcherActive = false;
            walletTopupWatcherId++;
            showToast("Auto-Aufladen gestoppt");
            return false;
        } else {
            walletTopupWatcherActive = true;
            final int watcherId = ++walletTopupWatcherId;
            showToast("Auto-Aufladen gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runWalletTopupWatcherLoop(watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Toggles the coins redemption watcher on or off.  When enabled, the
     * service monitors the coin balance and automatically attempts to redeem
     * coins when the balance meets or exceeds the configured minimum.  The
     * watcher will use the stored PIN to authorise the redemption.  When
     * disabled, the scanning thread is cancelled.
     *
     * @return {@code true} if the watcher is now active; {@code false} if it
     *         was stopped
     */
    public synchronized boolean toggleCoinsRedeemWatcher() {
        if (coinsRedeemWatcherActive) {
            coinsRedeemWatcherActive = false;
            coinsRedeemWatcherId++;
            showToast("Coins-Einlösen gestoppt");
            return false;
        } else {
            coinsRedeemWatcherActive = true;
            final int watcherId = ++coinsRedeemWatcherId;
            showToast("Coins-Einlösen gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runCoinsRedeemWatcherLoop(watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Toggles the transactions watcher on or off.  When enabled, the service
     * scans the transaction history in the Payzy app, collects new
     * transactions into a CSV and updates the last processed transaction ID.
     * When disabled, the scanning thread is cancelled.
     *
     * @return {@code true} if the watcher is now active; {@code false} if it
     *         was stopped
     */
    public synchronized boolean toggleTransactionsWatcher() {
        if (transactionsWatcherActive) {
            transactionsWatcherActive = false;
            transactionsWatcherId++;
            showToast("Transaktionen gestoppt");
            return false;
        } else {
            transactionsWatcherActive = true;
            final int watcherId = ++transactionsWatcherId;
            showToast("Transaktionen gestartet");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runTransactionsWatcherLoop(watcherId);
                }
            }).start();
            return true;
        }
    }

    /**
     * Strategy 1: Use the welcome text and search by password field and login button text.
     */
    private void loginStrategy1(String username, String password) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            String welcomeText = "Willkommen," + username;
            java.util.List<AccessibilityNodeInfo> welcomeNodes = root.findAccessibilityNodeInfosByText(welcomeText);
            boolean welcomeDetected = welcomeNodes != null && !welcomeNodes.isEmpty();
            if (!welcomeDetected) {
                return;
            }
            // Enter password via password field detection
            fillPasswordField(root, password);
            clickLoginButtonByText(root);
        } catch (Exception e) {
            Log.e(TAG, "Strategy1 failed", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Strategy 2: Ignore the welcome text and search for a password field (by isPassword) and the
     * login button by its text.
     */
    private void loginStrategy2(String username, String password) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            fillPasswordField(root, password);
            clickLoginButtonByText(root);
        } catch (Exception e) {
            Log.e(TAG, "Strategy2 failed", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Strategy 3: Search nodes by the approximate bounding boxes recorded in the event log.
     */
    private void loginStrategy3(String username, String password) {
        // bounding boxes from the provided CSV/JSON logs
        android.graphics.Rect passwordBounds = new android.graphics.Rect(84, 811, 996, 952);
        android.graphics.Rect loginBounds = new android.graphics.Rect(172, 1981, 909, 2130);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // find node matching password bounds
            AccessibilityNodeInfo pwdNode = findNodeByBounds(root, passwordBounds, 20);
            if (pwdNode != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // find node matching login bounds and click
            AccessibilityNodeInfo loginNode = findNodeByBounds(root, loginBounds, 20);
            if (loginNode != null) {
                loginNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                loginNode.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Strategy3 failed", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Strategy 4: Use the first EditText encountered (regardless of password flag) and the first
     * clickable node containing the text "Anmelden" or a similar pattern.
     */
    private void loginStrategy4(String username, String password) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // find first EditText
            AccessibilityNodeInfo firstEdit = findFirstEditText(root);
            if (firstEdit != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                firstEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                firstEdit.recycle();
            }
            // click any node with text containing "Anmeld"
            clickLoginButtonByPartialText(root, "Anmeld");
        } catch (Exception e) {
            Log.e(TAG, "Strategy4 failed", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Strategy 5: Attempt to click any clickable node with class Button or TextView whose
     * contentDescription or text contains "Anmelden"; as a fallback, use global coordinate
     * gesture to tap on the centre of the login bounds.
     */
    private void loginStrategy5(String username, String password) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            fillPasswordField(root, password);
            // look for clickable nodes with partial match in text or contentDescription
            java.util.List<AccessibilityNodeInfo> candidates = new java.util.ArrayList<>();
            collectClickableNodes(root, candidates);
            boolean clicked = false;
            for (AccessibilityNodeInfo node : candidates) {
                try {
                    CharSequence t = node.getText();
                    CharSequence cd = node.getContentDescription();
                    String textStr = t != null ? t.toString() : "";
                    String cdStr = cd != null ? cd.toString() : "";
                    if ((textStr != null && textStr.toLowerCase().contains("anmelden")) ||
                        (cdStr != null && cdStr.toLowerCase().contains("anmelden"))) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        clicked = true;
                        break;
                    }
                } finally {
                    node.recycle();
                }
            }
            if (!clicked) {
                // fallback: click approximate centre of known login bounds
                android.graphics.Rect loginBounds = new android.graphics.Rect(172, 1981, 909, 2130);
                float cx = (loginBounds.left + loginBounds.right) / 2f;
                float cy = (loginBounds.top + loginBounds.bottom) / 2f;
                performClickAt(cx, cy);
            }
        } catch (Exception e) {
            Log.e(TAG, "Strategy5 failed", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Helper to fill the first detected password field with the given password.
     */
    private void fillPasswordField(AccessibilityNodeInfo root, String password) {
        java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
        collectPasswordFields(root, passwords);
        if (!passwords.isEmpty()) {
            AccessibilityNodeInfo pwdNode = passwords.get(0);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
            pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            pwdNode.recycle();
        }
    }

    /**
     * Helper to click the first clickable node whose text or content description exactly
     * matches "Anmelden".
     */
    private void clickLoginButtonByText(AccessibilityNodeInfo root) {
        java.util.List<AccessibilityNodeInfo> loginNodes = root.findAccessibilityNodeInfosByText("Anmelden");
        if (loginNodes != null) {
            for (AccessibilityNodeInfo btn : loginNodes) {
                try {
                    if (btn.isClickable()) {
                        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        break;
                    }
                } finally {
                    btn.recycle();
                }
            }
        }
    }

    /**
     * Helper to click the first node whose text or content description contains the given
     * substring (case-insensitive).
     */
    private void clickLoginButtonByPartialText(AccessibilityNodeInfo root, String substring) {
        if (substring == null || substring.isEmpty()) return;
        java.util.List<AccessibilityNodeInfo> allNodes = new java.util.ArrayList<>();
        collectClickableNodes(root, allNodes);
        String lower = substring.toLowerCase();
        for (AccessibilityNodeInfo node : allNodes) {
            try {
                CharSequence t = node.getText();
                CharSequence cd = node.getContentDescription();
                String textStr = t != null ? t.toString().toLowerCase() : "";
                String cdStr = cd != null ? cd.toString().toLowerCase() : "";
                if (textStr.contains(lower) || cdStr.contains(lower)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
            } finally {
                node.recycle();
            }
        }
    }

    /**
     * Collects all clickable nodes in the hierarchy rooted at the given node. Clickable nodes
     * are added to the provided list. Nodes returned in the list are clones; they must be
     * recycled after use.
     */
    private void collectClickableNodes(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        try {
            if (node.isClickable()) {
                out.add(AccessibilityNodeInfo.obtain(node));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                collectClickableNodes(child, out);
                if (child != null) child.recycle();
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Finds the first EditText node in the hierarchy (breadth-first). The returned node is
     * cloned and must be recycled by the caller.
     */
    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence className = node.getClassName();
            if (className != null && "android.widget.EditText".contentEquals(className)) {
                // We have a match; return a clone of this node
                return AccessibilityNodeInfo.obtain(node);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
            node.recycle();
        }
        return null;
    }

    /**
     * Searches the hierarchy for the first node whose bounds approximate the specified
     * rectangle. A tolerance value (in pixels) is used to allow small differences. The
     * returned node is cloned and must be recycled by the caller.
     */
    private AccessibilityNodeInfo findNodeByBounds(AccessibilityNodeInfo root, android.graphics.Rect target, int tolerance) {
        if (root == null || target == null) return null;
        java.util.Stack<AccessibilityNodeInfo> stack = new java.util.Stack<>();
        stack.push(AccessibilityNodeInfo.obtain(root));
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            try {
                android.graphics.Rect rect = new android.graphics.Rect();
                node.getBoundsInScreen(rect);
                if (Math.abs(rect.left - target.left) <= tolerance &&
                        Math.abs(rect.top - target.top) <= tolerance &&
                        Math.abs(rect.right - target.right) <= tolerance &&
                        Math.abs(rect.bottom - target.bottom) <= tolerance) {
                    return AccessibilityNodeInfo.obtain(node);
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        stack.push(child);
                    }
                }
            } finally {
                node.recycle();
            }
        }
        return null;
    }

    /**
     * Performs a single tap on the given screen coordinates. Uses the accessibility
     * gesture API introduced in API 24. If the API level is too low or the gesture fails,
     * this method has no effect.
     */
    private void performClickAt(float x, float y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return;
        }
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke = new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
        android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
        builder.addStroke(stroke);
        android.accessibilityservice.GestureDescription gesture = builder.build();
        dispatchGesture(gesture, null, null);
    }

    /**
     * Runs a login loop for the specified approach. The loop attempts up to a fixed
     * number of times (e.g. 10) to locate the necessary UI elements and perform the
     * login actions. Between attempts it sleeps briefly. If the {@code attemptId}
     * no longer matches {@link #currentLoginAttemptId}, the loop will abort early.
     *
     * @param approach the login strategy number (1-10)
     * @param username the user name
     * @param password the password
     * @param attemptId the unique ID for this login attempt
     */
    private void runLoginLoop(int approach, String username, String password, int attemptId) {
        final int MAX_TRIES = 10;
        for (int i = 0; i < MAX_TRIES; i++) {
            // Abort if a newer login attempt has started
            if (attemptId != currentLoginAttemptId) {
                return;
            }
            boolean success = runStrategyOnce(approach, username, password, attemptId);
            if (success) {
                showToast("Ansatz " + approach + ": Login erfolgreich");
                return;
            } else {
                showToast("Ansatz " + approach + ": Versuch " + (i + 1) + " fehlgeschlagen");
            }
            // Wait before trying again
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        // Inform user that the attempts finished without success
        if (attemptId == currentLoginAttemptId) {
            showToast("Ansatz " + approach + ": keine passenden Elemente gefunden");
        }
    }

    /**
     * Executes a single attempt of the specified strategy. Returns {@code true} if the
     * password field was filled and the login button was clicked successfully. This
     * method checks the {@code attemptId} before and after significant operations to
     * cancel early if a newer login attempt has superseded this one.
     *
     * @param approach the strategy number
     * @param username the user name
     * @param password the password
     * @param attemptId the current attempt ID
     * @return true if the attempt completed and clicked the login button
     */
    private boolean runStrategyOnce(int approach, String username, String password, int attemptId) {
        // Abort if cancelled
        if (attemptId != currentLoginAttemptId) return false;
        boolean success = false;
        switch (approach) {
            case 1:
                success = attemptStrategy1(username, password, attemptId);
                break;
            case 2:
                success = attemptStrategy2(username, password, attemptId);
                break;
            case 3:
                success = attemptStrategy3(username, password, attemptId);
                break;
            case 4:
                success = attemptStrategy4(username, password, attemptId);
                break;
            case 5:
                success = attemptStrategy5(username, password, attemptId);
                break;
            case 6:
                success = attemptStrategy6(username, password, attemptId);
                break;
            case 7:
                success = attemptStrategy7(username, password, attemptId);
                break;
            case 8:
                success = attemptStrategy8(username, password, attemptId);
                break;
            case 9:
                success = attemptStrategy9(username, password, attemptId);
                break;
            case 10:
                success = attemptStrategy10(username, password, attemptId);
                break;
            default:
                showToast("Unbekannter Login-Ansatz: " + approach);
                return false;
        }
        return success;
    }

    /**
     * Continuously scans for login elements and performs the login when found. The
     * loop runs until the watcher is deactivated or a newer watcher is started.
     *
     * @param username the user name for the login
     * @param password the password for the login
     * @param watcherId the identifier for this watcher instance
     */
    private void runLoginWatcherLoop(String username, String password, int watcherId) {
        while (loginWatcherActive && watcherId == loginWatcherId) {
            // If we recently performed a login, wait a few seconds before attempting again
            long now = System.currentTimeMillis();
            if (lastLoginWatcherTimestamp > 0 && (now - lastLoginWatcherTimestamp) < 5000) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            boolean success = attemptLoginWatcher(username, password, watcherId);
            if (success) {
                lastLoginWatcherTimestamp = System.currentTimeMillis();
                showToast("Login-Watcher: Login durchgeführt");
                // Do not stop the watcher; continue scanning for future logins
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        // When the loop exits because the watcher was toggled off or superseded, update the UI
        MainActivity.updateLoginWatcherButtonStatic(false);
    }

    /**
     * Attempts a single login operation using a combination of strategies. This
     * method first tries strategy 2 (password field + exact login button text) and
     * if that fails, falls back to strategy 4 (first EditText + partial match).
     *
     * @param username the user name
     * @param password the password
     * @param watcherId the current watcher id
     * @return true if a login attempt was executed (i.e., the login button was clicked)
     */
    private boolean attemptLoginWatcher(String username, String password, int watcherId) {
        // Determine the package of the active window. Skip if not allowed.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            CharSequence pkgCs = root.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : "";
            if (!isPackageAllowed(pkg)) {
                // Do nothing if current app is not allowed
                return false;
            }
        } finally {
            root.recycle();
        }
        // We ignore watcherId here because runLoginWatcherLoop handles cancellation
        // First try strategy2
        boolean success = attemptStrategy2(username, password, -1);
        if (!success) {
            success = attemptStrategy4(username, password, -1);
        }
        return success;
    }

    /**
     * Continuously scans for Guthaben elements and updates the stored wallet value. When
     * a matching element is found, its text is parsed to extract the balance and
     * the element is clicked. The loop runs until deactivated or superseded.
     *
     * @param watcherId the identifier for this watcher instance
     */
    private void runWalletWatcherLoop(int watcherId) {
        while (walletWatcherActive && watcherId == walletWatcherId) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence pkgCs = root.getPackageName();
                    String pkg = pkgCs != null ? pkgCs.toString() : "";
                    if (isPackageAllowed(pkg)) {
                        boolean found = false;
                        // Breadth‑first search for nodes containing a balance indicator
                        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
                        queue.add(AccessibilityNodeInfo.obtain(root));
                        while (!queue.isEmpty() && !found) {
                            AccessibilityNodeInfo n = queue.removeFirst();
                            try {
                                CharSequence t = n.getText();
                                CharSequence cd = n.getContentDescription();
                                String ts = t != null ? t.toString() : "";
                                String cs = cd != null ? cd.toString() : "";
                                String lower = (ts + " " + cs).toLowerCase();
                                if (lower.contains("guthaben") || ts.contains("€") || cs.contains("€")) {
                                    // Attempt to extract a numeric value from either text or content description
                                    String full = !ts.isEmpty() ? ts : cs;
                                    String value = extractNumericValue(full);
                                    if (value == null || value.isEmpty()) {
                                        // As a fallback, search the other string
                                        String alt = ts.isEmpty() ? cs : ts;
                                        value = extractNumericValue(alt);
                                    }
                                    if (value != null && !value.isEmpty()) {
                                        PrefsHelper.setWalletValue(this, value);
                                        MainActivity.updateWalletValueStatic(value);
                                        showToast("Guthaben erkannt: " + value);
                                        // click the element if clickable to follow navigation
                                        if (n.isClickable()) {
                                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        }
                                        found = true;
                                        break;
                                    }
                                }
                                // continue BFS
                                for (int i = 0; i < n.getChildCount(); i++) {
                                    AccessibilityNodeInfo child = n.getChild(i);
                                    if (child != null) {
                                        queue.addLast(child);
                                    }
                                }
                            } finally {
                                n.recycle();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "WalletWatcher error", e);
                } finally {
                    root.recycle();
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        // When loop exits, update button label on the main UI
        MainActivity.updateWalletWatcherButtonStatic(false);
    }

    /**
     * Continuously scans for Coins elements and updates the stored coin count. When
     * a matching element is found, its text is parsed to extract the amount and
     * the element is clicked. The loop runs until deactivated or superseded.
     *
     * @param watcherId the identifier for this watcher instance
     */
    private void runCoinsWatcherLoop(int watcherId) {
        while (coinsWatcherActive && watcherId == coinsWatcherId) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence pkgCs = root.getPackageName();
                    String pkg = pkgCs != null ? pkgCs.toString() : "";
                    if (isPackageAllowed(pkg)) {
                        boolean found = false;
                        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
                        queue.add(AccessibilityNodeInfo.obtain(root));
                        while (!queue.isEmpty() && !found) {
                            AccessibilityNodeInfo n = queue.removeFirst();
                            try {
                                CharSequence t = n.getText();
                                CharSequence cd = n.getContentDescription();
                                String ts = t != null ? t.toString() : "";
                                String cs = cd != null ? cd.toString() : "";
                                String lower = (ts + " " + cs).toLowerCase();
                                if (lower.contains("coins") || lower.contains("coin") || lower.contains("punkte")) {
                                    // Extract numeric portion
                                    String full = !ts.isEmpty() ? ts : cs;
                                    String value = extractNumericValue(full);
                                    if (value == null || value.isEmpty()) {
                                        String alt = ts.isEmpty() ? cs : ts;
                                        value = extractNumericValue(alt);
                                    }
                                    if (value != null && !value.isEmpty()) {
                                        PrefsHelper.setCoinsValue(this, value);
                                        MainActivity.updateCoinsValueStatic(value);
                                        showToast("Coins erkannt: " + value);
                                        // Click the element to navigate into the coins screen
                                        if (n.isClickable()) {
                                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        }
                                        // Do not automatically redeem coins here.  Redemption logic
                                        // is handled in the separate coins redemption watcher.
                                        found = true;
                                        break;
                                    }
                                }
                                for (int i = 0; i < n.getChildCount(); i++) {
                                    AccessibilityNodeInfo child = n.getChild(i);
                                    if (child != null) {
                                        queue.addLast(child);
                                    }
                                }
                            } finally {
                                n.recycle();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "CoinsWatcher error", e);
                } finally {
                    root.recycle();
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        MainActivity.updateCoinsWatcherButtonStatic(false);
    }

    /**
     * Continuously monitors the wallet balance and triggers an automatic top‑up
     * when the balance falls below the user‑defined minimum.  The watcher
     * navigates into the wallet screen, enters the difference between the
     * desired minimum and the current balance and attempts to confirm the
     * payment using the stored PIN.  Because UI layouts may vary between
     * app versions, this implementation uses simple heuristics (first
     * EditText, clickable buttons with text like "Aufladen", "Weiter" or
     * "Bestätigen").  The loop terminates when the watcher is disabled
     * or superseded by a newer instance.
     *
     * @param watcherId the identifier for this watcher instance
     */
    private void runWalletTopupWatcherLoop(int watcherId) {
        while (walletTopupWatcherActive && watcherId == walletTopupWatcherId) {
            // Determine configured minimum balance
            String minStr = PrefsHelper.getMinWallet(this);
            double minBalance = 0.0;
            if (minStr != null && !minStr.trim().isEmpty()) {
                try {
                    minBalance = Double.parseDouble(minStr.replace(',', '.'));
                } catch (NumberFormatException e) {
                    minBalance = 0.0;
                }
            }
            // If no threshold defined, skip processing
            if (minBalance <= 0) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            // Get current wallet value from preferences
            String currentStr = PrefsHelper.getWalletValue(this);
            double currentBalance = 0.0;
            if (currentStr != null && !currentStr.trim().isEmpty()) {
                try {
                    String cleaned = currentStr.replaceAll("[^0-9.,]", "").replace(',', '.');
                    currentBalance = Double.parseDouble(cleaned);
                } catch (NumberFormatException e) {
                    currentBalance = 0.0;
                }
            }
            // If the current balance is below threshold, attempt top‑up
            if (currentBalance < minBalance) {
                double deficit = minBalance - currentBalance;
                showToast("Versuche Guthaben aufzuladen: aktuelles Guthaben=" + currentBalance + ", Ziel=" + minBalance);
                try {
                    performWalletTopup(deficit);
                } catch (Exception e) {
                    Log.e(TAG, "Fehler bei Auto-Aufladung", e);
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
        MainActivity.updateWalletTopupWatcherButtonStatic(false);
    }

    /**
     * Continuously monitors the coin balance and triggers an automatic
     * redemption when the balance meets or exceeds the user‑defined minimum.
     * The watcher waits for the coins screen to load and then calls
     * redeemCoins().  A cooldown of five seconds prevents repeated
     * redemptions.
     *
     * @param watcherId the identifier for this watcher instance
     */
    private void runCoinsRedeemWatcherLoop(int watcherId) {
        while (coinsRedeemWatcherActive && watcherId == coinsRedeemWatcherId) {
            String minStr = PrefsHelper.getMinCoins(this);
            int minCoins = 0;
            if (minStr != null && !minStr.trim().isEmpty()) {
                try {
                    minCoins = Integer.parseInt(minStr.trim());
                } catch (NumberFormatException e) {
                    minCoins = 0;
                }
            }
            if (minCoins <= 0) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            String currentStr = PrefsHelper.getCoinsValue(this);
            int currentCoins = 0;
            if (currentStr != null && !currentStr.trim().isEmpty()) {
                try {
                    String cleaned = currentStr.replaceAll("[^0-9]", "");
                    currentCoins = Integer.parseInt(cleaned);
                } catch (NumberFormatException e) {
                    currentCoins = 0;
                }
            }
            if (currentCoins >= minCoins) {
                long now = System.currentTimeMillis();
                // Respect cooldown between attempts
                if ((now - lastCoinsRedeemTimestamp) >= 5000) {
                    lastCoinsRedeemTimestamp = now;
                    final int amount = currentCoins;
                    final String pin = PrefsHelper.getPin(this);
                    if (pin == null || pin.isEmpty()) {
                        showToast("PIN nicht gesetzt – kann Coins nicht einlösen");
                    } else {
                        showToast("Einlösen von " + amount + " Coins");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(1500);
                                    redeemCoins(amount, pin);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }).start();
                    }
                }
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
        MainActivity.updateCoinsRedeemWatcherButtonStatic(false);
    }

    /**
     * Continuously scans the transaction list within the Payzy app and writes
     * new transactions to a CSV file.  The watcher will scroll the list
     * downward and collect the textual content of each visible item.  Once
     * the watcher encounters the last processed transaction ID (stored in
     * preferences) it stops collecting.  The watcher persists the ID of the
     * newest transaction at the start of each run.  If no new transactions
     * are found, the watcher sleeps briefly and tries again.  Because
     * transaction screens can vary, this implementation uses simple heuristics
     * to detect list items (nodes containing a € symbol or the word "€").
     *
     * @param watcherId the identifier for this watcher instance
     */
    private void runTransactionsWatcherLoop(int watcherId) {
        // Create a CSV file in the clicka/payzy directory
        File baseDir = Environment.getExternalStorageDirectory();
        File dir = new File(baseDir, "clicka/payzy");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File csvFile = new File(dir, "transactions.csv");
        while (transactionsWatcherActive && watcherId == transactionsWatcherId) {
            // Determine last processed ID from prefs
            String lastId = PrefsHelper.getLastTransactionId(this);
            java.util.List<String[]> newRows = new java.util.ArrayList<>();
            String newestId = null;
            boolean reachedLastId = false;
            // Attempt to collect transactions from current screen
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence pkgCs = root.getPackageName();
                    String pkg = pkgCs != null ? pkgCs.toString() : "";
                    if (!isPackageAllowed(pkg)) {
                        // Do not process if app not allowed
                        // Wait a bit and retry
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    // BFS through visible nodes, capture rows containing €
                    java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
                    queue.add(AccessibilityNodeInfo.obtain(root));
                    java.util.Set<String> seenIds = new java.util.HashSet<>();
                    while (!queue.isEmpty() && !reachedLastId) {
                        AccessibilityNodeInfo n = queue.removeFirst();
                        try {
                            CharSequence t = n.getText();
                            CharSequence cd = n.getContentDescription();
                            String text = t != null ? t.toString() : "";
                            String desc = cd != null ? cd.toString() : "";
                            // We assume a transaction item contains a euro symbol or comma/period separated amount
                            if (!text.isEmpty() && (text.contains("€") || text.matches(".*[0-9],[0-9]{2}.*"))) {
                                // Build a row string: gather all visible text from node and children
                                StringBuilder builder = new StringBuilder();
                                gatherTexts(n, builder);
                                String rowText = builder.toString().trim();
                                // Determine a simple ID for this transaction: first 10 characters of row
                                String id = rowText.length() > 10 ? rowText.substring(0, 10) : rowText;
                                if (newestId == null) {
                                    newestId = id;
                                }
                                if (id.equals(lastId)) {
                                    reachedLastId = true;
                                    break;
                                }
                                if (!seenIds.contains(id)) {
                                    seenIds.add(id);
                                    newRows.add(new String[]{id, rowText});
                                }
                            }
                            for (int i = 0; i < n.getChildCount(); i++) {
                                AccessibilityNodeInfo child = n.getChild(i);
                                if (child != null) {
                                    queue.addLast(child);
                                }
                            }
                        } finally {
                            n.recycle();
                        }
                    }
                    // If we didn't find any items, scroll down to load more
                    if (!reachedLastId && newRows.isEmpty()) {
                        // attempt to scroll forward on any scrollable node
                        boolean scrolled = scrollForward(root);
                        // Wait a moment before scanning again
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        if (!scrolled) {
                            // Unable to scroll; break to prevent infinite loop
                            reachedLastId = true;
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
            // Persist new rows to CSV
            if (!newRows.isEmpty()) {
                try {
                    java.io.FileWriter writer = new java.io.FileWriter(csvFile, true);
                    for (String[] row : newRows) {
                        writer.append(row[0]).append(",").append(row[1].replaceAll(",", ";")).append("\n");
                    }
                    writer.flush();
                    writer.close();
                    // Update newest transaction ID to prefs for next run
                    PrefsHelper.setLastTransactionId(this, newestId);
                    showToast(newRows.size() + " neue Transaktionen gespeichert");
                } catch (Exception e) {
                    Log.e(TAG, "Fehler beim Schreiben von Transaktionen", e);
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
        MainActivity.updateTransactionsWatcherButtonStatic(false);
    }

    /**
     * Navigates through the Payzy wallet UI to perform a top‑up of the
     * specified amount.  The method locates the wallet card, clicks it,
     * clicks an "Aufladen" button, fills in the amount in the first
     * available EditText, selects the first available payment method (if
     * necessary) and attempts to confirm the transaction via a button
     * labelled "Weiter" or "Bestätigen".  Finally, it enters the stored
     * PIN if prompted.  This implementation is based on the user‑provided
     * accessibility logs and may need adjustments for different versions of
     * the Payzy app.
     *
     * @param amount the amount to top up in euros
     */
    private void performWalletTopup(double amount) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // Step 1: find and click a node containing "Guthaben" to open wallet details
            boolean walletOpened = false;
            java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
            queue.add(AccessibilityNodeInfo.obtain(root));
            while (!queue.isEmpty() && !walletOpened) {
                AccessibilityNodeInfo n = queue.removeFirst();
                try {
                    String combined = "";
                    CharSequence t = n.getText();
                    CharSequence cd = n.getContentDescription();
                    if (t != null) combined += t.toString();
                    if (cd != null) combined += " " + cd.toString();
                    if (combined.toLowerCase().contains("guthaben")) {
                        if (n.isClickable()) {
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            walletOpened = true;
                            break;
                        }
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    }
                } finally {
                    n.recycle();
                }
            }
            if (!walletOpened) {
                return;
            }
            // Wait for wallet details screen to load
            Thread.sleep(1500);
            AccessibilityNodeInfo detailsRoot = getRootInActiveWindow();
            if (detailsRoot == null) return;
            try {
                // Step 2: find and click a button labelled "Aufladen"
                boolean topupButtonClicked = false;
                java.util.LinkedList<AccessibilityNodeInfo> queue2 = new java.util.LinkedList<>();
                queue2.add(AccessibilityNodeInfo.obtain(detailsRoot));
                while (!queue2.isEmpty() && !topupButtonClicked) {
                    AccessibilityNodeInfo n = queue2.removeFirst();
                    try {
                        if (n.isClickable()) {
                            String text = n.getText() != null ? n.getText().toString().toLowerCase() : "";
                            String cdStr = n.getContentDescription() != null ? n.getContentDescription().toString().toLowerCase() : "";
                            if (text.contains("aufladen") || cdStr.contains("aufladen")) {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                topupButtonClicked = true;
                                break;
                            }
                        }
                        for (int i = 0; i < n.getChildCount(); i++) {
                            AccessibilityNodeInfo child = n.getChild(i);
                            if (child != null) {
                                queue2.addLast(child);
                            }
                        }
                    } finally {
                        n.recycle();
                    }
                }
                if (!topupButtonClicked) {
                    return;
                }
                // Wait for top‑up sheet to appear
                Thread.sleep(1500);
                AccessibilityNodeInfo topupRoot = getRootInActiveWindow();
                if (topupRoot == null) return;
                try {
                    // Step 3: find first EditText and enter amount
                    AccessibilityNodeInfo amountField = findFirstEditText(topupRoot);
                    if (amountField != null) {
                        android.os.Bundle args = new android.os.Bundle();
                        // Format amount with two decimals using comma as decimal separator
                        String formatted = String.format(java.util.Locale.GERMANY, "%.2f", amount);
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatted);
                        amountField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        amountField.recycle();
                        Thread.sleep(500);
                    }
                    // Step 4: select the first payment method if a list of cards is present
                    boolean cardSelected = false;
                    java.util.LinkedList<AccessibilityNodeInfo> queue3 = new java.util.LinkedList<>();
                    queue3.add(AccessibilityNodeInfo.obtain(topupRoot));
                    while (!queue3.isEmpty() && !cardSelected) {
                        AccessibilityNodeInfo n = queue3.removeFirst();
                        try {
                            String text = n.getText() != null ? n.getText().toString() : "";
                            String cdStr = n.getContentDescription() != null ? n.getContentDescription().toString() : "";
                            String combined = (text + " " + cdStr).toLowerCase();
                            // The recorded logs show masked card numbers like "**** 1510"; we simply
                            // select the first clickable item containing "****" or the word
                            // "kredit"
                            if ((combined.contains("****") || combined.contains("kredit")) && n.isClickable()) {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                cardSelected = true;
                                break;
                            }
                            for (int i = 0; i < n.getChildCount(); i++) {
                                AccessibilityNodeInfo child = n.getChild(i);
                                if (child != null) {
                                    queue3.addLast(child);
                                }
                            }
                        } finally {
                            n.recycle();
                        }
                    }
                    // Step 5: click a button labelled "Weiter" or "Bestätigen"
                    boolean confirmClicked = false;
                    java.util.LinkedList<AccessibilityNodeInfo> queue4 = new java.util.LinkedList<>();
                    queue4.add(AccessibilityNodeInfo.obtain(topupRoot));
                    while (!queue4.isEmpty() && !confirmClicked) {
                        AccessibilityNodeInfo n = queue4.removeFirst();
                        try {
                            if (n.isClickable()) {
                                String text = n.getText() != null ? n.getText().toString().toLowerCase() : "";
                                String cdStr = n.getContentDescription() != null ? n.getContentDescription().toString().toLowerCase() : "";
                                if (text.contains("weiter") || text.contains("bestätigen") || cdStr.contains("weiter") || cdStr.contains("bestätigen")) {
                                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    confirmClicked = true;
                                    break;
                                }
                            }
                            for (int i = 0; i < n.getChildCount(); i++) {
                                AccessibilityNodeInfo child = n.getChild(i);
                                if (child != null) {
                                    queue4.addLast(child);
                                }
                            }
                        } finally {
                            n.recycle();
                        }
                    }
                    if (confirmClicked) {
                        // Wait and then enter the PIN on the next screen
                        Thread.sleep(1000);
                        String pin = PrefsHelper.getPin(this);
                        if (pin != null && !pin.isEmpty()) {
                            AccessibilityNodeInfo pinRoot = getRootInActiveWindow();
                            if (pinRoot != null) {
                                try {
                                    java.util.List<AccessibilityNodeInfo> pinFields = new java.util.ArrayList<>();
                                    collectEditTexts(pinRoot, pinFields);
                                    if (!pinFields.isEmpty()) {
                                        if (pinFields.size() == 1) {
                                            AccessibilityNodeInfo field = pinFields.get(0);
                                            android.os.Bundle a = new android.os.Bundle();
                                            a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
                                            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                                            field.recycle();
                                        } else {
                                            for (int i = 0; i < pinFields.size() && i < pin.length(); i++) {
                                                AccessibilityNodeInfo field = pinFields.get(i);
                                                android.os.Bundle a = new android.os.Bundle();
                                                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Character.toString(pin.charAt(i)));
                                                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                                                field.recycle();
                                            }
                                        }
                                    }
                                } finally {
                                    pinRoot.recycle();
                                }
                            }
                        }
                    }
                } finally {
                    topupRoot.recycle();
                }
            } finally {
                detailsRoot.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler bei performWalletTopup", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Recursively appends the text and content description of a node and its
     * children to the provided StringBuilder.  Used by the transactions
     * watcher to assemble a single line of text for each transaction item.
     */
    private void gatherTexts(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        CharSequence t = node.getText();
        CharSequence cd = node.getContentDescription();
        if (t != null) {
            out.append(t.toString()).append(" ");
        }
        if (cd != null) {
            out.append(cd.toString()).append(" ");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                gatherTexts(child, out);
            }
        }
    }

    /**
     * Attempts to scroll forward on any scrollable view within the hierarchy.
     * Returns true if a scroll action was performed.  This helper is used by
     * the transactions watcher when additional list items may be present off
     * screen.
     */
    private boolean scrollForward(AccessibilityNodeInfo root) {
        if (root == null) return false;
        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        boolean scrolled = false;
        while (!queue.isEmpty() && !scrolled) {
            AccessibilityNodeInfo n = queue.removeFirst();
            try {
                if (n.isScrollable()) {
                    scrolled = n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    if (scrolled) {
                        break;
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            } finally {
                n.recycle();
            }
        }
        return scrolled;
    }

    /**
     * Extracts a numeric value from a string containing a number, such as "56,39€ Guthaben"
     * or "15 Coins". The method returns the first group of digits and separators
     * encountered. If no digits are found, the original string is returned.
     *
     * @param full the full text from which to extract the numeric value
     * @return the extracted numeric portion
     */
    private String extractNumericValue(String full) {
        if (full == null) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("[\u00A3\u20AC]?[0-9]+(?:[.,][0-9]+)?");
        java.util.regex.Matcher m = p.matcher(full);
        if (m.find()) {
            return m.group().trim();
        }
        // fallback: return substring before first space
        int idx = full.indexOf(' ');
        if (idx > 0) {
            return full.substring(0, idx);
        }
        return full;
    }

    /**
     * Evaluates whether a coin redemption should be attempted based on the
     * detected balance.  If the numeric portion of {@code valueStr} is 50 or
     * greater and at least five seconds have elapsed since the last attempt,
     * a redemption is triggered on a background thread.  This method is a
     * no‑op when the service is disabled or when the user has not supplied a
     * PIN via the main UI.
     *
     * @param valueStr the raw numeric string extracted from the coins card
     */
    private void maybeRedeemCoins(String valueStr) {
        if (valueStr == null) return;
        // Remove any currency symbols or commas and parse as integer
        String cleaned = valueStr.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) return;
        int coins;
        try {
            coins = Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return;
        }
        if (coins < 50) {
            return;
        }
        long now = System.currentTimeMillis();
        // Only attempt redemption once every 5 seconds to avoid spamming
        if ((now - lastCoinsRedeemTimestamp) < 5000) {
            return;
        }
        lastCoinsRedeemTimestamp = now;
        final int amount = coins;
        final String pin = PrefsHelper.getPin(this);
        if (pin == null || pin.isEmpty()) {
            // Without a PIN we cannot complete the redemption.  Notify the user.
            showToast("PIN nicht gesetzt – kann Coins nicht einlösen");
            return;
        }
        // Run the redemption sequence in a background thread so as not to
        // block the watcher loop.  The sequence waits briefly for the
        // coins screen to load, fills in the desired amount, clicks the
        // redeem button and enters the PIN when prompted.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Give the UI a moment to transition into the coins screen
                    Thread.sleep(1500);
                    redeemCoins(amount, pin);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }).start();
    }

    /**
     * Performs the UI actions necessary to redeem a specified number of coins.  This
     * method searches for the first visible {@link android.widget.EditText} on
     * screen, inputs the coin amount, finds a clickable node with the text
     * "Einlösen" (case‑insensitive) and clicks it.  After the amount is
     * submitted, it attempts to enter the user’s PIN into any visible text
     * fields of length six.  This implementation is based on the recorded
     * accessibility events provided by the user and may need adjustments for
     * different app versions.
     *
     * @param amount the number of coins to redeem
     * @param pin    the six‑digit PIN to authorise the redemption
     */
    private void redeemCoins(int amount, String pin) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // Step 1: find an input field (EditText) to enter the coin amount.  We look
            // for the first EditText in the hierarchy.
            AccessibilityNodeInfo amountField = findFirstEditText(root);
            if (amountField != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Integer.toString(amount));
                amountField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                amountField.recycle();
                // Small pause after entering text
                Thread.sleep(500);
            }
            // Step 2: find and click the redeem button.  We search for any clickable
            // node whose visible text contains "einlösen" (case‑insensitive).
            boolean clicked = false;
            java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
            queue.add(AccessibilityNodeInfo.obtain(root));
            while (!queue.isEmpty() && !clicked) {
                AccessibilityNodeInfo n = queue.removeFirst();
                try {
                    if (n.isClickable()) {
                        CharSequence txt = n.getText();
                        CharSequence cd = n.getContentDescription();
                        String combined = "";
                        if (txt != null) combined += txt.toString();
                        if (cd != null) combined += " " + cd.toString();
                        if (combined.toLowerCase().contains("einlösen") || combined.toLowerCase().contains("einloesen")) {
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            clicked = true;
                            break;
                        }
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    }
                } finally {
                    n.recycle();
                }
            }
            // If we clicked the redeem button, wait for the PIN screen to appear
            if (clicked) {
                Thread.sleep(1000);
                AccessibilityNodeInfo pinRoot = getRootInActiveWindow();
                if (pinRoot != null) {
                    try {
                        // Attempt to enter the PIN.  Some PIN screens present six separate
                        // input fields; others use a single hidden field.  We look for all
                        // EditTexts and fill them sequentially.  If only one is found we
                        // set the entire PIN at once.
                        java.util.List<AccessibilityNodeInfo> pinFields = new java.util.ArrayList<>();
                        collectEditTexts(pinRoot, pinFields);
                        if (!pinFields.isEmpty()) {
                            if (pinFields.size() == 1) {
                                AccessibilityNodeInfo field = pinFields.get(0);
                                android.os.Bundle a = new android.os.Bundle();
                                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
                                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                                field.recycle();
                            } else {
                                // If there are multiple fields, fill each with one digit
                                for (int i = 0; i < pinFields.size() && i < pin.length(); i++) {
                                    AccessibilityNodeInfo field = pinFields.get(i);
                                    android.os.Bundle a = new android.os.Bundle();
                                    a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Character.toString(pin.charAt(i)));
                                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                                    field.recycle();
                                }
                            }
                        }
                    } finally {
                        pinRoot.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during coin redemption", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Recursively collects all EditText nodes into the provided list.  This helper is
     * used when entering the PIN for coin redemption.  Nodes are not recycled in
     * this method; the caller must handle recycling.
     *
     * @param node the root of the hierarchy
     * @param out  list to collect EditText nodes
     */
    private void collectEditTexts(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().equals("android.widget.EditText")) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectEditTexts(child, out);
            }
        }
    }

    /*
     * The following attemptStrategyX methods implement single attempts for each login
     * strategy. They return true if the login button was clicked. Toast messages
     * provide feedback on what elements were found or missing.
     */

    private boolean attemptStrategy1(String username, String password, int attemptId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        boolean success = false;
        try {
            String welcomeText = "Willkommen," + username;
            java.util.List<AccessibilityNodeInfo> welcomeNodes = root.findAccessibilityNodeInfosByText(welcomeText);
            boolean welcomeDetected = welcomeNodes != null && !welcomeNodes.isEmpty();
            showToast("Ansatz 1: Willkommen gefunden=" + welcomeDetected);
            if (!welcomeDetected) {
                return false;
            }
            // Password field
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            boolean pwdFound = !passwords.isEmpty();
            showToast("Ansatz 1: Passwortfeld gefunden=" + pwdFound);
            if (pwdFound) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // Login button
            java.util.List<AccessibilityNodeInfo> loginNodes = root.findAccessibilityNodeInfosByText("Anmelden");
            boolean loginClicked = false;
            if (loginNodes != null) {
                for (AccessibilityNodeInfo btn : loginNodes) {
                    try {
                        if (btn.isClickable()) {
                            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            loginClicked = true;
                            break;
                        }
                    } finally {
                        btn.recycle();
                    }
                }
            }
            showToast("Ansatz 1: Login-Knopf geklickt=" + loginClicked);
            success = loginClicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy1 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy2(String username, String password, int attemptId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        boolean success = false;
        try {
            // Password field
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            boolean pwdFound = !passwords.isEmpty();
            showToast("Ansatz 2: Passwortfeld gefunden=" + pwdFound);
            if (pwdFound) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // Login button by exact text
            java.util.List<AccessibilityNodeInfo> loginNodes = root.findAccessibilityNodeInfosByText("Anmelden");
            boolean loginClicked = false;
            if (loginNodes != null) {
                for (AccessibilityNodeInfo btn : loginNodes) {
                    try {
                        if (btn.isClickable()) {
                            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            loginClicked = true;
                            break;
                        }
                    } finally {
                        btn.recycle();
                    }
                }
            }
            showToast("Ansatz 2: Login-Knopf geklickt=" + loginClicked);
            success = loginClicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy2 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy3(String username, String password, int attemptId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            android.graphics.Rect passwordBounds = new android.graphics.Rect(84, 811, 996, 952);
            android.graphics.Rect loginBounds = new android.graphics.Rect(172, 1981, 909, 2130);
            AccessibilityNodeInfo pwdNode = findNodeByBounds(root, passwordBounds, 20);
            boolean pwdFound = pwdNode != null;
            showToast("Ansatz 3: Passwort-Feld per Bounds gefunden=" + pwdFound);
            if (pwdFound) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            AccessibilityNodeInfo loginNode = findNodeByBounds(root, loginBounds, 20);
            boolean loginClicked = false;
            if (loginNode != null) {
                loginNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                loginNode.recycle();
                loginClicked = true;
            }
            showToast("Ansatz 3: Login-Knopf per Bounds geklickt=" + loginClicked);
            success = loginClicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy3 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy4(String username, String password, int attemptId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            AccessibilityNodeInfo firstEdit = findFirstEditText(root);
            boolean editFound = firstEdit != null;
            showToast("Ansatz 4: Erstes EditText gefunden=" + editFound);
            if (editFound) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                firstEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                firstEdit.recycle();
            }
            // Partial text "Anmeld"
            boolean loginClicked = clickLoginButtonByPartialTextAndReturn(root, "Anmeld");
            showToast("Ansatz 4: Login-Knopf (Partial) geklickt=" + loginClicked);
            success = loginClicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy4 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy5(String username, String password, int attemptId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            // Fill password
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            boolean pwdFound = !passwords.isEmpty();
            showToast("Ansatz 5: Passwortfeld gefunden=" + pwdFound);
            if (pwdFound) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // clickable nodes with 'anmelden'
            java.util.List<AccessibilityNodeInfo> candidates = new java.util.ArrayList<>();
            collectClickableNodes(root, candidates);
            boolean clicked = false;
            for (AccessibilityNodeInfo node : candidates) {
                try {
                    CharSequence t = node.getText();
                    CharSequence cd = node.getContentDescription();
                    String textStr = t != null ? t.toString().toLowerCase() : "";
                    String cdStr = cd != null ? cd.toString().toLowerCase() : "";
                    if (textStr.contains("anmelden") || cdStr.contains("anmelden")) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        clicked = true;
                        break;
                    }
                } finally {
                    node.recycle();
                }
            }
            if (!clicked) {
                // fallback: click centre of bounds
                android.graphics.Rect loginBounds = new android.graphics.Rect(172, 1981, 909, 2130);
                float cx = (loginBounds.left + loginBounds.right) / 2f;
                float cy = (loginBounds.top + loginBounds.bottom) / 2f;
                performClickAt(cx, cy);
                clicked = true;
            }
            showToast("Ansatz 5: Login-Knopf geklickt=" + clicked);
            success = clicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy5 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy6(String username, String password, int attemptId) {
        // Similar to strategy1 but search for "Willkommen" without username
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            java.util.List<AccessibilityNodeInfo> welcomeNodes = root.findAccessibilityNodeInfosByText("Willkommen");
            boolean welcomeDetected = welcomeNodes != null && !welcomeNodes.isEmpty();
            showToast("Ansatz 6: Irgendein Willkommen gefunden=" + welcomeDetected);
            // proceed even if welcome not found; we attempt fill & click
            // fill first password field or first edittext
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            boolean pwdFound = !passwords.isEmpty();
            showToast("Ansatz 6: Passwortfeld gefunden=" + pwdFound);
            if (pwdFound) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // click login button
            boolean clicked = clickLoginButtonByPartialTextAndReturn(root, "Anmelden");
            showToast("Ansatz 6: Login-Knopf geklickt=" + clicked);
            success = clicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy6 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy7(String username, String password, int attemptId) {
        // Variation: search for welcome in content description rather than text
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            boolean welcomeFound = false;
            java.util.List<AccessibilityNodeInfo> allNodes = new java.util.ArrayList<>();
            collectClickableNodes(root, allNodes);
            for (AccessibilityNodeInfo node : allNodes) {
                try {
                    CharSequence cd = node.getContentDescription();
                    if (cd != null && cd.toString().contains("Willkommen")) {
                        welcomeFound = true;
                        break;
                    }
                } finally {
                    node.recycle();
                }
            }
            showToast("Ansatz 7: Willkommen in ContentDesc gefunden=" + welcomeFound);
            // fill password
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            boolean pwdFound = !passwords.isEmpty();
            showToast("Ansatz 7: Passwortfeld gefunden=" + pwdFound);
            if (pwdFound) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // find any clickable with text containing "login" or "anmelden"
            boolean clicked = false;
            allNodes.clear();
            collectClickableNodes(root, allNodes);
            for (AccessibilityNodeInfo node : allNodes) {
                try {
                    String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                    String cd = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
                    if (text.contains("login") || text.contains("anmelden") || cd.contains("login") || cd.contains("anmelden")) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        clicked = true;
                        break;
                    }
                } finally {
                    node.recycle();
                }
            }
            showToast("Ansatz 7: Login-Knopf geklickt=" + clicked);
            success = clicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy7 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy8(String username, String password, int attemptId) {
        // Variation: bounding boxes with larger tolerance
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            android.graphics.Rect passwordBounds = new android.graphics.Rect(84, 811, 996, 952);
            android.graphics.Rect loginBounds = new android.graphics.Rect(172, 1981, 909, 2130);
            AccessibilityNodeInfo pwdNode = findNodeByBounds(root, passwordBounds, 50);
            boolean pwdFound = pwdNode != null;
            showToast("Ansatz 8: Passwort-Feld (große Toleranz) gefunden=" + pwdFound);
            if (pwdFound) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            AccessibilityNodeInfo loginNode = findNodeByBounds(root, loginBounds, 50);
            boolean clicked = false;
            if (loginNode != null) {
                loginNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                loginNode.recycle();
                clicked = true;
            }
            showToast("Ansatz 8: Login-Knopf (große Toleranz) geklickt=" + clicked);
            success = clicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy8 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy9(String username, String password, int attemptId) {
        // Variation: use first EditText if password not found, and click any clickable button at bottom half of screen
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            // fill password field if present, else first EditText
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            AccessibilityNodeInfo targetField = null;
            if (!passwords.isEmpty()) {
                targetField = passwords.get(0);
                showToast("Ansatz 9: Passwortfeld gefunden");
            } else {
                targetField = findFirstEditText(root);
                showToast("Ansatz 9: Passwortfeld nicht gefunden, benutze erstes EditText=" + (targetField != null));
            }
            if (targetField != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                targetField.recycle();
            }
            // click any clickable node at lower half (y > half of screen height) with text containing "anmeld"
            java.util.List<AccessibilityNodeInfo> clickable = new java.util.ArrayList<>();
            collectClickableNodes(root, clickable);
            boolean clicked = false;
            for (AccessibilityNodeInfo node : clickable) {
                try {
                    android.graphics.Rect rect = new android.graphics.Rect();
                    node.getBoundsInScreen(rect);
                    if (rect.top > 1000) { // assume 2340p height; adjust as needed
                        String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                        String cd = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
                        if (text.contains("anmeld") || cd.contains("anmeld")) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            clicked = true;
                            break;
                        }
                    }
                } finally {
                    node.recycle();
                }
            }
            showToast("Ansatz 9: Login-Knopf geklickt=" + clicked);
            success = clicked;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy9 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    private boolean attemptStrategy10(String username, String password, int attemptId) {
        // Variation: use gesture taps at predetermined coordinates without searching for nodes
        // Coordinates derived from the log: tap password field, then login button
        // We'll still attempt to set password via password field detection
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean success = false;
        try {
            // Tap password field centre first to focus
            android.graphics.Rect pwdRect = new android.graphics.Rect(84, 811, 996, 952);
            float px = (pwdRect.left + pwdRect.right) / 2f;
            float py = (pwdRect.top + pwdRect.bottom) / 2f;
            performClickAt(px, py);
            showToast("Ansatz 10: Passwortfeld angetippt");
            // Wait briefly for focus
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            // Attempt to fill via password field detection
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            if (!passwords.isEmpty()) {
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
                showToast("Ansatz 10: Passwort gesetzt");
            } else {
                showToast("Ansatz 10: Kein Passwortfeld gefunden, nur angetippt");
            }
            // Tap login button
            android.graphics.Rect loginRect = new android.graphics.Rect(172, 1981, 909, 2130);
            float cx = (loginRect.left + loginRect.right) / 2f;
            float cy = (loginRect.top + loginRect.bottom) / 2f;
            performClickAt(cx, cy);
            showToast("Ansatz 10: Login-Knopf angetippt");
            success = true;
        } catch (Exception e) {
            Log.e(TAG, "AttemptStrategy10 error", e);
        } finally {
            root.recycle();
        }
        return success;
    }

    /**
     * Helper to click the first node whose text or content description contains the given
     * substring (case-insensitive), returning whether a click occurred. This is an
     * alternative to {@link #clickLoginButtonByPartialText} that returns a result.
     */
    private boolean clickLoginButtonByPartialTextAndReturn(AccessibilityNodeInfo root, String substring) {
        if (substring == null || substring.isEmpty()) return false;
        java.util.List<AccessibilityNodeInfo> allNodes = new java.util.ArrayList<>();
        collectClickableNodes(root, allNodes);
        String lower = substring.toLowerCase();
        boolean clicked = false;
        for (AccessibilityNodeInfo node : allNodes) {
            try {
                CharSequence t = node.getText();
                CharSequence cd = node.getContentDescription();
                String textStr = t != null ? t.toString().toLowerCase() : "";
                String cdStr = cd != null ? cd.toString().toLowerCase() : "";
                if (textStr.contains(lower) || cdStr.contains(lower)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    clicked = true;
                    break;
                }
            } finally {
                node.recycle();
            }
        }
        return clicked;
    }

    /**
     * Ensures that the writers for CSV and JSON logs are open. When called the first time, this
     * method creates the directory structure {@code /sdcard/clicka/payzy} (if it does not
     * already exist) and opens the log files in append mode. Subsequent calls reuse the
     * existing {@link FileWriter} instances.
     */
    private void ensureWriters() {
        if (csvWriter != null && jsonWriter != null) {
            return;
        }
        try {
            File baseDir = Environment.getExternalStorageDirectory();
            File dir = new File(baseDir, "clicka/payzy");
            if (!dir.exists()) {
                boolean ok = dir.mkdirs();
                if (!ok) {
                    Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                    return;
                }
            }
            if (csvWriter == null) {
                File csvFile = new File(dir, "events.csv");
                csvWriter = new FileWriter(csvFile, true /* append */);
            }
            if (jsonWriter == null) {
                File jsonFile = new File(dir, "events.jsonl");
                jsonWriter = new FileWriter(jsonFile, true /* append */);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error opening log files", e);
        }
    }

    /**
     * Builds and writes a detailed log record for the supplied accessibility event. The record
     * contains the timestamp, delta since the previous event, event type, package/class name,
     * view ID, bounding rectangle, textual content and content description. The data is written
     * to two files: a CSV for quick inspection and a JSONL file for structured processing.
     *
     * @param event the accessibility event to log
     */
    private void logEvent(AccessibilityEvent event) {
        ensureWriters();
        if (csvWriter == null || jsonWriter == null) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        long delta = (lastEventTimestamp < 0) ? 0 : timestamp - lastEventTimestamp;
        lastEventTimestamp = timestamp;
        int type = event.getEventType();
        String typeName;
        try {
            typeName = AccessibilityEvent.eventTypeToString(type);
        } catch (Exception e) {
            // Fallback for older API levels or unexpected failures
            typeName = Integer.toString(type);
        }
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        String className = event.getClassName() != null ? event.getClassName().toString() : "";
        CharSequence contentDescription = event.getContentDescription();
        String contentDesc = contentDescription != null ? contentDescription.toString() : "";
        // Concatenate event text entries into a single string
        StringBuilder textBuilder = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append(cs.toString());
                }
            }
        }
        String eventText = textBuilder.toString();
        // Extract bounding rectangle and view ID from the source node
        int left = -1, top = -1, right = -1, bottom = -1;
        String viewId = "";
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source != null) {
                Rect bounds = new Rect();
                source.getBoundsInScreen(bounds);
                left = bounds.left;
                top = bounds.top;
                right = bounds.right;
                bottom = bounds.bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    CharSequence vid = source.getViewIdResourceName();
                    if (vid != null) {
                        viewId = vid.toString();
                    }
                }
            }
        } catch (Exception e) {
            // ignore errors when retrieving node info
        } finally {
            if (source != null) {
                source.recycle();
            }
        }
        // Compose CSV line: timestamp,delta,typeName,package,class,viewId,left,top,right,bottom,text,contentDesc\n
        StringBuilder csvLine = new StringBuilder();
        csvLine.append(timestamp).append(",");
        csvLine.append(delta).append(",");
        csvLine.append(escapeCsv(typeName)).append(",");
        csvLine.append(escapeCsv(packageName)).append(",");
        csvLine.append(escapeCsv(className)).append(",");
        csvLine.append(escapeCsv(viewId)).append(",");
        csvLine.append(left).append(",");
        csvLine.append(top).append(",");
        csvLine.append(right).append(",");
        csvLine.append(bottom).append(",");
        csvLine.append(escapeCsv(eventText)).append(",");
        csvLine.append(escapeCsv(contentDesc)).append("\n");
        try {
            csvWriter.write(csvLine.toString());
            csvWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing CSV log", e);
        }
        // Compose JSON object for the event
        try {
            JSONObject obj = new JSONObject();
            obj.put("timestamp", timestamp);
            obj.put("delta", delta);
            obj.put("eventType", typeName);
            obj.put("package", packageName);
            obj.put("class", className);
            obj.put("viewId", viewId);
            obj.put("left", left);
            obj.put("top", top);
            obj.put("right", right);
            obj.put("bottom", bottom);
            obj.put("text", eventText);
            obj.put("contentDescription", contentDesc);
            jsonWriter.write(obj.toString());
            jsonWriter.write("\n");
            jsonWriter.flush();
        } catch (Exception e) {
            Log.e(TAG, "Error writing JSON log", e);
        }
    }

    /**
     * Escapes a string for inclusion in a CSV record. If the value contains commas, quotes or
     * newlines it will be wrapped in double quotes and internal quotes will be doubled.
     *
     * @param value the value to escape
     * @return an escaped representation suitable for CSV
     */
    private static String escapeCsv(String value) {
        if (value == null) return "";
        boolean needsQuotes = value.contains(",") || value.contains("\n") || value.contains("\r") || value.contains("\"");
        String escaped = value.replace("\"", "\"\"");
        if (needsQuotes) {
            return "\"" + escaped + "\"";
        } else {
            return escaped;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        // Close the writers when the service is destroyed to free file handles.
        if (csvWriter != null) {
            try {
                csvWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing CSV writer", e);
            }
            csvWriter = null;
        }
        if (jsonWriter != null) {
            try {
                jsonWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing JSON writer", e);
            }
            jsonWriter = null;
        }
    }
}