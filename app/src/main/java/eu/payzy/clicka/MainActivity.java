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

    /**
     * Holds a reference to the currently visible instance of MainActivity. This is used
     * by the accessibility service to update UI elements (wallet and coins values) on
     * the main thread. It is assigned in {@link #onResume()} and cleared in
     * {@link #onPause()}.
     */
    private static MainActivity currentInstance;
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
        android.widget.EditText editAllowedPackages = findViewById(R.id.edit_allowed_packages);
        // Input fields for minimum wallet balance and minimum coins to redeem
        android.widget.EditText editMinWallet = findViewById(R.id.edit_min_wallet);
        android.widget.EditText editMinCoins = findViewById(R.id.edit_min_coins);
        Button saveButton = findViewById(R.id.button_save_credentials);
        // Watcher buttons and labels
        Button loginWatcherButton = findViewById(R.id.button_login_watcher);
        Button walletWatcherButton = findViewById(R.id.button_wallet_watcher);
        Button coinsWatcherButton = findViewById(R.id.button_coins_watcher);
        // Additional watcher buttons for top‑ups, coin redemptions and transaction export
        Button walletTopupWatcherButton = findViewById(R.id.button_wallet_topup_watcher);
        Button coinsRedeemWatcherButton = findViewById(R.id.button_coins_redeem_watcher);
        Button transactionsWatcherButton = findViewById(R.id.button_transactions_watcher);

        android.widget.TextView textWalletValue = findViewById(R.id.text_wallet_value);
        android.widget.TextView textCoinsValue = findViewById(R.id.text_coins_value);

        // Pre-populate fields with stored values if available. The username is only set
        // when a value exists; otherwise the field remains empty. The wallet and coins
        // values are also displayed if previously recorded.
        String savedUsername = PrefsHelper.getUsername(this);
        if (savedUsername != null && !savedUsername.isEmpty()) {
            editUsername.setText(savedUsername);
        }
        editPassword.setText(PrefsHelper.getPassword(this));
        editPin.setText(PrefsHelper.getPin(this));
        // Load the allowed package filter from preferences.  If no value has been
        // persisted yet then leave the field empty.  An empty value means all
        // packages will be considered by the watchers.  Previously we pre‑filled
        // this field with "gr.payzy" which caused the service to only operate
        // on that package.  Removing the default makes the behaviour more
        // flexible and avoids unwanted package restrictions.
        String savedAllowed = PrefsHelper.getAllowedPackages(this);
        if (savedAllowed != null && !savedAllowed.isEmpty()) {
            editAllowedPackages.setText(savedAllowed);
        } else {
            editAllowedPackages.setText("");
        }
        // Load previously configured minimum wallet and coin thresholds and display them
        String savedMinWallet = PrefsHelper.getMinWallet(this);
        if (savedMinWallet != null && !savedMinWallet.isEmpty()) {
            editMinWallet.setText(savedMinWallet);
        }
        String savedMinCoins = PrefsHelper.getMinCoins(this);
        if (savedMinCoins != null && !savedMinCoins.isEmpty()) {
            editMinCoins.setText(savedMinCoins);
        }
        // Display stored wallet and coins values
        String walletValue = PrefsHelper.getWalletValue(this);
        String coinsValue = PrefsHelper.getCoinsValue(this);
        textWalletValue.setText(walletValue != null && !walletValue.isEmpty() ? walletValue : "—");
        textCoinsValue.setText(coinsValue != null && !coinsValue.isEmpty() ? coinsValue : "—");

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
                // Toggle the recording state.  When starting a new recording
                // session we reset the writers in the accessibility service so
                // that fresh log files are created.  Without resetting the
                // writers the logger would continue to append to the previous
                // files, and on some devices no new files would be created
                // until the app data was cleared.
                boolean recording = PrefsHelper.isRecording(MainActivity.this);
                boolean newState = !recording;
                PrefsHelper.setRecording(MainActivity.this, newState);
                updateRecordButtonLabel(recordButton);
                if (newState) {
                    AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                    if (service != null) {
                        service.resetWriters();
                    }
                }
            }
        });

        // Handle saving of username, password, PIN, allowed packages and thresholds
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = editUsername.getText().toString();
                String password = editPassword.getText().toString();
                String pin = editPin.getText().toString();
                String allowed = editAllowedPackages.getText().toString();
                String minWallet = editMinWallet.getText().toString();
                String minCoins = editMinCoins.getText().toString();
                PrefsHelper.setUsername(MainActivity.this, username);
                PrefsHelper.setPassword(MainActivity.this, password);
                PrefsHelper.setPin(MainActivity.this, pin);
                PrefsHelper.setAllowedPackages(MainActivity.this, allowed);
                PrefsHelper.setMinWallet(MainActivity.this, minWallet);
                PrefsHelper.setMinCoins(MainActivity.this, minCoins);
                android.widget.Toast.makeText(MainActivity.this, R.string.saved_successfully, android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // Handle login watcher toggle
        loginWatcherButton.setOnClickListener(new View.OnClickListener() {
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
                boolean active = service.toggleLoginWatcher(username, password);
                // Update label according to new state
                loginWatcherButton.setText(active ? R.string.button_login_watcher_stop : R.string.button_login_watcher_start);
            }
        });

        // Handle wallet watcher toggle
        walletWatcherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean active = service.toggleWalletWatcher();
                walletWatcherButton.setText(active ? R.string.button_wallet_watcher_stop : R.string.button_wallet_watcher_start);
            }
        });

        // Handle coins watcher toggle
        coinsWatcherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean active = service.toggleCoinsWatcher();
                coinsWatcherButton.setText(active ? R.string.button_coins_watcher_stop : R.string.button_coins_watcher_start);
            }
        });

        // Handle wallet top‑up watcher toggle
        walletTopupWatcherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean active = service.toggleWalletTopupWatcher();
                walletTopupWatcherButton.setText(active ? R.string.button_wallet_topup_watcher_stop : R.string.button_wallet_topup_watcher_start);
            }
        });

        // Handle coin redemption watcher toggle
        coinsRedeemWatcherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean active = service.toggleCoinsRedeemWatcher();
                coinsRedeemWatcherButton.setText(active ? R.string.button_coins_redeem_watcher_stop : R.string.button_coins_redeem_watcher_start);
            }
        });

        // Handle transactions watcher toggle
        transactionsWatcherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccessibilityLoggerService service = AccessibilityLoggerService.getInstance();
                if (service == null) {
                    Toast.makeText(MainActivity.this, "Dienst nicht aktiv", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean active = service.toggleTransactionsWatcher();
                transactionsWatcherButton.setText(active ? R.string.button_transactions_watcher_stop : R.string.button_transactions_watcher_start);
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

    @Override
    protected void onResume() {
        super.onResume();
        currentInstance = this;
        // Update displayed wallet and coins values from preferences when resuming
        updateWallet(PrefsHelper.getWalletValue(this));
        updateCoins(PrefsHelper.getCoinsValue(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentInstance == this) {
            currentInstance = null;
        }
    }

    /**
     * Updates the wallet balance display on the UI thread.
     *
     * @param value the wallet balance to display (may be null or empty)
     */
    public void updateWallet(final String value) {
        final String display = (value != null && !value.isEmpty()) ? value : "—";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.TextView textWallet = findViewById(R.id.text_wallet_value);
                textWallet.setText(display);
            }
        });
    }

    /**
     * Updates the coins amount display on the UI thread.
     *
     * @param value the coins amount to display (may be null or empty)
     */
    public void updateCoins(final String value) {
        final String display = (value != null && !value.isEmpty()) ? value : "—";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.TextView textCoins = findViewById(R.id.text_coins_value);
                textCoins.setText(display);
            }
        });
    }

    /**
     * Static helper called by the accessibility service to update the wallet balance
     * on the currently visible instance of MainActivity. If no instance is
     * available, the value is stored in preferences and will be shown next time.
     *
     * @param value the wallet balance to display
     */
    public static void updateWalletValueStatic(String value) {
        if (currentInstance != null) {
            currentInstance.updateWallet(value);
        }
    }

    /**
     * Static helper called by the accessibility service to update the coins amount
     * on the currently visible instance of MainActivity. If no instance is
     * available, the value is stored in preferences and will be shown next time.
     *
     * @param value the coins amount to display
     */
    public static void updateCoinsValueStatic(String value) {
        if (currentInstance != null) {
            currentInstance.updateCoins(value);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * login watcher toggle button. When {@code active} is true the button will
     * display the stop text, otherwise it will display the start text. If the
     * activity is not currently visible the call is ignored.
     *
     * @param active whether the login watcher is active
     */
    public static void updateLoginWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateLoginWatcherButton(active);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * wallet watcher toggle button.
     *
     * @param active whether the wallet watcher is active
     */
    public static void updateWalletWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateWalletWatcherButton(active);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * coins watcher toggle button.
     *
     * @param active whether the coins watcher is active
     */
    public static void updateCoinsWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateCoinsWatcherButton(active);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * wallet top‑up watcher toggle button.
     *
     * @param active whether the wallet top‑up watcher is active
     */
    public static void updateWalletTopupWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateWalletTopupWatcherButton(active);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * coins redemption watcher toggle button.
     *
     * @param active whether the coins redemption watcher is active
     */
    public static void updateCoinsRedeemWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateCoinsRedeemWatcherButton(active);
        }
    }

    /**
     * Static helper used by the accessibility service to update the label of the
     * transactions watcher toggle button.
     *
     * @param active whether the transactions watcher is active
     */
    public static void updateTransactionsWatcherButtonStatic(final boolean active) {
        if (currentInstance != null) {
            currentInstance.updateTransactionsWatcherButton(active);
        }
    }

    /**
     * Updates the login watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateLoginWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_login_watcher);
                btn.setText(active ? getString(R.string.button_login_watcher_stop) : getString(R.string.button_login_watcher_start));
            }
        });
    }

    /**
     * Updates the wallet watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateWalletWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_wallet_watcher);
                btn.setText(active ? getString(R.string.button_wallet_watcher_stop) : getString(R.string.button_wallet_watcher_start));
            }
        });
    }

    /**
     * Updates the coins watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateCoinsWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_coins_watcher);
                btn.setText(active ? getString(R.string.button_coins_watcher_stop) : getString(R.string.button_coins_watcher_start));
            }
        });
    }

    /**
     * Updates the wallet top‑up watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateWalletTopupWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_wallet_topup_watcher);
                btn.setText(active ? getString(R.string.button_wallet_topup_watcher_stop) : getString(R.string.button_wallet_topup_watcher_start));
            }
        });
    }

    /**
     * Updates the coins redemption watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateCoinsRedeemWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_coins_redeem_watcher);
                btn.setText(active ? getString(R.string.button_coins_redeem_watcher_stop) : getString(R.string.button_coins_redeem_watcher_start));
            }
        });
    }

    /**
     * Updates the transactions watcher button label on the UI thread.
     *
     * @param active true if the watcher is active (show stop), false otherwise
     */
    private void updateTransactionsWatcherButton(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Button btn = findViewById(R.id.button_transactions_watcher);
                btn.setText(active ? getString(R.string.button_transactions_watcher_stop) : getString(R.string.button_transactions_watcher_start));
            }
        });
    }
}