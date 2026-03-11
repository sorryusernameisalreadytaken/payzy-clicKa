package eu.payzy.clicka;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * MainActivity displays a simple interface with buttons to request the
 * necessary permissions for the app and to toggle recording of accessibility
 * events. It does not itself start or stop the accessibility service;
 * instead the user must enable the service in the system settings.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_WRITE_STORAGE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button accessibilityButton = findViewById(R.id.button_accessibility);
        Button permissionButton = findViewById(R.id.button_permission);
        Button recordButton = findViewById(R.id.button_record);

        // Launch system accessibility settings so the user can enable the service.
        accessibilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });

        // Request external storage permission for writing logs.
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQUEST_WRITE_STORAGE
                    );
                } else {
                    Toast.makeText(MainActivity.this, R.string.permission_already_granted, Toast.LENGTH_SHORT).show();
                }
            }
        });

        updateRecordButtonLabel(recordButton);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean recording = PrefsHelper.isRecording(MainActivity.this);
                PrefsHelper.setRecording(MainActivity.this, !recording);
                updateRecordButtonLabel(recordButton);
            }
        });
    }

    /**
     * Updates the label of the record button based on the current recording state.
     *
     * @param recordButton the button to update
     */
    private void updateRecordButtonLabel(Button recordButton) {
        boolean recording = PrefsHelper.isRecording(this);
        recordButton.setText(recording ? getString(R.string.button_record_stop) : getString(R.string.button_record_start));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }
}