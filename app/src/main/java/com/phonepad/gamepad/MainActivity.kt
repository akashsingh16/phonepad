package com.phonepad.gamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

enum class LayoutMode { GAMEPAD, KEYBOARD }

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhonePad"
    }

    // -- Bluetooth HID --
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val foundDevices = mutableListOf<BluetoothDevice>()
    private var discoveryReceiver: BroadcastReceiver? = null

    // -- WiFi --
    private var wifiTransport: WifiTransport? = null
    private var useWifi = false

    // -- UI --
    private lateinit var statusText: TextView
    private lateinit var modeSwitchBtn: Button
    private var currentLayoutMode = LayoutMode.GAMEPAD
    private var editLayoutMode = false
    private val actionButtonViews = mutableListOf<Button>()

    // -- Gamepad report state (unchanged from previous version) --
    private var buttonMask = 0
    private var hat = 8
    private var lx = 128; private var ly = 128
    private var rx = 128; private var ry = 128
    private var lt = 0; private var rt = 0

    // -- Keyboard/mouse report state --
    private val pressedKeys = mutableSetOf<Int>()
    private var activeModifiers = 0
    private var mouseButtonMask = 0
    private var mouseSensitivity = 1.0f
    private var lastSentMods = -1
    private var lastSentKeys: Set<Int> = emptySet()
    private val moveKeys = setOf(HidKeyCodes.W, HidKeyCodes.A, HidKeyCodes.S, HidKeyCodes.D)
    private var currentLayout: MutableList<KeyBinding> = mutableListOf()
    private var pendingTapRelease: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val directExecutor = Executor { it.run() }

    // ---------------------------------------------------------------
    // Bluetooth HID callbacks
    // ---------------------------------------------------------------

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged registered=$registered device=$pluggedDevice")
            runOnUiThread {
                if (!registered) {
                    statusText.text = "BT HID registration failed \u2014 check Bluetooth is on and retry"
                } else {
                    refreshStatusText()
                    tryAutoConnect()
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        device?.let { LayoutStore.saveLastDeviceMac(this@MainActivity, it.address) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> connectedDevice = null
                }
                refreshStatusText()
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                mainHandler.postDelayed({ registerHidApp() }, 500)
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    // ---------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startBluetooth()
        else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showGamepadLayout()
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryReceiver?.let { runCatching { unregisterReceiver(it) } }
        hidDevice?.apply {
            connectedDevice?.let { disconnect(it) }
            unregisterApp()
        }
        wifiTransport?.close()
    }

    // ---------------------------------------------------------------
    // Layout switching (Gamepad <-> Keyboard) — transport/pairing state
    // (hidDevice / connectedDevice / wifiTransport) is untouched by this.
    // ---------------------------------------------------------------

    private fun showGamepadLayout() {
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        bindTransportSwitch()
        bindLayoutToggle()
        wireButtons()
        wireSticks()
        refreshStatusText()
    }

    private fun showKeyboardLayout() {
        setContentView(R.layout.activity_keyboard)
        statusText = findViewById(R.id.statusText)
        bindTransportSwitch()
        bindLayoutToggle()
        bindGear()
        bindEditLayoutToggle()
        findViewById<Button>(R.id.btnConnectDevice).setOnClickListener { showDevicePicker() }

        currentLayout = LayoutStore.loadLayout(this)
        renderActionButtons()
        applyStickConfig("left", R.id.leftStick)
        applyPadConfig("mouse", R.id.mousePad)
        wireKeyboardLeftStick()
        wireTouchpad()
        bindSensitivitySeek()
        findViewById<Button>(R.id.btnAddKey).setOnClickListener {
            showCatalogPicker { chosen ->
                currentLayout.add(chosen.copy(id = chosen.id + "_" + System.currentTimeMillis()))
                persistAndRenderGrid()
            }
        }
        refreshStatusText()
    }

    private fun bindTransportSwitch() {
        modeSwitchBtn = findViewById(R.id.btnModeSwitch)
        modeSwitchBtn.text = if (useWifi) "Switch to BT" else "Switch to WiFi"
        modeSwitchBtn.setOnClickListener {
            if (!useWifi) switchToWifi() else switchToBluetooth()
        }
    }

    private fun bindLayoutToggle() {
        val btn = findViewById<Button>(R.id.btnLayoutToggle)
        btn.text = if (currentLayoutMode == LayoutMode.GAMEPAD) "\u2328 Keyboard" else "\uD83C\uDFAE Gamepad"
        btn.setOnClickListener {
            if (currentLayoutMode == LayoutMode.GAMEPAD) {
                currentLayoutMode = LayoutMode.KEYBOARD
                showKeyboardLayout()
            } else {
                releaseAllKeyboardState()
                editLayoutMode = false
                currentLayoutMode = LayoutMode.GAMEPAD
                showGamepadLayout()
            }
        }
    }

    private fun bindGear() {
        val gear = findViewById<Button>(R.id.btnGear)
        val panel = findViewById<LinearLayout>(R.id.controlPanel)
        gear.setOnClickListener {
            panel.visibility = if (panel.visibility == android.view.View.VISIBLE)
                android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun bindEditLayoutToggle() {
        val toggle = findViewById<ToggleButton>(R.id.btnEditLayout)
        toggle.isChecked = editLayoutMode
        toggle.setOnCheckedChangeListener { _, checked ->
            editLayoutMode = checked
            findViewById<JoystickView>(R.id.leftStick).editMode = checked
            findViewById<TouchpadView>(R.id.mousePad).editMode = checked
            if (checked) releaseAllKeyboardState() // avoid stuck keys/clicks while rearranging
            statusText.text = if (checked) "Edit Layout: drag to move, pinch to resize" else "Edit Layout off"
        }
    }

    private fun refreshStatusText() {
        statusText.text = when {
            useWifi && wifiTransport != null -> "WiFi mode \u2014 controls active"
            useWifi -> "WiFi mode \u2014 tap Switch to WiFi to enter PC IP"
            connectedDevice != null -> "BT connected: ${connectedDevice?.name ?: connectedDevice?.address}"
            hidDevice != null -> "BT ready \u2014 tap Connect\u2026 to pick a device"
            else -> "Starting\u2026"
        }
    }

    // ---------------------------------------------------------------
    // Bluetooth setup
    // ---------------------------------------------------------------

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .forEach { needed.add(it) }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else startBluetooth()
    }

    private fun startBluetooth() {
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Turn Bluetooth on then reopen the app"
            return
        }
        val ok = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        Log.d(TAG, "getProfileProxy result=$ok")
        statusText.text = "Getting HID profile proxy\u2026"
    }

    private fun registerHidApp() {
        val hd = hidDevice ?: run {
            Log.e(TAG, "registerHidApp called but hidDevice is null")
            return
        }

        // Combined descriptor: gamepad (Report ID 1, unchanged) + keyboard (ID 2) + mouse (ID 3).
        val combinedDescriptor = HidGamepadDescriptor.DESCRIPTOR + HidKeyboardMouseDescriptor.DESCRIPTOR

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PhonePad",
            "Phone Bluetooth Gamepad + Keyboard/Mouse",
            "PhonePad",
            0xC0.toByte(), // Peripheral minor class: keyboard+pointing "combo" hint
            combinedDescriptor
        )

        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 800, 11250, 11250
        )

        val result = hd.registerApp(sdp, null, qos, directExecutor, hidCallback)
        Log.d(TAG, "registerApp returned $result")
        if (!result) {
            runOnUiThread { statusText.text = "registerApp() returned false \u2014 see README troubleshooting" }
        }
    }

    // ---------------------------------------------------------------
    // Phone-initiated pairing & auto-reconnect (like a typical remote app —
    // no need to go into the PC/TV's own Bluetooth settings each time)
    // ---------------------------------------------------------------

    private fun showDevicePicker() {
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        val labels = (bonded.map { "${it.name ?: it.address} (paired)" } + "Scan for new device\u2026").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(labels) { _, which ->
                if (which < bonded.size) connectToDevice(bonded[which]) else startDeviceScan()
            }
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        statusText.text = "Connecting to ${device.name ?: device.address}\u2026"
        val ok = hidDevice?.connect(device) ?: false
        if (ok) LayoutStore.saveLastDeviceMac(this, device.address)
    }

    private fun startDeviceScan() {
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter ?: return
        foundDevices.clear()
        statusText.text = "Scanning\u2026 make sure your PC/TV Bluetooth is on"
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let { d -> if (foundDevices.none { it.address == d.address }) foundDevices.add(d) }
            }
        }
        discoveryReceiver = receiver
        registerReceiver(receiver, filter)
        adapter.startDiscovery()
        mainHandler.postDelayed({
            adapter.cancelDiscovery()
            runCatching { unregisterReceiver(receiver) }
            discoveryReceiver = null
            showScanResults()
        }, 8000)
    }

    private fun showScanResults() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "No devices found nearby", Toast.LENGTH_LONG).show()
            refreshStatusText()
            return
        }
        val labels = foundDevices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select device to pair")
            .setItems(labels) { _, which ->
                val device = foundDevices[which]
                statusText.text = "Pairing with ${device.name ?: device.address}\u2026"
                device.createBond()
                // Bonding is async; give it a few seconds before attempting the HID connect.
                mainHandler.postDelayed({ connectToDevice(device) }, 3000)
            }
            .show()
    }

    private fun tryAutoConnect() {
        if (connectedDevice != null) return
        val mac = LayoutStore.loadLastDeviceMac(this) ?: return
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter ?: return
        val device = adapter.bondedDevices?.find { it.address == mac } ?: return
        statusText.text = "Reconnecting to ${device.name ?: device.address}\u2026"
        hidDevice?.connect(device)
    }

    // ---------------------------------------------------------------
    // WiFi mode
    // ---------------------------------------------------------------

    private fun switchToWifi() {
        val input = android.widget.EditText(this).apply {
            hint = "192.168.x.x"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("PC IP address")
            .setMessage("Enter your Pop!_OS PC's local IP (run `hostname -I` on the PC)")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isBlank()) return@setPositiveButton
                useWifi = true
                modeSwitchBtn.text = "Switch to BT"
                statusText.text = "Connecting to $ip:7777\u2026"
                wifiTransport = WifiTransport(ip, 7777,
                    onStatus = { msg -> runOnUiThread { statusText.text = msg } }
                )
                wifiTransport?.connect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchToBluetooth() {
        useWifi = false
        wifiTransport?.close()
        wifiTransport = null
        modeSwitchBtn.text = "Switch to WiFi"
        refreshStatusText()
    }

    // ---------------------------------------------------------------
    // Gamepad mode wiring — UNCHANGED from the previous version
    // ---------------------------------------------------------------

    private fun wireButtons() {
        val btnMap = mapOf(
            R.id.btnA to HidGamepadDescriptor.BTN_A,
            R.id.btnB to HidGamepadDescriptor.BTN_B,
            R.id.btnX to HidGamepadDescriptor.BTN_X,
            R.id.btnY to HidGamepadDescriptor.BTN_Y,
            R.id.btnLB to HidGamepadDescriptor.BTN_LB,
            R.id.btnRB to HidGamepadDescriptor.BTN_RB,
            R.id.btnBack to HidGamepadDescriptor.BTN_BACK,
            R.id.btnStart to HidGamepadDescriptor.BTN_START,
            R.id.btnGuide to HidGamepadDescriptor.BTN_GUIDE
        )
        btnMap.forEach { (viewId, bit) ->
            findViewById<Button>(viewId).setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> setGamepadButton(bit, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setGamepadButton(bit, false)
                }
                true
            }
        }
        val triggerMap = mapOf(R.id.btnLT to true, R.id.btnRT to false)
        triggerMap.forEach { (viewId, isLeft) ->
            findViewById<Button>(viewId).setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { if (isLeft) lt = 255 else rt = 255; sendReport() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (isLeft) lt = 0 else rt = 0; sendReport() }
                }
                true
            }
        }
        val dpadMap = mapOf(R.id.btnUp to 0, R.id.btnRight to 2, R.id.btnDown to 4, R.id.btnLeft to 6)
        dpadMap.forEach { (viewId, dir) ->
            findViewById<Button>(viewId).setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { hat = dir; sendReport() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hat = 8; sendReport() }
                }
                true
            }
        }
    }

    private fun wireSticks() {
        findViewById<JoystickView>(R.id.leftStick).onMove = { nx, ny ->
            lx = (128 + nx * 127).toInt(); ly = (128 + ny * 127).toInt(); sendReport()
        }
        findViewById<JoystickView>(R.id.rightStick).onMove = { nx, ny ->
            rx = (128 + nx * 127).toInt(); ry = (128 + ny * 127).toInt(); sendReport()
        }
    }

    private fun setGamepadButton(bit: Int, down: Boolean) {
        buttonMask = if (down) buttonMask or (1 shl bit) else buttonMask and (1 shl bit).inv()
        sendReport()
    }

    private fun sendReport() {
        val report = HidGamepadDescriptor.buildReport(buttonMask, hat, lx, ly, rx, ry, lt, rt)
        if (useWifi) {
            wifiTransport?.sendGamepad(report)
        } else {
            connectedDevice?.let { dev ->
                hidDevice?.sendReport(dev, HidGamepadDescriptor.REPORT_ID.toInt(), report)
            }
        }
    }

    // ---------------------------------------------------------------
    // Keyboard mode wiring
    // ---------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun pxToDp(px: Int): Int = (px / resources.displayMetrics.density).toInt()

    private fun defaultPosFor(index: Int): Pair<Int, Int> {
        val col = index % 4
        val row = index / 4
        return Pair(24 + col * 76, 70 + row * 58)
    }

    private fun applyStickConfig(key: String, viewId: Int) {
        val cfg = LayoutStore.loadStickConfig(this, key) ?: return
        val stick = findViewById<JoystickView>(viewId)
        val lp = stick.layoutParams as FrameLayout.LayoutParams
        lp.gravity = 0
        lp.leftMargin = dp(cfg.x); lp.topMargin = dp(cfg.y)
        lp.width = dp(cfg.size); lp.height = dp(cfg.size)
        stick.layoutParams = lp
    }

    private fun applyPadConfig(key: String, viewId: Int) {
        val cfg = LayoutStore.loadPadConfig(this, key) ?: return
        val pad = findViewById<TouchpadView>(viewId)
        val lp = pad.layoutParams as FrameLayout.LayoutParams
        lp.gravity = 0
        lp.leftMargin = dp(cfg.x); lp.topMargin = dp(cfg.y)
        lp.width = dp(cfg.width); lp.height = dp(cfg.height)
        pad.layoutParams = lp
    }

    private fun renderActionButtons() {
        val canvas = findViewById<FrameLayout>(R.id.playCanvas)
        actionButtonViews.forEach { canvas.removeView(it) }
        actionButtonViews.clear()
        currentLayout.forEachIndexed { index, binding ->
            val sizeDp = if (binding.size > 0) binding.size else 60
            val (defX, defY) = defaultPosFor(index)
            val xDp = if (binding.x >= 0) binding.x else defX
            val yDp = if (binding.y >= 0) binding.y else defY
            val btn = Button(this).apply {
                text = binding.label
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A2A31"))
                textSize = 12f
                val lp = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                lp.leftMargin = dp(xDp); lp.topMargin = dp(yDp)
                layoutParams = lp
            }
            attachButtonTouch(btn, binding)
            canvas.addView(btn)
            actionButtonViews.add(btn)
        }
    }

    private fun attachButtonTouch(btn: Button, binding: KeyBinding) {
        var startRawX = 0f; var startRawY = 0f
        var startLeft = 0; var startTop = 0
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val lp = btn.layoutParams as FrameLayout.LayoutParams
                val newSize = (btn.width * detector.scaleFactor).toInt().coerceIn(dp(40), dp(140))
                lp.width = newSize; lp.height = newSize
                btn.layoutParams = lp
                return true
            }
        })
        btn.setOnTouchListener { v, event ->
            if (editLayoutMode) {
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX; startRawY = event.rawY
                        startLeft = v.left; startTop = v.top
                    }
                    MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = (startLeft + (event.rawX - startRawX)).toInt().coerceAtLeast(0)
                        lp.topMargin = (startTop + (event.rawY - startRawY)).toInt().coerceAtLeast(0)
                        v.layoutParams = lp
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        updateBindingPosition(binding, pxToDp(lp.leftMargin), pxToDp(lp.topMargin), pxToDp(lp.width))
                    }
                }
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> applyBinding(binding, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> applyBinding(binding, false)
                }
            }
            true
        }
        btn.setOnLongClickListener {
            if (!editLayoutMode) showEditDialog(binding)
            true
        }
    }

    private fun updateBindingPosition(binding: KeyBinding, x: Int, y: Int, size: Int) {
        val idx = currentLayout.indexOfFirst { it.id == binding.id }
        if (idx >= 0) currentLayout[idx] = currentLayout[idx].copy(x = x, y = y, size = size)
        LayoutStore.saveLayout(this, currentLayout)
    }

    private fun applyBinding(binding: KeyBinding, down: Boolean) {
        when (binding.type) {
            BindingType.KEY -> {
                if (down) pressedKeys.add(binding.code) else pressedKeys.remove(binding.code)
                sendKeyboardReport()
            }
            BindingType.MODIFIER -> {
                activeModifiers = if (down) activeModifiers or binding.code else activeModifiers and binding.code.inv()
                sendKeyboardReport()
            }
            BindingType.MOUSE -> {
                mouseButtonMask = if (down) mouseButtonMask or binding.code else mouseButtonMask and binding.code.inv()
                sendMouseReport(0, 0, 0)
            }
        }
    }

    private fun showEditDialog(binding: KeyBinding) {
        AlertDialog.Builder(this)
            .setTitle(binding.label)
            .setItems(arrayOf("Change key", "Remove")) { _, which ->
                when (which) {
                    0 -> showCatalogPicker { chosen -> replaceBinding(binding, chosen) }
                    1 -> removeBinding(binding)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCatalogPicker(onPicked: (KeyBinding) -> Unit) {
        val labels = BindingCatalog.ALL.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose key")
            .setItems(labels) { _, which -> onPicked(BindingCatalog.ALL[which]) }
            .show()
    }

    private fun replaceBinding(old: KeyBinding, chosen: KeyBinding) {
        val idx = currentLayout.indexOfFirst { it.id == old.id }
        // Keep the position/size the user already set — only the key identity changes.
        if (idx >= 0) currentLayout[idx] = chosen.copy(id = old.id, x = old.x, y = old.y, size = old.size)
        persistAndRenderGrid()
    }

    private fun removeBinding(binding: KeyBinding) {
        currentLayout.removeAll { it.id == binding.id }
        persistAndRenderGrid()
    }

    private fun persistAndRenderGrid() {
        LayoutStore.saveLayout(this, currentLayout)
        renderActionButtons()
    }

    private fun bindSensitivitySeek() {
        val seek = findViewById<SeekBar>(R.id.sensitivitySeek)
        val stored = LayoutStore.loadSensitivity(this) // 1..30
        seek.progress = (stored - 1).coerceIn(0, 29)
        mouseSensitivity = stored / 10f
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                mouseSensitivity = (progress + 1) / 10f
                if (fromUser) LayoutStore.saveSensitivity(this@MainActivity, progress + 1)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun wireKeyboardLeftStick() {
        val stick = findViewById<JoystickView>(R.id.leftStick)
        stick.onPositionChanged = { x, y, size -> LayoutStore.saveStickConfig(this, "left", StickConfig(x, y, size)) }
        stick.onMove = { nx, ny ->
            val deadzone = 0.35f
            val desired = mutableSetOf<Int>()
            if (ny < -deadzone) desired.add(HidKeyCodes.W)
            if (ny > deadzone) desired.add(HidKeyCodes.S)
            if (nx < -deadzone) desired.add(HidKeyCodes.A)
            if (nx > deadzone) desired.add(HidKeyCodes.D)
            if (desired != (pressedKeys intersect moveKeys)) {
                pressedKeys.removeAll(moveKeys)
                pressedKeys.addAll(desired)
                sendKeyboardReport()
            }
        }
    }

    /**
     * Direct trackpad behavior: raw finger-movement deltas from [TouchpadView]
     * are scaled by sensitivity and sent as-is. No deadzone, no "hold to keep
     * moving" — move your finger, the cursor moves; stop, it stops; lift and
     * reposition to swipe again, same as any laptop trackpad.
     *
     * Tap = quick left click (auto press+release). Double-tap-then-drag =
     * hold left button for the duration of the drag (drag-select /
     * drag-and-drop) — the pending click-release from a plain tap is
     * cancelled if a drag-hold starts before it fires, so the two gestures
     * never fight over the button state.
     */
    private fun wireTouchpad() {
        val pad = findViewById<TouchpadView>(R.id.mousePad)
        pad.onPositionChanged = { x, y, w, h -> LayoutStore.savePadConfig(this, "mouse", PadConfig(x, y, w, h)) }
        pad.onDelta = { dx, dy ->
            val sx = (dx * mouseSensitivity).toInt()
            val sy = (dy * mouseSensitivity).toInt()
            if (sx != 0 || sy != 0) sendMouseReport(sx, sy, 0)
        }
        pad.onTap = {
            pendingTapRelease?.let { mainHandler.removeCallbacks(it) }
            mouseButtonMask = mouseButtonMask or HidKeyboardMouseDescriptor.MOUSE_LEFT
            sendMouseReport(0, 0, 0)
            val release = Runnable {
                mouseButtonMask = mouseButtonMask and HidKeyboardMouseDescriptor.MOUSE_LEFT.inv()
                sendMouseReport(0, 0, 0)
                pendingTapRelease = null
            }
            pendingTapRelease = release
            mainHandler.postDelayed(release, 60)
        }
        pad.onDragHoldChanged = { holding ->
            pendingTapRelease?.let { mainHandler.removeCallbacks(it) }
            pendingTapRelease = null
            mouseButtonMask = if (holding) mouseButtonMask or HidKeyboardMouseDescriptor.MOUSE_LEFT
                               else mouseButtonMask and HidKeyboardMouseDescriptor.MOUSE_LEFT.inv()
            sendMouseReport(0, 0, 0)
        }
    }

    private fun sendKeyboardReport() {
        if (activeModifiers == lastSentMods && pressedKeys == lastSentKeys) return
        lastSentMods = activeModifiers
        lastSentKeys = pressedKeys.toSet()
        if (useWifi) {
            wifiTransport?.sendKeyboard(activeModifiers, pressedKeys)
        } else {
            connectedDevice?.let { dev ->
                val report = HidKeyboardMouseDescriptor.buildKeyboardReport(activeModifiers, pressedKeys)
                hidDevice?.sendReport(dev, HidKeyboardMouseDescriptor.REPORT_ID_KEYBOARD.toInt(), report)
            }
        }
    }

    private fun sendMouseReport(dx: Int, dy: Int, wheel: Int) {
        if (useWifi) {
            wifiTransport?.sendMouse(mouseButtonMask, dx, dy, wheel)
        } else {
            connectedDevice?.let { dev ->
                val report = HidKeyboardMouseDescriptor.buildMouseReport(mouseButtonMask, dx, dy, wheel)
                hidDevice?.sendReport(dev, HidKeyboardMouseDescriptor.REPORT_ID_MOUSE.toInt(), report)
            }
        }
    }

    private fun releaseAllKeyboardState() {
        pendingTapRelease?.let { mainHandler.removeCallbacks(it) }
        pendingTapRelease = null
        pressedKeys.clear()
        activeModifiers = 0
        mouseButtonMask = 0
        wifiTransport?.sendKeyboard(0, emptySet())
        wifiTransport?.sendMouse(0, 0, 0, 0)
        connectedDevice?.let { dev ->
            hidDevice?.sendReport(dev, HidKeyboardMouseDescriptor.REPORT_ID_KEYBOARD.toInt(),
                HidKeyboardMouseDescriptor.buildKeyboardReport(0, emptySet()))
            hidDevice?.sendReport(dev, HidKeyboardMouseDescriptor.REPORT_ID_MOUSE.toInt(),
                HidKeyboardMouseDescriptor.buildMouseReport(0, 0, 0, 0))
        }
        lastSentMods = -1
        lastSentKeys = emptySet()
    }
}
