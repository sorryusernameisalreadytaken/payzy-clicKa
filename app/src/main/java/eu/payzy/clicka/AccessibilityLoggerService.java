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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            // Detect the welcome message to ensure we are on the login screen
            String welcomeText = "Willkommen," + username;
            java.util.List<AccessibilityNodeInfo> welcomeNodes = root.findAccessibilityNodeInfosByText(welcomeText);
            boolean welcomeDetected = welcomeNodes != null && !welcomeNodes.isEmpty();
            if (!welcomeDetected) {
                // Not on the expected screen
                return;
            }
            // Find password fields
            java.util.List<AccessibilityNodeInfo> passwords = new java.util.ArrayList<>();
            collectPasswordFields(root, passwords);
            if (!passwords.isEmpty()) {
                // Use the first password field found
                AccessibilityNodeInfo pwdNode = passwords.get(0);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                pwdNode.recycle();
            }
            // Find the login button by its text or content description
            java.util.List<AccessibilityNodeInfo> loginButtons = root.findAccessibilityNodeInfosByText("Anmelden");
            if (loginButtons != null) {
                for (AccessibilityNodeInfo btn : loginButtons) {
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
        } catch (Exception e) {
            Log.e(TAG, "Error performing login", e);
        } finally {
            // Always recycle the root node to avoid memory leaks
            root.recycle();
        }
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