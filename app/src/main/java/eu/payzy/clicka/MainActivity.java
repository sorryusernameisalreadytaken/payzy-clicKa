package eu.payzy.clicka;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import eu.payzy.clicka.AccessibilityLoggerService;

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
    private static final int REQUEST_MANAGE_STORAGE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button accessibilityButton = findViewById(R.id.button_accessibility);
        Button permissionButton = findViewById(R.id.button_permission);
        Button recordButton = findViewById(R.id.button_record);

        // New UI elements for username, password and PIN
        android.widget.EditText editUsername = findViewById(R.id.edit_username);
        android.widget.EditText editPassword = findViewById(R.id.edit_password);
        android.widget.EditText editPin = findViewById(R.id.edit_pin);
        Button saveButton = findViewById(R.id.button_save_credentials);
        Button loginButton1 = findViewById(R.id.button_login1);
        Button loginButton2 = findViewById(R.id.button_login2);
        Button loginButton3 = findViewById(R.id.button_login3);
        Button loginButton4 = findViewById(R.id.button_login4);
        Button loginButton5 = findViewById(R.id.button_login5);

        // Pre-populate fields with stored values if available
        String savedUsername = PrefsHelper.getUsername(this);
        if (savedUsername != null && !savedUsername.isEmpty()) {
            editUsername.setText(savedUsername);
        }
        editPassword.setText(PrefsHelper.getPassword(this));
        editPin.setText(PrefsHelper.getPin(this));

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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    // On Android 11 (API 30) and higher, apps need the MANAGE_EXTERNAL_STORAGE permission
                    // to write to arbitrary locations on shared storage. Check if the permission is granted.
                    if (android.os.Environment.isExternalStorageManager()) {
                        Toast.makeText(MainActivity.this, R.string.permission_already_granted, Toast.LENGTH_SHORT).show();
                    } else {
                        // Direct the user to the system settings page where they can grant the permission
                        try {
                            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        } catch (Exception ex) {
                            // Fallback to general manage all files access permission page
                            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        }
                    }
                } else {
                    // For older API levels, request WRITE_EXTERNAL_STORAGE at runtime
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

        // Handle saving of username, password and PIN
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = editUsername.getText().toString();
                String password = editPassword.getText().toString();
                String pin = editPin.getText().toString();
                PrefsHelper.setUsername(MainActivity.this, username);
                PrefsHelper.setPassword(MainActivity.this, password);
                PrefsHelper.setPin(MainActivity.this, pin);
                android.widget.Toast.makeText(MainActivity.this, R.string.saved_successfully, android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // Handle login automation using five different approaches
        View.OnClickListener loginClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = editUsername.getText().toString();
                String password = editPassword.getText().toString();
                // Persist credentials for later reuse
                PrefsHelper.setUsername(MainActivity.this, username);
                PrefsHelper.setPassword(MainActivity.this, password);
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                int approach;
                int id = v.getId();
                if (id == R.id.button_login1) {
                    approach = 1;
                } else if (id == R.id.button_login2) {
                    approach = 2;
                } else if (id == R.id.button_login3) {
                    approach = 3;
                } else if (id == R.id.button_login4) {
                    approach = 4;
                } else {
                    approach = 5;
                }
                service.performLoginApproach(approach, username, password);
            }
        };
        loginButton1.setOnClickListener(loginClickListener);
        loginButton2.setOnClickListener(loginClickListener);
        loginButton3.setOnClickListener(loginClickListener);
        loginButton4.setOnClickListener(loginClickListener);
        loginButton5.setOnClickListener(loginClickListener);
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