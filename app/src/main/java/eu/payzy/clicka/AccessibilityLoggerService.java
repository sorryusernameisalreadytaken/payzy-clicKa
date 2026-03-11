package eu.payzy.clicka;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Environment;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

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
    private FileWriter writer;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
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
        // Compose a comma‑separated line containing basic details about the event.
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis());
        sb.append(",");
        sb.append(AccessibilityEvent.eventTypeToString(event.getEventType()));
        sb.append(",");
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            sb.append(pkg.toString().replace(',', ' '));
        } else {
            sb.append("null");
        }
        sb.append(",");
        CharSequence desc = event.getContentDescription();
        if (desc != null) {
            sb.append(desc.toString().replace(',', ' '));
        } else {
            sb.append("null");
        }
        sb.append("\n");
        writeLog(sb.toString());
    }

    @Override
    public void onInterrupt() {
        // No special interrupt handling required.
    }

    /**
     * Writes a line to the log file stored on external storage.
     *
     * The file is created on demand when the first line is logged. Subsequent
     * calls reuse the same FileWriter instance. In case of failures the
     * exceptions are logged but not propagated.
     *
     * @param line the CSV line to append to the log file
     */
    private void writeLog(String line) {
        try {
            if (writer == null) {
                // Build the directory structure /sdcard/clicka/payzy if it doesn't exist.
                File baseDir = Environment.getExternalStorageDirectory();
                File dir = new File(baseDir, "clicka/payzy");
                if (!dir.exists()) {
                    boolean ok = dir.mkdirs();
                    if (!ok) {
                        Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                        return;
                    }
                }
                File logFile = new File(dir, "events.csv");
                writer = new FileWriter(logFile, true /* append */);
            }
            writer.write(line);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing log", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Close the writer when the service is destroyed to free file handles.
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing writer", e);
            }
            writer = null;
        }
    }
}