package com.alita.multicontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var btAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private lateinit var deviceSpinner: Spinner
    private lateinit var captureZone: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var isCapturing = false
    private val pressedKeys = LinkedHashSet<Byte>()

    // Descriptor USB HID estándar: Report ID 1 = Teclado, Report ID 2 = Mouse
    private val hidDescriptor = byteArrayOf(
        // === Teclado (Report ID 1) ===
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x06.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x06.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x65.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(),
        0x81.toByte(), 0x00.toByte(),
        0xC0.toByte(),
        // === Mouse (Report ID 2) ===
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x02.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x02.toByte(),
        0x09.toByte(), 0x01.toByte(),
        0xA1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(),
        0x09.toByte(), 0x38.toByte(),
        0x15.toByte(), 0x81.toByte(),
        0x25.toByte(), 0x7F.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x81.toByte(), 0x06.toByte(),
        0xC0.toByte(),
        0xC0.toByte()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildUi()
        checkAndRequestPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "MulticontrolAlita — Fase 1 (Bluetooth HID)"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        root.addView(title)

        val rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnInit = Button(this).apply {
            text = "1. Activar HID"
            setOnClickListener { initHidProfile() }
        }
        val btnRefresh = Button(this).apply {
            text = "Actualizar BT"
            setOnClickListener { refreshPairedDevices() }
        }
        val btnConnect = Button(this).apply {
            text = "2. Conectar"
            setOnClickListener { connectSelectedDevice() }
        }
        rowTop.addView(btnInit)
        rowTop.addView(btnRefresh)
        rowTop.addView(btnConnect)
        root.addView(rowTop)

        deviceSpinner = Spinner(this).apply {
            setPadding(0, 16, 0, 16)
        }
        root.addView(deviceSpinner)

        captureZone = TextView(this).apply {
            text = "3. TOCA AQUÍ PARA CONTROLAR LA OTRA TABLET\n(Para volver a la Tab S6: Ctrl + Shift + Espacio o toca la pantalla con el dedo)"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F3A5F"))
            setPadding(24, 48, 24, 48)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                if (isCapturing) {
                    releaseCapture()
                } else {
                    startCapture()
                }
            }
            setOnCapturedPointerListener { _, event ->
                handleMouseEvent(event)
                true
            }
        }
        root.addView(captureZone)

        logText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A8E6CF"))
            setPadding(16, 16, 16, 16)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = 20 }
            addView(logText)
        }
        root.addView(logScroll)

        setContentView(root)
    }

    private fun log(msg: String) {
        runOnUiThread {
            logText.append("• $msg\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                100
            )
        } else {
            log("Permisos Bluetooth OK.")
            refreshPairedDevices()
            initHidProfile()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPermission()) {
            log("Permisos concedidos.")
            refreshPairedDevices()
            initHidProfile()
        } else {
            log("ERROR: Falta permiso de Dispositivos Cercanos (Bluetooth).")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        if (!hasBtPermission()) return
        pairedDevices = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val names = if (pairedDevices.isEmpty()) listOf("(Sin dispositivos emparejados)")
        else pairedDevices.map { "${it.name ?: "Sin nombre"} [${it.address}]" }

        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        log("Dispositivos emparejados encontrados: ${pairedDevices.size}")
    }

    @SuppressLint("MissingPermission")
    private fun initHidProfile() {
        if (!hasBtPermission()) {
            log("Faltan permisos Bluetooth.")
            return
        }
        log("Solicitando perfil Bluetooth HID al sistema...")
        val ok = btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    log("Perfil HID obtenido. Registrando app como Teclado+Mouse...")
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "MulticontrolAlita",
                        "Teclado y Mouse HID",
                        "Alita",
                        BluetoothHidDevice.SUBCLASS1_COMBO,
                        hidDescriptor
                    )
                    val registered = hidDevice?.registerApp(
                        sdp, null, null, Executors.newCachedThreadPool(), hidCallback
                    )
                    log("Resultado registerApp(): $registered")
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
                log("Perfil HID desconectado.")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (ok != true) log("ERROR: Este dispositivo devolvió false en getProfileProxy(HID_DEVICE).")
    }

    @SuppressLint("MissingPermission")
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log("Estado registro HID -> registrado=$registered (host=${pluggedDevice?.name})")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val stateName = when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    "CONECTADO"
                }
                BluetoothProfile.STATE_CONNECTING -> "CONECTANDO..."
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost == device) connectedHost = null
                    "DESCONECTADO"
                }
                else -> "Estado $state"
            }
            log("Conexión con ${device.name}: $stateName")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        val hid = hidDevice ?: run {
            log("Primero activa HID (botón 1).")
            return
        }
        val idx = deviceSpinner.selectedItemPosition
        if (idx !in pairedDevices.indices) {
            log("Selecciona un dispositivo válido.")
            return
        }
        val target = pairedDevices[idx]
        log("Intentando conectar HID a ${target.name}...")
        val res = hid.connect(target)
        log("hid.connect() devolvió: $res")
    }

    private fun startCapture() {
        if (connectedHost == null) {
            log("Aviso: aún no hay host conectado, pero probando captura de mouse...")
        }
        captureZone.requestFocus()
        captureZone.requestPointerCapture()
    }

    private fun releaseCapture() {
        captureZone.releasePointerCapture()
        pressedKeys.clear()
        sendKeyReport(0)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        isCapturing = hasCapture
        runOnUiThread {
            if (hasCapture) {
                captureZone.setBackgroundColor(Color.parseColor("#1E6F50"))
                captureZone.text = "CONTROLANDO LA OTRA TABLET\n(Ctrl + Shift + Espacio o toca aquí con el dedo para volver)"
                log("Captura ACTIVA: mouse y teclado redirigidos.")
            } else {
                captureZone.setBackgroundColor(Color.parseColor("#1F3A5F"))
                captureZone.text = "3. TOCA AQUÍ PARA CONTROLAR LA OTRA TABLET\n(Para volver a la Tab S6: Ctrl + Shift + Espacio o toca la pantalla con el dedo)"
                log("Captura LIBERADA: control devuelto a Tab S6.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleMouseEvent(event: MotionEvent) {
        val host = connectedHost ?: return
        val hid = hidDevice ?: return

        var buttons = 0
        if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or 0x01
        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or 0x02
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or 0x04

        val dx = if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
            event.x.toInt().coerceIn(-127, 127) else 0
        val dy = if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
            event.y.toInt().coerceIn(-127, 127) else 0
        val wheel = if (event.actionMasked == MotionEvent.ACTION_SCROLL)
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt().coerceIn(-127, 127) else 0

        val report = byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())
        hid.sendReport(host, 2, report)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isCapturing) return super.dispatchKeyEvent(event)

        // Atajo de salida: Ctrl + Shift + Espacio
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_SPACE &&
            event.isCtrlPressed && event.isShiftPressed
        ) {
            releaseCapture()
            return true
        }

        val hidCode = keyMap[event.keyCode]
        if (hidCode != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (pressedKeys.size < 6) pressedKeys.add(hidCode)
            } else if (event.action == KeyEvent.ACTION_UP) {
                pressedKeys.remove(hidCode)
            }
        }

        var mod = 0
        if (event.isCtrlPressed) mod = mod or 0x01
        if (event.isShiftPressed) mod = mod or 0x02
        if (event.isAltPressed) mod = mod or 0x04
        if (event.isMetaPressed) mod = mod or 0x08

        sendKeyReport(mod)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyReport(modifier: Int) {
        val host = connectedHost ?: return
        val hid = hidDevice ?: return
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        var i = 2
        for (k in pressedKeys) {
            if (i < 8) report[i++] = k
        }
        hid.sendReport(host, 1, report)
    }

    private val keyMap = mapOf(
        KeyEvent.KEYCODE_A to 0x04.toByte(), KeyEvent.KEYCODE_B to 0x05.toByte(),
        KeyEvent.KEYCODE_C to 0x06.toByte(), KeyEvent.KEYCODE_D to 0x07.toByte(),
        KeyEvent.KEYCODE_E to 0x08.toByte(), KeyEvent.KEYCODE_F to 0x09.toByte(),
        KeyEvent.KEYCODE_G to 0x0A.toByte(), KeyEvent.KEYCODE_H to 0x0B.toByte(),
        KeyEvent.KEYCODE_I to 0x0C.toByte(), KeyEvent.KEYCODE_J to 0x0D.toByte(),
        KeyEvent.KEYCODE_K to 0x0E.toByte(), KeyEvent.KEYCODE_L to 0x0F.toByte(),
        KeyEvent.KEYCODE_M to 0x10.toByte(), KeyEvent.KEYCODE_N to 0x11.toByte(),
        KeyEvent.KEYCODE_O to 0x12.toByte(), KeyEvent.KEYCODE_P to 0x13.toByte(),
        KeyEvent.KEYCODE_Q to 0x14.toByte(), KeyEvent.KEYCODE_R to 0x15.toByte(),
        KeyEvent.KEYCODE_S to 0x16.toByte(), KeyEvent.KEYCODE_T to 0x17.toByte(),
        KeyEvent.KEYCODE_U to 0x18.toByte(), KeyEvent.KEYCODE_V to 0x19.toByte(),
        KeyEvent.KEYCODE_W to 0x1A.toByte(), KeyEvent.KEYCODE_X to 0x1B.toByte(),
        KeyEvent.KEYCODE_Y to 0x1C.toByte(), KeyEvent.KEYCODE_Z to 0x1D.toByte(),
        KeyEvent.KEYCODE_1 to 0x1E.toByte(), KeyEvent.KEYCODE_2 to 0x1F.toByte(),
        KeyEvent.KEYCODE_3 to 0x20.toByte(), KeyEvent.KEYCODE_4 to 0x21.toByte(),
        KeyEvent.KEYCODE_5 to 0x22.toByte(), KeyEvent.KEYCODE_6 to 0x23.toByte(),
        KeyEvent.KEYCODE_7 to 0x24.toByte(), KeyEvent.KEYCODE_8 to 0x25.toByte(),
        KeyEvent.KEYCODE_9 to 0x26.toByte(), KeyEvent.KEYCODE_0 to 0x27.toByte(),
        KeyEvent.KEYCODE_ENTER to 0x28.toByte(), KeyEvent.KEYCODE_ESCAPE to 0x29.toByte(),
        KeyEvent.KEYCODE_DEL to 0x2A.toByte(), KeyEvent.KEYCODE_TAB to 0x2B.toByte(),
        KeyEvent.KEYCODE_SPACE to 0x2C.toByte(), KeyEvent.KEYCODE_MINUS to 0x2D.toByte(),
        KeyEvent.KEYCODE_EQUALS to 0x2E.toByte(), KeyEvent.KEYCODE_LEFT_BRACKET to 0x2F.toByte(),
        KeyEvent.KEYCODE_RIGHT_BRACKET to 0x30.toByte(), KeyEvent.KEYCODE_BACKSLASH to 0x31.toByte(),
        KeyEvent.KEYCODE_SEMICOLON to 0x33.toByte(), KeyEvent.KEYCODE_APOSTROPHE to 0x34.toByte(),
        KeyEvent.KEYCODE_GRAVE to 0x35.toByte(), KeyEvent.KEYCODE_COMMA to 0x36.toByte(),
        KeyEvent.KEYCODE_PERIOD to 0x37.toByte(), KeyEvent.KEYCODE_SLASH to 0x38.toByte(),
        KeyEvent.KEYCODE_FORWARD_DEL to 0x4C.toByte(),
        KeyEvent.KEYCODE_DPAD_RIGHT to 0x4F.toByte(), KeyEvent.KEYCODE_DPAD_LEFT to 0x50.toByte(),
        KeyEvent.KEYCODE_DPAD_DOWN to 0x51.toByte(), KeyEvent.KEYCODE_DPAD_UP to 0x52.toByte()
    )
}
