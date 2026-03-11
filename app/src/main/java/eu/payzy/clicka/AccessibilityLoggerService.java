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
            boolean success = attemptLoginWatcher(username, password, watcherId);
            if (success) {
                // Stop the watcher after a successful login to prevent repeated logins
                toggleLoginWatcher(username, password);
                showToast("Login-Watcher: Login durchgeführt");
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
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
                    java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Guthaben");
                    boolean found = false;
                    if (nodes != null) {
                        for (AccessibilityNodeInfo node : nodes) {
                            try {
                                CharSequence text = node.getText();
                                if (text != null && text.toString().toLowerCase().contains("guthaben")) {
                                    String full = text.toString();
                                    // Extract numeric value (up to the first space or before the word Guthaben)
                                    String value = extractNumericValue(full);
                                    // Persist and update UI
                                    PrefsHelper.setWalletValue(this, value);
                                    MainActivity.updateWalletValueStatic(value);
                                    showToast("Wallet gefunden: " + value);
                                    // Click the card
                                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    found = true;
                                    break;
                                }
                            } finally {
                                node.recycle();
                            }
                        }
                    }
                    // Optionally search by euro symbol if not found
                    if (!found) {
                        // fallback search: find nodes containing € and guthaben in content description or text
                        // A simple breadth-first search for text containing € and guthaben
                        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
                        queue.add(AccessibilityNodeInfo.obtain(root));
                        while (!queue.isEmpty() && !found) {
                            AccessibilityNodeInfo n = queue.removeFirst();
                            try {
                                CharSequence t = n.getText();
                                CharSequence cd = n.getContentDescription();
                                String ts = t != null ? t.toString() : "";
                                String cs = cd != null ? cd.toString() : "";
                                if ((ts.contains("€") && ts.toLowerCase().contains("guthaben")) ||
                                    (cs.contains("€") && cs.toLowerCase().contains("guthaben"))) {
                                    String full = !ts.isEmpty() ? ts : cs;
                                    String value = extractNumericValue(full);
                                    PrefsHelper.setWalletValue(this, value);
                                    MainActivity.updateWalletValueStatic(value);
                                    showToast("Wallet gefunden: " + value);
                                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    found = true;
                                    break;
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
                    java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Coins");
                    boolean found = false;
                    if (nodes != null) {
                        for (AccessibilityNodeInfo node : nodes) {
                            try {
                                CharSequence text = node.getText();
                                if (text != null && text.toString().toLowerCase().contains("coins")) {
                                    String full = text.toString();
                                    String value = extractNumericValue(full);
                                    PrefsHelper.setCoinsValue(this, value);
                                    MainActivity.updateCoinsValueStatic(value);
                                    showToast("Coins gefunden: " + value);
                                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    found = true;
                                    break;
                                }
                            } finally {
                                node.recycle();
                            }
                        }
                    }
                    if (!found) {
                        // fallback search by scanning all nodes for text containing "Coins"
                        java.util.LinkedList<AccessibilityNodeInfo> queue = new java.util.LinkedList<>();
                        queue.add(AccessibilityNodeInfo.obtain(root));
                        while (!queue.isEmpty() && !found) {
                            AccessibilityNodeInfo n = queue.removeFirst();
                            try {
                                CharSequence t = n.getText();
                                CharSequence cd = n.getContentDescription();
                                String ts = t != null ? t.toString() : "";
                                String cs = cd != null ? cd.toString() : "";
                                if ((ts.toLowerCase().contains("coins")) || (cs.toLowerCase().contains("coins"))) {
                                    String full = !ts.isEmpty() ? ts : cs;
                                    String value = extractNumericValue(full);
                                    PrefsHelper.setCoinsValue(this, value);
                                    MainActivity.updateCoinsValueStatic(value);
                                    showToast("Coins gefunden: " + value);
                                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    found = true;
                                    break;
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