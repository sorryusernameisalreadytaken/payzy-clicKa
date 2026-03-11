package eu.payzy.clicka;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
    public void performLoginApproach(int approach, String username, String password) {
        switch (approach) {
            case 1:
                loginStrategy1(username, password);
                break;
            case 2:
                loginStrategy2(username, password);
                break;
            case 3:
                loginStrategy3(username, password);
                break;
            case 4:
                loginStrategy4(username, password);
                break;
            case 5:
                loginStrategy5(username, password);
                break;
            default:
                Log.w(TAG, "Unknown login approach: " + approach);
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