package jp.kshoji.blehid.sample;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

import jp.kshoji.blehid.HidPeripheral;
import jp.kshoji.blehid.KindleClickPeripheral;
import jp.kshoji.blehid.sample.R.id;
import jp.kshoji.blehid.sample.R.layout;
import jp.kshoji.blehid.sample.R.string;

/**
 * Activity for the Kindle Click page turner
 */
public class KindleClickActivity extends AbstractBleActivity {
    private static final String TAG = "KindleClick";
    // The Kindle, and the remote's GATT handles, when the Kindle last subscribed to input reports, i.e. last
    // discovered the remote. Until its Bluetooth restarts (every sleep), a Kindle reuses those handles on
    // reconnection, even if the remote's have moved.
    private static final String PREF_KINDLE_ADDRESS = "kindleAddress";
    private static final String PREF_KINDLE_HANDLES = "kindleHandles";
    private static final String PREF_PHONE_BATTERY = "phoneBattery";
    private static final String PREF_BATTERY_LEVEL = "batteryLevel";
    private static final int MAX_KINDLE_HANDLE = 0xFF; // a Kindle keeps report handles in 8 bits

    private KindleClickPeripheral kindleClick;
    private boolean restartWhenBluetoothOn;
    private int phoneBatteryLevel = -1;
    private TextView statusText;
    private TextView batteryText;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layout.activity_kindle_click);

        setTitle(getString(string.kindle_click));
        statusText = findViewById(id.statusText);
        batteryText = findViewById(id.batteryText);

        findViewById(id.nextPageButton).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View view) {
                if (kindleClick != null) {
                    kindleClick.pageNext();
                }
            }
        });
        findViewById(id.previousPageButton).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View view) {
                if (kindleClick != null) {
                    kindleClick.pagePrevious();
                }
            }
        });
        setUpBrightnessButton(id.brightnessUpButton, true);
        setUpBrightnessButton(id.brightnessDownButton, false);
        final View sleepButton = findViewById(id.sleepButton);
        sleepButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View view) {
                Toast.makeText(KindleClickActivity.this, string.sleep_hint, Toast.LENGTH_SHORT).show();
            }
        });
        sleepButton.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View view) {
                if (kindleClick != null) {
                    kindleClick.sleep();
                }
                return true;
            }
        });

        final SeekBar batterySeekBar = findViewById(id.batterySeekBar);
        batterySeekBar.setProgress(preferences().getInt(PREF_BATTERY_LEVEL, 100));
        batterySeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress, final boolean fromUser) {
                preferences().edit().putInt(PREF_BATTERY_LEVEL, progress).apply();
                updateBatteryLevel();
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
            }
        });
        final CheckBox phoneBatteryCheckBox = findViewById(id.phoneBatteryCheckBox);
        phoneBatteryCheckBox.setChecked(preferences().getBoolean(PREF_PHONE_BATTERY, true));
        batterySeekBar.setVisibility(phoneBatteryCheckBox.isChecked() ? View.GONE : View.VISIBLE);
        phoneBatteryCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                preferences().edit().putBoolean(PREF_PHONE_BATTERY, isChecked).apply();
                batterySeekBar.setVisibility(isChecked ? View.GONE : View.VISIBLE);
                updateBatteryLevel();
            }
        });
        final IntentFilter systemFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        systemFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, systemReceiver, systemFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        updateBatteryLevel();
        updateStatus();
    }

    @Override
    void setupBlePeripheralProvider() {
        kindleClick = new KindleClickPeripheral(this);
        kindleClick.setDeviceName(getString(string.kindle_click));
        kindleClick.setConnectionStateCallback(new HidPeripheral.ConnectionStateCallback() {
            @Override
            public void onConnectionStateChanged(@NonNull final HidPeripheral.ConnectionState connectionState) {
                runOnUiThread(statusUpdater);
            }
        });
        kindleClick.setSubscriptionCallback(new HidPeripheral.SubscriptionCallback() {
            @Override
            public void onSubscribed(@NonNull final BluetoothDevice device) {
                // The Kindle discovered the remote again: it now uses the current handles
                final int[] handles = kindleClick.getGattHandles();
                if (handles != null) {
                    preferences().edit()
                            .putString(PREF_KINDLE_ADDRESS, device.getAddress())
                            .putString(PREF_KINDLE_HANDLES, Arrays.toString(handles))
                            .apply();
                }
                runOnUiThread(statusUpdater);
            }
        });
        kindleClick.startAdvertising();
        updateBatteryLevel();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(systemReceiver);
        stopRemote();
    }

    private SharedPreferences preferences() {
        return getPreferences(MODE_PRIVATE);
    }

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatus();
        }
    };

    private void updateStatus() {
        if (statusText == null || isDestroyed()) {
            return;
        }
        final int[] handles = kindleClick == null ? null : kindleClick.getGattHandles();
        if (handles == null) {
            statusText.setText(restartWhenBluetoothOn ? string.status_bluetooth_off : string.status_starting);
            if (kindleClick != null) {
                // services are still being added
                statusText.removeCallbacks(statusUpdater);
                statusText.postDelayed(statusUpdater, 500);
            }
            return;
        }
        final String kindleHandles = preferences().getString(PREF_KINDLE_HANDLES, null);
        final boolean connected = isKindleConnected();
        Log.d(TAG, "Kindle connected: " + connected + ", handles: " + Arrays.toString(handles) + ", Kindle's: " + kindleHandles);
        if (handles[0] > MAX_KINDLE_HANDLE || handles[1] > MAX_KINDLE_HANDLE) {
            statusText.setText(string.status_handles_too_high);
        } else if (!connected) {
            statusText.setText(kindleClick.hasStaleLink() ? string.status_stale_link : string.status_waiting);
        } else if (kindleHandles != null && !kindleHandles.equals(Arrays.toString(handles))) {
            statusText.setText(string.status_handles_moved);
        } else {
            statusText.setText(string.status_connected);
        }
    }

    /**
     * @return true if the Kindle is connected to the remote
     */
    @SuppressLint("MissingPermission") // the remote only starts once Bluetooth permissions are granted
    private boolean isKindleConnected() {
        final String address = preferences().getString(PREF_KINDLE_ADDRESS, null);
        final BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (address == null || adapter.getRemoteDevice(address).getBondState() != BluetoothDevice.BOND_BONDED) {
            // Kindle not known yet, or paired again since (it is saved when it subscribes, which it does when
            // it pairs or connects after waking): count any bonded or subscribed device
            return kindleClick.isConnected();
        }
        return kindleClick.isConnected(address);
    }

    private void stopRemote() {
        if (kindleClick != null) {
            kindleClick.setConnectionStateCallback(null);
            kindleClick.setSubscriptionCallback(null);
            kindleClick.stopAdvertising();
            kindleClick = null;
        }
    }

    /**
     * Report the phone's battery level, or the level set with the slider
     */
    private void updateBatteryLevel() {
        final int level = preferences().getBoolean(PREF_PHONE_BATTERY, true) && phoneBatteryLevel >= 0
                ? phoneBatteryLevel : preferences().getInt(PREF_BATTERY_LEVEL, 100);
        if (kindleClick != null) {
            kindleClick.setBatteryLevel(level);
        }
        if (batteryText != null) {
            batteryText.setText(getString(string.battery_level, level));
        }
    }

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                // Turning Bluetooth off removes the remote: stop it while its GATT server is still registered,
                // and start it again when Bluetooth is back. (If Bluetooth was off when this screen opened,
                // AbstractBleActivity starts the remote instead.)
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF && kindleClick != null) {
                    stopRemote();
                    restartWhenBluetoothOn = true;
                } else if (state == BluetoothAdapter.STATE_ON && restartWhenBluetoothOn) {
                    restartWhenBluetoothOn = false;
                    setupBlePeripheralProvider();
                }
                updateStatus();
                return;
            }
            final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                phoneBatteryLevel = level * 100 / scale;
                updateBatteryLevel();
            }
        }
    };

    /**
     * A light button holds its key while touched, like the Click's side buttons
     */
    private void setUpBrightnessButton(final int buttonId, final boolean up) {
        findViewById(buttonId).setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent event) {
                if (kindleClick != null) {
                    final int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        kindleClick.pressBrightness(up);
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        kindleClick.releaseBrightness();
                    }
                }
                return false; // the Button still shows itself pressed
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        // Volume keys turn pages too: down = forward, up = back
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.dispatchKeyEvent(event);
        }
        if (kindleClick != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                kindleClick.pageNext();
            } else {
                kindleClick.pagePrevious();
            }
        }
        return true; // swallow key up too, or Android shows the volume panel
    }
}
