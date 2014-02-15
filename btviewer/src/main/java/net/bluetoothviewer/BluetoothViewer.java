/*
 * Copyright (C) 2010 Janos Gyerik
 *
 * This file is part of BluetoothViewer.
 *
 * BluetoothViewer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BluetoothViewer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BluetoothViewer.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.bluetoothviewer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class BluetoothViewer extends Activity {

    private static final String TAG = BluetoothViewer.class.getSimpleName();
    private static final boolean D = true;

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // Layout Views
    private TextView mStatusView;
    private EditText mOutEditText;
    private View mSendTextContainer;

    // Toolbar
    private ImageButton mToolbarConnectButton;
    private ImageButton mToolbarDisconnectButton;
    private ImageButton mToolbarPauseButton;
    private ImageButton mToolbarPlayButton;

    private ArrayAdapter<String> mConversationArrayAdapter;
    private StringBuffer mOutStringBuffer;
    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothChatService mBluetoothService = null;

    // State variables
    private boolean paused = false;
    private boolean connected = false;
    // The Handler that gets information back from the BluetoothService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BluetoothChatService.MSG_CONNECTED:
                    connected = true;
                    mStatusView.setText(formatStatusMessage(R.string.btstatus_connected_to_fmt, msg.obj));
                    onBluetoothStateChanged();
                    break;
                case BluetoothChatService.MSG_CONNECTING:
                    mStatusView.setText(formatStatusMessage(R.string.btstatus_connecting_to_fmt, msg.obj));
                    onBluetoothStateChanged();
                    break;
                case BluetoothChatService.MSG_NOT_CONNECTED:
                    connected = false;
                    mStatusView.setText(R.string.btstatus_not_connected);
                    onBluetoothStateChanged();
                    break;
                case BluetoothChatService.MSG_BYTES_WRITTEN:
                    String written = new String((byte[]) msg.obj);
                    mConversationArrayAdapter.add(">>> " + written);
                    Log.i(TAG, "written = '" + written + "'");
                    break;
                case BluetoothChatService.MSG_LINE_READ:
                    if (paused) break;
                    String readMessage = (String)msg.obj;
                    if (D) Log.d(TAG, readMessage);
                    mConversationArrayAdapter.add(readMessage);
                    break;
            }
        }
    };
    private TextView.OnEditorActionListener mWriteListener =
            new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                        sendMessage(view.getText());
                    }
                    return true;
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        mStatusView = (TextView) findViewById(R.id.btstatus);

        mSendTextContainer = findViewById(R.id.send_text_container);

        mToolbarConnectButton = (ImageButton) findViewById(R.id.toolbar_btn_connect);
        mToolbarConnectButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startDeviceListActivity();
            }
        });

        mToolbarDisconnectButton = (ImageButton) findViewById(R.id.toolbar_btn_disconnect);
        mToolbarDisconnectButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                disconnectDevices();
            }
        });

        mToolbarPauseButton = (ImageButton) findViewById(R.id.toolbar_btn_pause);
        mToolbarPauseButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                paused = true;
                onPausedStateChanged();
            }
        });

        mToolbarPlayButton = (ImageButton) findViewById(R.id.toolbar_btn_play);
        mToolbarPlayButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                paused = false;
                onPausedStateChanged();
            }
        });

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    private void startDeviceListActivity() {
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        } else if (mBluetoothService == null) {
            setupUserInterface();
        }
    }

    private void setupUserInterface() {
        mConversationArrayAdapter = new ArrayAdapter<String>(this, R.layout.message);
        ListView mConversationView = (ListView) findViewById(R.id.in);
        mConversationView.setAdapter(mConversationArrayAdapter);

        mConversationArrayAdapter.add(getString(R.string.welcome_1));
        mConversationArrayAdapter.add(getString(R.string.welcome_2));
        mConversationArrayAdapter.add(getString(R.string.welcome_3));
        mConversationArrayAdapter.add(getString(R.string.welcome_github_pre));
        mConversationArrayAdapter.add(getString(R.string.welcome_github));
        mConversationArrayAdapter.add(getString(R.string.welcome_please_rate));
        mConversationArrayAdapter.add(getString(R.string.welcome_please_buy));

        // Initialize the compose field with a listener for the return key
        mOutEditText = (EditText) findViewById(R.id.edit_text_out);
        mOutEditText.setOnEditorActionListener(mWriteListener);

        // Initialize the send button with a listener for click events
        Button mSendButton = (Button) findViewById(R.id.button_send);
        mSendButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                TextView view = (TextView) findViewById(R.id.edit_text_out);
                sendMessage(view.getText());
            }
        });

        mBluetoothService = new BluetoothChatService(mHandler);

        mOutStringBuffer = new StringBuffer("");

        onBluetoothStateChanged();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBluetoothService != null) mBluetoothService.stop();
    }

    private void sendMessage(CharSequence chars) {
        if (chars.length() > 0) {
            if (mBluetoothService.getState() != BluetoothChatService.STATE_CONNECTED) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
                return;
            }

            String message = chars.toString() + "\n";

            mBluetoothService.write(message.getBytes());

            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);
        }
    }

    private String formatStatusMessage(int formatResId, Object obj) {
        String deviceName = (String) obj;
        return getString(formatResId, deviceName);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                    mBluetoothService.connect(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode != Activity.RESULT_OK) {
                    Log.i(TAG, "BT not enabled");
                    Toast.makeText(this, R.string.bt_not_enabled, Toast.LENGTH_SHORT).show();
                }
                setupUserInterface();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_github:
                openURL(getString(R.string.url_github));
                break;
            case R.id.menu_rate:
                openURL(getString(R.string.url_rate));
                break;
            case R.id.menu_buy:
                openURL(getString(R.string.url_full_app));
                break;
        }
        return false;
    }

    private void openURL(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void disconnectDevices() {
        if (mBluetoothService != null) mBluetoothService.stop();

        onBluetoothStateChanged();
    }

    private void onBluetoothStateChanged() {
        if (connected) {
            mToolbarConnectButton.setVisibility(View.GONE);
            mToolbarDisconnectButton.setVisibility(View.VISIBLE);
            mSendTextContainer.setVisibility(View.VISIBLE);
        } else {
            mToolbarConnectButton.setVisibility(View.VISIBLE);
            mToolbarDisconnectButton.setVisibility(View.GONE);
            mSendTextContainer.setVisibility(View.GONE);
        }
        paused = false;
        onPausedStateChanged();
    }

    private void onPausedStateChanged() {
        if (connected) {
            if (paused) {
                mToolbarPlayButton.setVisibility(View.VISIBLE);
                mToolbarPauseButton.setVisibility(View.GONE);
            } else {
                mToolbarPlayButton.setVisibility(View.GONE);
                mToolbarPauseButton.setVisibility(View.VISIBLE);
            }
        } else {
            mToolbarPlayButton.setVisibility(View.GONE);
            mToolbarPauseButton.setVisibility(View.GONE);
        }
    }
}