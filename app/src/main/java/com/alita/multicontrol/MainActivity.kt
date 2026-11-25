package com.alita.multicontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

// Versión 1: Bluetooth HID clásico (BluetoothHidDevice) en vez de BLE.
// La Tab S6 se presenta ante la Y700 como teclado + mouse.
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var btAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var hostDevice: BluetoothDevice? = null
    @Volatile private var isRegistered = false
    private var isCapturing = false

    private val executor = Executors.newSingleThreadExecutor()
    private val pressedKeys = LinkedHashSet<Byte>()
    private var remX = 0f
    private var remY = 0f
    private var lastButtons = 0

    // Sensibilidad del mouse (1.0 = normal). Si va muy rápido, bájala (0.7); si va lento, súbela.
    private val sensitivity = 1.0f

    private lateinit var statusView: TextView
    private lateinit var captureZone: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    // Descriptor HID: Report ID 1 = teclado (8 bytes), Report ID 2 = mouse (4 bytes)
    private val hidDescriptor = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08,
        0x81, 0x01, 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00,
        0x29, 0x65, 0x81, 0x00, 0xC0,
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09,
        0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0
    ).map { it.toByte() }.toByteArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildUi()
        if (hasBtPermissions()) {
            initHid()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            hidDevice?.unregisterApp()
            btAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}
    }

    // ---------- Interfaz ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Multicontrol Alita"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }
        root.addView(statusView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(makeButton("Conectar") { connectToLastOrAsk() })
        row.addView(makeButton("Elegir tablet") { pickHost() })
        row.addView(makeButton("Reiniciar") { restartHid() })
        root.addView(row)

        captureZone = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(24, 48, 24, 48)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            setOnClickListener { if (isCapturing) releaseCapture() else startCapture() }
            setOnCapturedPointerListener { _, event ->
                handleMouseEvent(event)
                true
            }
        }
        root.addView(captureZone)
        showCaptureText(false)

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
        updateStatus()
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logText.append("• $msg\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun nameOf(d: BluetoothDevice?): String = d?.name ?: d?.address ?: "?"

    private fun updateStatus() {
        runOnUiThread {
            val host = hostDevice
            when {
                host != null -> {
                    statusView.text = "● Conectado con ${nameOf(host)}"
                    statusView.setTextColor(Color.parseColor("#4CAF50"))
                }
                isRegistered -> {
                    statusView.text = "● Listo. Toca Conectar para enlazar la Y700"
                    statusView.setTextColor(Color.parseColor("#FFC107"))
                }
                else -> {
                    statusView.text = "● Bluetooth HID inactivo (toca Reiniciar)"
                    statusView.setTextColor(Color.parseColor("#9E9E9E"))
                }
            }
        }
    }

    private fun showCaptureText(active: Boolean) {
        if (active) {
            captureZone.setBackgroundColor(Color.parseColor("#1E6F50"))
            captureZone.text = "CONTROLANDO LA OTRA TABLET\n(Ctrl + Shift + Espacio o toca aquí para volver)"
        } else {
            captureZone.setBackgroundColor(Color.parseColor("#1F3A5F"))
            captureZone.text = "TOCA AQUÍ PARA CONTROLAR LA OTRA TABLET\n(Para volver: Ctrl + Shift + Espacio o toca aquí)"
        }
    }

    // ---------- Permisos y arranque del HID ----------

    private fun hasBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPermissions()) initHid() else log("Faltan permisos de Dispositivos cercanos (Bluetooth).")
    }

    private fun initHid() {
        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth apagado o no disponible. Enciéndelo y toca Reiniciar.")
            return
        }
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHid()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    isRegistered = false
                    hostDevice = null
                    updateStatus()
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHid() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "MulticontrolAlita", "Teclado y mouse", "Alita",
            BluetoothHidDevice.SUBCLASS1_COMBO, hidDescriptor
        )
        val ok = hid.registerApp(sdp, null, null, executor, hidCallback)
        if (!ok) {
            log("No se pudo registrar. Cierra otras apps de teclado/mouse Bluetooth y toca Reiniciar.")
        }
    }

    private fun restartHid() {
        log("Reiniciando enlace...")
        try { hidDevice?.unregisterApp() } catch (_: Exception) {}
        isRegistered = false
        hostDevice = null
        updateStatus()
        Handler(Looper.getMainLooper()).postDelayed({
            if (hidDevice != null) registerHid() else initHid()
        }, 600)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            log(if (registered) "HID activo." else "HID desactivado.")
            updateStatus()
            if (registered) connectToLast(silent = true)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    device?.let {
                        getSharedPreferences("mc", MODE_PRIVATE).edit().putString("host", it.address).apply()
                    }
                    log("CONECTADO con ${nameOf(device)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (hostDevice?.address == device?.address) hostDevice = null
                    log("DESCONECTADO de ${nameOf(device)}")
                    if (isCapturing) runOnUiThread { releaseCapture() }
                }
            }
            updateStatus()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, ByteArray(if (id.toInt() == 1) 8 else 4))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ---------- Conexión con la Y700 ----------

    private fun bondedList(): List<BluetoothDevice> =
        btAdapter?.bondedDevices?.toList()?.sortedBy { it.name ?: "" } ?: emptyList()

    private fun connectToLast(silent: Boolean): Boolean {
        val hid = hidDevice ?: return false
        if (!isRegistered) return false
        val addr = getSharedPreferences("mc", MODE_PRIVATE).getString("host", null) ?: return false
        val dev = bondedList().firstOrNull { it.address == addr }
        if (dev == null) {
            if (!silent) log("La tablet guardada ya no está emparejada. Toca Elegir tablet.")
            return false
        }
        if (hostDevice?.address == dev.address) return true
        log("Conectando con ${nameOf(dev)}...")
        return hid.connect(dev)
    }

    private fun connectToLastOrAsk() {
        if (!isRegistered) {
            log("El HID no está activo. Toca Reiniciar.")
            return
        }
        if (!connectToLast(silent = false)) pickHost()
    }

    private fun pickHost() {
        if (!isRegistered) {
            log("El HID no está activo. Toca Reiniciar.")
            return
        }
        val devices = bondedList()
        if (devices.isEmpty()) {
            log("No hay tablets emparejadas. Empareja primero desde Ajustes > Bluetooth.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("¿Con cuál tablet?")
            .setItems(devices.map { nameOf(it) }.toTypedArray()) { _, i ->
                val dev = devices[i]
                getSharedPreferences("mc", MODE_PRIVATE).edit().putString("host", dev.address).apply()
                log("Conectando con ${nameOf(dev)}...")
                hidDevice?.connect(dev)
            }
            .show()
    }

    // ---------- Captura de teclado y mouse ----------

    private fun startCapture() {
        if (hostDevice == null) {
            log("Aún no hay conexión con la otra tablet. No se activa la captura.")
            return
        }
        captureZone.requestFocus()
        captureZone.requestPointerCapture()
    }

    private fun releaseCapture() {
        captureZone.releasePointerCapture()
        pressedKeys.clear()
        sendReport(1, ByteArray(8))
        sendReport(2, ByteArray(4))
        lastButtons = 0
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        isCapturing = hasCapture
        runOnUiThread { showCaptureText(hasCapture) }
        log(if (hasCapture) "Captura ACTIVA." else "Captura liberada: control devuelto a esta tablet.")
    }

    private fun sendReport(id: Int, data: ByteArray) {
        val host = hostDevice ?: return
        try {
            hidDevice?.sendReport(host, id, data)
        } catch (_: Exception) {}
    }

    private fun handleMouseEvent(event: MotionEvent) {
        var buttons = 0
        if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or 0x01
        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or 0x02
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or 0x04

        var dx = 0
        var dy = 0
        var wheel = 0

        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            // Se acumulan los decimales para que no se pierda movimiento fino ni haya "drift"
            remX += event.x * sensitivity
            remY += event.y * sensitivity
            dx = remX.toInt()
            dy = remY.toInt()
            remX -= dx
            remY -= dy
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt().coerceIn(-127, 127)
        }

        if (dx == 0 && dy == 0 && wheel == 0 && buttons == lastButtons) return
        lastButtons = buttons

        // Si el movimiento es mayor a lo que cabe en un reporte, se envía en partes
        do {
            val cx = dx.coerceIn(-127, 127)
            val cy = dy.coerceIn(-127, 127)
            sendReport(2, byteArrayOf(buttons.toByte(), cx.toByte(), cy.toByte(), wheel.toByte()))
            dx -= cx
            dy -= cy
            wheel = 0
        } while (dx != 0 || dy != 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isToggle = event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_SPACE &&
            event.isCtrlPressed && event.isShiftPressed

        if (!isCapturing) {
            if (isToggle) {
                startCapture()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isToggle) {
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

        val report = ByteArray(8)
        report[0] = mod.toByte()
        var i = 2
        for (k in pressedKeys) {
            if (i < 8) report[i++] = k
        }
        sendReport(1, report)
        return true
    }

    private val keyMap: Map<Int, Byte> = buildMap {
        // Letras A-Z -> 0x04..0x1D
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, (0x04 + i).toByte())
        // Números 1-9 -> 0x1E..0x26, 0 -> 0x27
        for (i in 0 until 9) put(KeyEvent.KEYCODE_1 + i, (0x1E + i).toByte())
        put(KeyEvent.KEYCODE_0, 0x27.toByte())
        // Teclas de función F1-F12 -> 0x3A..0x45
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, (0x3A + i).toByte())

        put(KeyEvent.KEYCODE_ENTER, 0x28.toByte())
        put(KeyEvent.KEYCODE_ESCAPE, 0x29.toByte())
        put(KeyEvent.KEYCODE_DEL, 0x2A.toByte())
        put(KeyEvent.KEYCODE_TAB, 0x2B.toByte())
        put(KeyEvent.KEYCODE_SPACE, 0x2C.toByte())
        put(KeyEvent.KEYCODE_MINUS, 0x2D.toByte())
        put(KeyEvent.KEYCODE_EQUALS, 0x2E.toByte())
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F.toByte())
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30.toByte())
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31.toByte())
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33.toByte())
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34.toByte())
        put(KeyEvent.KEYCODE_GRAVE, 0x35.toByte())
        put(KeyEvent.KEYCODE_COMMA, 0x36.toByte())
        put(KeyEvent.KEYCODE_PERIOD, 0x37.toByte())
        put(KeyEvent.KEYCODE_SLASH, 0x38.toByte())
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39.toByte())
        put(KeyEvent.KEYCODE_INSERT, 0x49.toByte())
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A.toByte())
        put(KeyEvent.KEYCODE_PAGE_UP, 0x4B.toByte())
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C.toByte())
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D.toByte())
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E.toByte())
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F.toByte())
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50.toByte())
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51.toByte())
        put(KeyEvent.KEYCODE_DPAD_UP, 0x52.toByte())
    }
}
