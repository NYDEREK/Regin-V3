package com.snyde.reginapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth controller for REGIN V3 (turbine line follower).
 * Text protocol is the GRUZIK4.0 one, see SimpleParser.c in the firmware.
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "regin";
    private static final String KEY_PRESET_NAMES = "preset_names";
    private static final String KEY_CLEAN_SPEED = "clean.speed";
    private static final String KEY_JOYSTICK_SPEED = "joystick.speed";
    private static final String KEY_BT_NAME = "bt.name";
    private static final String KEY_LAST_DEVICE = "last.device";
    private static final String KEY_TAB = "tab";
    private static final int BT_NAME_MAX_LEN = 20;
    private static final int REQUEST_BT = 41;

    private static final int TAB_DRIVE = 0;
    private static final int TAB_SENSORS = 1;
    private static final int TAB_JOYSTICK = 2;
    private static final int TAB_LOG = 3;
    private static final int TAB_SETTINGS = 4;
    private static final String[] TAB_NAMES = {"Drive", "Sensors", "Joystick", "Log", "Settings"};

    private static final int STYLE_QUIET = 0;
    private static final int STYLE_PRIMARY = 1;
    private static final int STYLE_DANGER = 2;

    private static final int BG = Color.rgb(14, 14, 15);
    private static final int FIELD = Color.rgb(30, 30, 33);
    private static final int PRESSED = Color.rgb(44, 44, 48);
    private static final int LINE = Color.rgb(42, 42, 46);
    private static final int TEXT = Color.rgb(236, 236, 238);
    private static final int MUTED = Color.rgb(140, 140, 148);
    private static final int DANGER = Color.rgb(229, 72, 77);
    private static final int WARNING = Color.rgb(245, 165, 36);

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object writeLock = new Object();
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final LinkedHashMap<String, EditText> fields = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> fieldDefaults = new LinkedHashMap<>();
    private final ArrayList<String> presetNames = new ArrayList<>();
    private final ArrayList<TextView> tabLabels = new ArrayList<>();
    private final ArrayList<View> tabUnderlines = new ArrayList<>();
    private final ArrayList<View> pages = new ArrayList<>();
    private final StringBuilder rxLineBuffer = new StringBuilder();

    private BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothSocket socket;
    private volatile OutputStream outputStream;
    private Thread readThread;
    private volatile boolean readThreadRunning;
    private volatile long lastStartAckMs;
    private int connectionState = STATE_DISCONNECTED;
    private boolean streaming;
    private boolean presetTouched;

    private Spinner deviceSpinner;
    private Button connectButton;
    private TextView voltageText;
    private TextView warningText;
    private TextView logText;
    private Spinner presetSpinner;
    private EditText presetNameInput;
    private EditText cleanSpeedInput;
    private EditText joystickSpeedInput;
    private EditText btNameInput;
    private TextView btNameStatusText;
    private TextView joystickStatusText;
    private SensorBarsView sensorBars;
    private TextView sensorSummaryText;
    private TextView sensorErrorText;
    private TextView sensorPositionText;
    private Button streamButton;

    private long lastManualSendMs;
    private int lastManualLeft;
    private int lastManualRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        loadSettings();
        loadPresetNames();
        requestBluetoothPermissionIfNeeded();
        selectTab(prefs().getInt(KEY_TAB, TAB_DRIVE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (connectionState == STATE_DISCONNECTED) {
            loadPairedDevices();
        }
    }

    @Override
    protected void onPause() {
        saveSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (outputStream != null) {
            sendLine("Manual=0,0\n", false);
            sendLine("Clean=0\n", false);
        }
        closeConnection();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG);
        scrollView.setOnApplyWindowInsetsListener(this::applySystemInsets);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scrollView.addView(root, matchWrap());

        LinearLayout header = row();
        TextView title = label("REGIN V3", 20, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        voltageText = label("", 15, TEXT);
        voltageText.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(voltageText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header, matchWrap());

        LinearLayout connectionRow = row();
        deviceSpinner = new Spinner(this);
        connectButton = button("Connect", STYLE_QUIET);
        connectionRow.addView(deviceSpinner, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(dp(116), dp(48));
        connectParams.setMargins(dp(8), 0, 0, 0);
        connectionRow.addView(connectButton, connectParams);
        root.addView(connectionRow, topMargin(8));
        connectButton.setOnClickListener(v -> onConnectClicked());

        LinearLayout driveRow = row();
        Button startButton = button("Start", STYLE_PRIMARY);
        Button stopButton = button("Stop", STYLE_DANGER);
        driveRow.addView(startButton, weighted(0, 4));
        driveRow.addView(stopButton, weighted(4, 0));
        root.addView(driveRow, topMargin(8));
        startButton.setOnClickListener(v -> startRobot());
        stopButton.setOnClickListener(v -> stopRobot());

        warningText = label("", 13, WARNING);
        warningText.setVisibility(View.GONE);
        root.addView(warningText, topMargin(6));

        root.addView(tabBar(), topMargin(10));
        root.addView(hairline());

        pages.add(buildDrivePage());
        pages.add(buildSensorsPage());
        pages.add(buildJoystickPage());
        pages.add(buildLogPage());
        pages.add(buildSettingsPage());
        for (View page : pages) {
            root.addView(page, topMargin(4));
        }

        setContentView(scrollView);
    }

    private WindowInsets applySystemInsets(View view, WindowInsets insets) {
        // Android 15+ always draws edge-to-edge, older versions lay out below the bars
        if (Build.VERSION.SDK_INT >= 35) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        }
        return insets;
    }

    private View tabBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = row();
        for (int i = 0; i < TAB_NAMES.length; i++) {
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(TAB_NAMES[i], 15, MUTED);
            name.setPadding(0, dp(10), 0, dp(9));
            View underline = new View(this);
            underline.setBackgroundColor(TEXT);
            tab.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tab.addView(underline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
            final int index = i;
            tab.setOnClickListener(v -> selectTab(index));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(22), 0);
            bar.addView(tab, params);
            tabLabels.add(name);
            tabUnderlines.add(underline);
        }
        scroller.addView(bar);
        return scroller;
    }

    private void selectTab(int tab) {
        if (tab < 0 || tab >= pages.size()) {
            tab = TAB_DRIVE;
        }
        for (int i = 0; i < pages.size(); i++) {
            boolean selected = i == tab;
            pages.get(i).setVisibility(selected ? View.VISIBLE : View.GONE);
            tabLabels.get(i).setTextColor(selected ? TEXT : MUTED);
            tabUnderlines.get(i).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
        prefs().edit().putInt(KEY_TAB, tab).apply();
    }

    private View buildDrivePage() {
        LinearLayout page = page();

        page.addView(sectionLabel("Turbine"), matchWrap());
        page.addView(fieldRow("Turbine_Speed", "Speed, 0-1000", "100",
                "Turbine_Prep_Time", "Spin-up, ms", "1000"), matchWrap());

        page.addView(sectionLabel("PID"), matchWrap());
        page.addView(fieldRow("Kp", "Kp", "0.015", "Kd", "Kd", "0.55"), matchWrap());
        page.addView(fieldRow("Treshold", "Threshold", "3700", null, null, null), matchWrap());

        page.addView(sectionLabel("Speed"), matchWrap());
        page.addView(fieldRow("Base_speed", "Base", "125", "Max_speed", "Max", "200"), matchWrap());
        // Line lost: the outer wheel gets *_left, the inner wheel *_right (see sharp_turn())
        page.addView(fieldRow("Sharp_bend_speed_left", "Sharp bend outer", "120",
                "Sharp_bend_speed_right", "Sharp bend inner", "-75"), matchWrap());
        page.addView(fieldRow("Bend_speed_left", "Bend outer", "120",
                "Bend_speed_right", "Bend inner", "-75"), matchWrap());

        Button sendButton = button("Send values", STYLE_QUIET);
        page.addView(sendButton, topMargin(10));
        sendButton.setOnClickListener(v -> {
            if (!isConnected()) {
                showWarning("Not connected");
                return;
            }
            ArrayList<String> commands = fieldCommands();
            new Thread(() -> sendLinesBlocking(commands)).start();
        });

        EditText thresholdInput = fields.get("Treshold");
        if (thresholdInput != null) {
            thresholdInput.addTextChangedListener(new SimpleWatcher(this::updateSensorThreshold));
        }

        page.addView(sectionLabel("Presets"), matchWrap());
        presetSpinner = new Spinner(this);
        page.addView(presetSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        presetSpinner.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                presetTouched = true;
            }
            return false;
        });
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (presetTouched && position >= 0 && position < presetNames.size()) {
                    loadPreset(presetNames.get(position));
                }
                presetTouched = false;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LinearLayout presetRow = row();
        presetNameInput = input("Name");
        Button saveButton = button("Save", STYLE_QUIET);
        Button deleteButton = button("Delete", STYLE_QUIET);
        presetRow.addView(presetNameInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        presetRow.addView(saveButton, fixedWidth(88));
        presetRow.addView(deleteButton, fixedWidth(88));
        page.addView(presetRow, topMargin(8));
        saveButton.setOnClickListener(v -> saveCurrentPreset());
        deleteButton.setOnClickListener(v -> deleteSelectedPreset());

        page.addView(sectionLabel("Tire cleaning"), matchWrap());
        LinearLayout cleanRow = row();
        cleanRow.setGravity(Gravity.BOTTOM);
        cleanSpeedInput = numberInput("170");
        Button cleanButton = button("Hold to clean", STYLE_QUIET);
        cleanRow.addView(labeledInput("Speed", cleanSpeedInput), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cleanParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        cleanParams.setMargins(dp(8), 0, 0, 0);
        cleanRow.addView(cleanButton, cleanParams);
        page.addView(cleanRow, matchWrap());
        cleanButton.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                startTireCleaning();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                sendInBackground("Clean=0\n");
            }
            return true;
        });

        return page;
    }

    private View buildSensorsPage() {
        LinearLayout page = page();

        streamButton = button("Start stream", STYLE_QUIET);
        page.addView(streamButton, topMargin(12));
        streamButton.setOnClickListener(v -> sendCommand("Telemetry", streaming ? "off" : "debug"));

        sensorBars = new SensorBarsView(this);
        page.addView(sensorBars, sizedTopMargin(LinearLayout.LayoutParams.MATCH_PARENT, dp(280), 16));

        LinearLayout readouts = row();
        sensorErrorText = readoutValue("-");
        sensorPositionText = readoutValue("- / " + sensorCentre(SensorBarsView.COUNT));
        readouts.addView(readout("Error", sensorErrorText), weighted(0, 4));
        readouts.addView(readout("Position / centre", sensorPositionText), weighted(4, 0));
        page.addView(readouts, topMargin(12));

        sensorSummaryText = label("No data", 14, MUTED);
        sensorSummaryText.setTypeface(Typeface.MONOSPACE);
        page.addView(sensorSummaryText, topMargin(10));

        TextView legend = label("Streams only while the robot is stopped. Dashed line is the threshold, "
                + "tick under the bars is the centre. Error = position - centre, same as the PID: "
                + "plus means the line is right of the centre.", 13, MUTED);
        page.addView(legend, topMargin(6));
        return page;
    }

    private View buildJoystickPage() {
        LinearLayout page = page();

        LinearLayout settingsRow = row();
        settingsRow.setGravity(Gravity.BOTTOM);
        joystickSpeedInput = numberInput("85");
        Button stopButton = button("Stop", STYLE_DANGER);
        settingsRow.addView(labeledInput("Max PWM", joystickSpeedInput), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        settingsRow.addView(stopButton, stopParams);
        page.addView(settingsRow, topMargin(12));

        JoystickView joystick = new JoystickView(this);
        page.addView(joystick, sizedTopMargin(LinearLayout.LayoutParams.MATCH_PARENT, dp(300), 12));

        joystickStatusText = label("L 0   R 0", 14, MUTED);
        joystickStatusText.setTypeface(Typeface.MONOSPACE);
        joystickStatusText.setGravity(Gravity.CENTER);
        page.addView(joystickStatusText, topMargin(8));

        joystick.setListener((forward, turn, active) -> sendManualDrive(forward, turn, !active));
        stopButton.setOnClickListener(v -> sendManualStop());
        return page;
    }

    private View buildLogPage() {
        LinearLayout page = page();
        Button clearButton = button("Clear", STYLE_QUIET);
        page.addView(clearButton, topMargin(12));

        logText = label("", 12, TEXT);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        page.addView(logText, topMargin(10));

        clearButton.setOnClickListener(v -> logText.setText(""));
        return page;
    }

    private View buildSettingsPage() {
        LinearLayout page = page();
        page.addView(sectionLabel("Bluetooth name"), matchWrap());

        LinearLayout nameRow = row();
        btNameInput = input("REGIN_V3");
        btNameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        Button renameButton = button("Rename", STYLE_QUIET);
        nameRow.addView(btNameInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        nameRow.addView(renameButton, fixedWidth(104));
        page.addView(nameRow, matchWrap());

        TextView hint = label("The module has to be in AT mode. Pair it again after renaming.", 13, MUTED);
        page.addView(hint, topMargin(6));

        btNameStatusText = label("", 13, TEXT);
        btNameStatusText.setVisibility(View.GONE);
        page.addView(btNameStatusText, topMargin(6));

        renameButton.setOnClickListener(v -> confirmBluetoothRename());
        return page;
    }

    private LinearLayout fieldRow(String leftKey, String leftLabel, String leftDefault,
                                  String rightKey, String rightLabel, String rightDefault) {
        LinearLayout row = row();
        row.addView(field(leftKey, leftLabel, leftDefault), weighted(0, 4));
        if (rightKey != null) {
            row.addView(field(rightKey, rightLabel, rightDefault), weighted(4, 0));
        } else {
            row.addView(new View(this), weighted(4, 0));
        }
        return row;
    }

    private View field(String key, String title, String defaultValue) {
        EditText input = numberInput(defaultValue);
        fields.put(key, input);
        fieldDefaults.put(key, defaultValue);
        return labeledInput(title, input);
    }

    // ---------------------------------------------------------------- robot commands

    private void startRobot() {
        if (!isConnected()) {
            showWarning("Not connected");
            return;
        }
        ArrayList<String> commands = fieldCommands();
        new Thread(() -> {
            long startRequestMs = System.currentTimeMillis();
            sendLine("Telemetry=off\n", true);
            sleepMs(40);
            sendLinesBlocking(commands);
            sleepMs(120);
            sendLine("StartNormal=1\n", true);

            // Older firmware without StartNormal
            sleepMs(650);
            if (lastStartAckMs < startRequestMs) {
                sendLine("Mode=P\n", true);
                sleepMs(120);
                sendLine("Mode=Y\n", true);
            }
        }).start();
    }

    private void stopRobot() {
        if (!isConnected()) {
            showWarning("Not connected");
            return;
        }
        new Thread(() -> {
            sendLine("Mode=N\n", true);
            sleepMs(25);
            sendLine("Manual=0,0\n", true);
            sleepMs(25);
            sendLine("Clean=0\n", true);
        }).start();
    }

    private ArrayList<String> fieldCommands() {
        ArrayList<String> commands = new ArrayList<>();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            String value = entry.getValue().getText().toString().trim().replace(',', '.');
            if (!value.isEmpty()) {
                commands.add(entry.getKey() + "=" + value + "\n");
            }
        }
        return commands;
    }

    private void sendLinesBlocking(ArrayList<String> commands) {
        for (String command : commands) {
            sendLine(command, true);
            sleepMs(30);
        }
    }

    private void startTireCleaning() {
        String speed = cleanSpeedInput.getText().toString().trim();
        String command = "CleanSpeed=" + (speed.isEmpty() ? "170" : speed) + "\n";
        new Thread(() -> {
            sendLine(command, true);
            sleepMs(25);
            sendLine("Clean=1\n", true);
        }).start();
    }

    private void sendManualDrive(float forward, float turn, boolean force) {
        int maxSpeed = clampInt(readInt(joystickSpeedInput, 85), 0, 250);
        int left = clampInt(Math.round((forward + turn) * maxSpeed), -250, 250);
        int right = clampInt(Math.round((forward - turn) * maxSpeed), -250, 250);

        if (!force) {
            long now = System.currentTimeMillis();
            if ((now - lastManualSendMs) < 70 &&
                    Math.abs(left - lastManualLeft) < 4 &&
                    Math.abs(right - lastManualRight) < 4) {
                return;
            }
            lastManualSendMs = now;
        }

        lastManualLeft = left;
        lastManualRight = right;
        joystickStatusText.setText(String.format(Locale.US, "L %d   R %d", left, right));
        String command = String.format(Locale.US, "Manual=%d,%d\n", left, right);
        new Thread(() -> sendLine(command, false)).start();
    }

    private void sendManualStop() {
        lastManualLeft = 0;
        lastManualRight = 0;
        lastManualSendMs = 0L;
        joystickStatusText.setText("L 0   R 0");
        sendInBackground("Manual=0,0\n");
    }

    private void confirmBluetoothRename() {
        String name = btNameInput.getText().toString().trim();
        if (!isValidBluetoothName(name)) {
            showBluetoothStatus("Use 1-20 characters: A-Z 0-9 space _ - .");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Rename module")
                .setMessage("The Bluetooth link can drop after the rename.")
                .setPositiveButton("Rename", (dialog, which) -> {
                    prefs().edit().putString(KEY_BT_NAME, name).apply();
                    sendInBackground("BtNameNow=" + name + "\n");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean isValidBluetoothName(String name) {
        if (name == null || name.isEmpty() || name.length() > BT_NAME_MAX_LEN) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                    c == ' ' || c == '_' || c == '-' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private void sendCommand(String key, String value) {
        if (!isConnected()) {
            showWarning("Not connected");
            return;
        }
        sendInBackground(key + "=" + value + "\n");
    }

    private void sendInBackground(String command) {
        new Thread(() -> sendLine(command, true)).start();
    }

    private void sendLine(String command, boolean log) {
        OutputStream stream = outputStream;
        if (stream == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                stream.write(command.getBytes(StandardCharsets.US_ASCII));
                stream.flush();
            }
            if (log) {
                appendLog("> " + command);
            }
        } catch (IOException ex) {
            mainHandler.post(() -> showWarning("Bluetooth send failed: " + ex.getMessage()));
            closeConnection();
        }
    }

    // ---------------------------------------------------------------- robot replies

    private void handleIncomingText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                String line = rxLineBuffer.toString().trim();
                rxLineBuffer.setLength(0);
                if (!line.isEmpty()) {
                    handleRobotLine(line);
                }
            } else if (c != '\r') {
                rxLineBuffer.append(c);
            }
        }
    }

    private void handleRobotLine(String line) {
        if (line.startsWith("DBG,")) {
            parseDebugTelemetry(line);
            return;
        }

        appendLog("< " + line + "\n");

        if (line.startsWith("STARTING,") || "Start".equals(line)) {
            lastStartAckMs = System.currentTimeMillis();
            setStreaming(false);
            hideWarning();
        } else if ("Stop".equals(line)) {
            hideWarning();
        }

        if (line.startsWith("TELEMETRY,")) {
            setStreaming("TELEMETRY,debug".equals(line));
        }

        if (line.startsWith("BT_NAME_OK,")) {
            showBluetoothStatus("Renamed. Remove the pairing in Android and pair again.");
        } else if (line.startsWith("BT_NAME_ERROR,at_no_response")) {
            showBluetoothStatus("No AT response. Put the module in AT mode and try again.");
        } else if (line.startsWith("BT_NAME_ERROR")) {
            showBluetoothStatus(line);
        }

        if (line.startsWith("!") || line.contains("ERROR") || line.contains("stop_robot_first") ||
                line.contains("unsupported") || line.startsWith("Stop robot")) {
            showWarning(line.replace("!", "").trim());
        }

        int batteryIndex = line.indexOf("Battery");
        if (batteryIndex >= 0) {
            String voltage = firstNumber(line.substring(batteryIndex));
            if (voltage != null) {
                voltageText.setText(voltage + " V");
            }
        }
    }

    private void parseDebugTelemetry(String line) {
        String[] tokens = line.split(",");
        if (tokens.length < 14) {
            return;
        }

        int position = Math.round(parseFloatToken(tokens, 1, 0.0f));
        int active = Math.round(parseFloatToken(tokens, 2, 0.0f));
        int lastEnd = Math.round(parseFloatToken(tokens, 3, 0.0f));

        // DBG,pos,active,last_end,<9 encoder/IMU fields>,S1..S16
        int count = Math.min(SensorBarsView.COUNT, tokens.length - 13);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = Math.round(parseFloatToken(tokens, 13 + i, 0.0f));
        }

        setStreaming(true);
        sensorBars.setData(values, position, active);

        // Same as PID_control(): error = position - centre, plus = line right of the centre
        int centre = sensorCentre(count);
        String lastEndText = "Last end " + (lastEnd == 1 ? "right" : "left");
        if (active > 0) {
            int error = position - centre;
            sensorErrorText.setText(error > 0 ? "+" + error : String.valueOf(error));
            sensorPositionText.setText(position + " / " + centre);
            sensorSummaryText.setText(String.format(Locale.US, "Active %-4d %s", active, lastEndText));
        } else {
            sensorErrorText.setText("-");
            sensorPositionText.setText("- / " + centre);
            sensorSummaryText.setText("Line lost   " + lastEndText);
        }
    }

    private static int sensorCentre(int sensorCount) {
        // Sensor k (1..n) weighs k * 1000, the middle of the bar is (n + 1) * 500
        return (sensorCount + 1) * 500;
    }

    private void setStreaming(boolean value) {
        streaming = value;
        if (streamButton != null) {
            streamButton.setText(value ? "Stop stream" : "Start stream");
        }
    }

    private void updateSensorThreshold() {
        EditText input = fields.get("Treshold");
        if (sensorBars != null && input != null) {
            sensorBars.setThreshold(readFloat(input, 3700.0f));
        }
    }

    // ---------------------------------------------------------------- Bluetooth

    private void onConnectClicked() {
        if (connectionState == STATE_CONNECTED) {
            closeConnection();
        } else if (connectionState == STATE_DISCONNECTED) {
            connectSelectedDevice();
        }
    }

    private void setConnectionState(int state) {
        connectionState = state;
        if (state == STATE_CONNECTED) {
            connectButton.setText("Disconnect");
        } else if (state == STATE_CONNECTING) {
            connectButton.setText("Connecting");
        } else {
            connectButton.setText("Connect");
            setStreaming(false);
        }
        deviceSpinner.setEnabled(state == STATE_DISCONNECTED);
    }

    private boolean isConnected() {
        return outputStream != null;
    }

    private void requestBluetoothPermissionIfNeeded() {
        ArrayList<String> permissions = missingBluetoothPermissions();
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT) {
            loadPairedDevices();
        }
    }

    private ArrayList<String> missingBluetoothPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions;
    }

    private void loadPairedDevices() {
        pairedDevices.clear();
        ArrayList<String> names = new ArrayList<>();

        if (bluetoothAdapter == null) {
            names.add("Bluetooth not available");
        } else if (!missingBluetoothPermissions().isEmpty()) {
            names.add("Bluetooth permission needed");
        } else if (!bluetoothAdapter.isEnabled()) {
            names.add("Bluetooth is off");
        } else {
            try {
                Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice device : bonded) {
                    pairedDevices.add(device);
                    String name = device.getName() == null ? "Unknown" : device.getName();
                    names.add(name + "  " + device.getAddress());
                }
            } catch (SecurityException ex) {
                names.clear();
                pairedDevices.clear();
                names.add("Bluetooth permission needed");
            }
            if (names.isEmpty()) {
                names.add("No paired devices");
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        deviceSpinner.setAdapter(adapter);

        String lastAddress = prefs().getString(KEY_LAST_DEVICE, "");
        for (int i = 0; i < pairedDevices.size(); i++) {
            if (pairedDevices.get(i).getAddress().equals(lastAddress)) {
                deviceSpinner.setSelection(i);
                break;
            }
        }
    }

    private void showPermissionHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Bluetooth permission")
                .setMessage("Allow Nearby devices for this app in Android settings.")
                .setPositiveButton("Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectSelectedDevice() {
        if (!missingBluetoothPermissions().isEmpty()) {
            requestBluetoothPermissionIfNeeded();
            showPermissionHelp();
            return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= pairedDevices.size()) {
            showWarning("Pair the robot's Bluetooth module in Android settings first");
            return;
        }

        BluetoothDevice device = pairedDevices.get(index);
        prefs().edit().putString(KEY_LAST_DEVICE, device.getAddress()).apply();
        setConnectionState(STATE_CONNECTING);
        hideWarning();

        new Thread(() -> {
            try {
                BluetoothSocket newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                newSocket.connect();
                socket = newSocket;
                outputStream = newSocket.getOutputStream();
                startReadThread(newSocket.getInputStream());
                mainHandler.post(() -> setConnectionState(STATE_CONNECTED));
            } catch (IOException | SecurityException ex) {
                closeConnection();
                mainHandler.post(() -> showWarning("Connection failed: " + ex.getMessage()));
            }
        }).start();
    }

    private void startReadThread(InputStream inputStream) {
        readThreadRunning = true;
        readThread = new Thread(() -> {
            byte[] buffer = new byte[256];
            while (readThreadRunning) {
                try {
                    int len = inputStream.read(buffer);
                    if (len > 0) {
                        String text = new String(buffer, 0, len, StandardCharsets.US_ASCII);
                        mainHandler.post(() -> handleIncomingText(text));
                    } else if (len < 0) {
                        throw new IOException("closed");
                    }
                } catch (IOException ex) {
                    if (readThreadRunning) {
                        closeConnection();
                        mainHandler.post(() -> showWarning("Connection lost"));
                    }
                    return;
                }
            }
        });
        readThread.start();
    }

    private void closeConnection() {
        readThreadRunning = false;
        BluetoothSocket current = socket;
        socket = null;
        outputStream = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        mainHandler.post(() -> setConnectionState(STATE_DISCONNECTED));
    }

    // ---------------------------------------------------------------- presets and settings

    private void loadSettings() {
        SharedPreferences store = prefs();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            entry.getValue().setText(store.getString("current." + entry.getKey(), fieldDefaults.get(entry.getKey())));
        }
        cleanSpeedInput.setText(store.getString(KEY_CLEAN_SPEED, "170"));
        joystickSpeedInput.setText(store.getString(KEY_JOYSTICK_SPEED, "85"));
        btNameInput.setText(store.getString(KEY_BT_NAME, "REGIN_V3"));
        updateSensorThreshold();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs().edit();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            editor.putString("current." + entry.getKey(), entry.getValue().getText().toString().trim());
        }
        editor.putString(KEY_CLEAN_SPEED, cleanSpeedInput.getText().toString().trim());
        editor.putString(KEY_JOYSTICK_SPEED, joystickSpeedInput.getText().toString().trim());
        editor.putString(KEY_BT_NAME, btNameInput.getText().toString().trim());
        editor.apply();
    }

    private void loadPresetNames() {
        presetNames.clear();
        String joined = prefs().getString(KEY_PRESET_NAMES, "");
        if (joined != null) {
            for (String part : joined.split("\\|", -1)) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    presetNames.add(name);
                }
            }
        }
        updatePresetSpinner();
    }

    private void saveCurrentPreset() {
        String name = presetNameInput.getText().toString().trim().replace("|", "");
        if (name.isEmpty()) {
            showWarning("Preset name is empty");
            return;
        }

        SharedPreferences.Editor editor = prefs().edit();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            editor.putString(presetKey(name, entry.getKey()), entry.getValue().getText().toString().trim());
        }
        if (!presetNames.contains(name)) {
            presetNames.add(name);
        }
        editor.putString(KEY_PRESET_NAMES, joinPresetNames());
        editor.apply();
        updatePresetSpinner();
        presetSpinner.setSelection(presetNames.indexOf(name));
    }

    private void loadPreset(String name) {
        presetNameInput.setText(name);
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            String current = entry.getValue().getText().toString();
            entry.getValue().setText(prefs().getString(presetKey(name, entry.getKey()), current));
        }
    }

    private void deleteSelectedPreset() {
        int index = presetSpinner.getSelectedItemPosition();
        if (index < 0 || index >= presetNames.size()) {
            return;
        }
        String name = presetNames.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Delete preset")
                .setMessage(name)
                .setPositiveButton("Delete", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs().edit();
                    for (String key : fields.keySet()) {
                        editor.remove(presetKey(name, key));
                    }
                    presetNames.remove(name);
                    editor.putString(KEY_PRESET_NAMES, joinPresetNames());
                    editor.apply();
                    updatePresetSpinner();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updatePresetSpinner() {
        ArrayList<String> names = new ArrayList<>(presetNames);
        if (names.isEmpty()) {
            names.add("No presets");
        }
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private String joinPresetNames() {
        StringBuilder builder = new StringBuilder();
        for (String name : presetNames) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(name);
        }
        return builder.toString();
    }

    private String presetKey(String presetName, String fieldName) {
        return "preset." + presetName + "." + fieldName;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- messages

    private void showWarning(String message) {
        warningText.setText(message);
        warningText.setVisibility(View.VISIBLE);
    }

    private void hideWarning() {
        warningText.setVisibility(View.GONE);
    }

    private void showBluetoothStatus(String message) {
        btNameStatusText.setText(message);
        btNameStatusText.setVisibility(View.VISIBLE);
    }

    private void appendLog(String value) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> appendLog(value));
            return;
        }
        String next = logText.getText().toString() + value;
        if (next.length() > 8000) {
            next = next.substring(next.length() - 8000);
        }
        logText.setText(next);
    }

    // ---------------------------------------------------------------- view helpers

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private TextView label(String text, int sp, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sp);
        label.setTextColor(color);
        return label;
    }

    private TextView sectionLabel(String text) {
        TextView label = label(text, 14, TEXT);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dp(20), 0, dp(2));
        return label;
    }

    private LinearLayout readout(String title, TextView value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(title, 12, MUTED), matchWrap());
        box.addView(value, matchWrap());
        return box;
    }

    private TextView readoutValue(String text) {
        TextView value = label(text, 24, TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setFontFeatureSettings("tnum");
        return value;
    }

    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setSingleLine(true);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(roundedDrawable(FIELD, FIELD));
        return input;
    }

    private EditText numberInput(String value) {
        EditText input = input(value);
        input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private LinearLayout labeledInput(String title, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView label = label(title, 12, MUTED);
        label.setPadding(0, dp(8), 0, dp(4));
        box.addView(label, matchWrap());
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return box;
    }

    private Button button(String text, int style) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        if (style == STYLE_PRIMARY) {
            button.setTextColor(BG);
            button.setBackground(buttonDrawable(TEXT, Color.rgb(196, 196, 200), TEXT));
        } else if (style == STYLE_DANGER) {
            button.setTextColor(Color.WHITE);
            button.setBackground(buttonDrawable(DANGER, Color.rgb(180, 52, 56), DANGER));
        } else {
            button.setTextColor(TEXT);
            button.setBackground(buttonDrawable(BG, PRESSED, LINE));
        }
        return button;
    }

    private StateListDrawable buttonDrawable(int normal, int pressed, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundedDrawable(pressed, stroke));
        states.addState(new int[]{}, roundedDrawable(normal, stroke));
        return states;
    }

    private GradientDrawable roundedDrawable(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(marginDp), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams sizedTopMargin(int width, int height, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, dp(marginDp), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weighted(int leftDp, int rightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(leftDp), 0, dp(rightDp), 0);
        return params;
    }

    private LinearLayout.LayoutParams fixedWidth(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(48));
        params.setMargins(dp(8), 0, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- parsing helpers

    private static String firstNumber(String text) {
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                number.append(c);
            } else if (number.length() > 0) {
                break;
            }
        }
        return number.length() > 0 ? number.toString() : null;
    }

    private int readInt(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private float readFloat(EditText input, float fallback) {
        try {
            return Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private float parseFloatToken(String[] tokens, int index, float fallback) {
        if (index < 0 || index >= tokens.length) {
            return fallback;
        }
        try {
            return Float.parseFloat(tokens[index].trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }
}
