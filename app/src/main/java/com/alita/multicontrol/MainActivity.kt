package com.alita.multicontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.util.UUID

class MainActivity : Activity() {

    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedHost: BluetoothDevice? = null

    private var keyboardReportChar: BluetoothGattCharacteristic? = null
    private var mouseReportChar: BluetoothGattCharacteristic? = null
    private var protocolMode = byteArrayOf(0x01)

    private lateinit var captureZone: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var isCapturing = false
    private var isAdvertising = false
    private val pressedKeys = LinkedHashSet<Byte>()

    private val UUID_HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val UUID_HID_INFO = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT_MAP = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
    private val UUID_CONTROL_POINT = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
    private val UUID_PROTOCOL_MODE = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
    private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

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
        btManager = getSystemService(BluetoothManager::class.java)
        btAdapter = btManager?.adapter
        buildUi()
        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleHid()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "MulticontrolAlita — BLE HID (No desconecta periféricos)"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        root.addView(title)

        val rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnStart = Button(this).apply {
            text = "1. Emitir señal BLE"
            setOnClickListener { startBleHid() }
        }
        val btnStop = Button(this).apply {
            text = "Detener señal"
            setOnClickListener { stopBleHid() }
        }
        rowTop.addView(btnStart)
        rowTop.addView(btnStop)
        root.addView(rowTop)

        captureZone = TextView(this).apply {
            text = "2. TOCA AQUÍ PARA CONTROLAR LA Y700\n(Para volver a la Tab S6: Ctrl + Shift + Espacio o toca aquí con el dedo)"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F3A5F"))
            setPadding(24, 48, 24, 48)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            setOnClickListener {
                if (isCapturing) releaseCapture() else startCapture()
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

    private fun hasBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermissions()) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                100
            )
        } else {
            log("Permisos Bluetooth OK. Toca '1. Emitir señal BLE' para comenzar.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPermissions()) {
            log("Permisos concedidos. Toca '1. Emitir señal BLE'.")
        } else {
            log("ERROR: Faltan permisos de Dispositivos Cercanos (Bluetooth).")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleHid() {
        if (!hasBtPermissions()) {
            log("Faltan permisos Bluetooth.")
            return
        }
        if (isAdvertising) {
            log("La señal BLE ya está activa.")
            return
        }

        val server = btManager?.openGattServer(this, gattCallback)
        if (server == null) {
            log("ERROR: No se pudo abrir el servidor GATT BLE.")
            return
        }
        gattServer = server

        val hidService = BluetoothGattService(UUID_HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hidInfo = BluetoothGattCharacteristic(
            UUID_HID_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val reportMap = BluetoothGattCharacteristic(
            UUID_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val controlPoint = BluetoothGattCharacteristic(
            UUID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val protoMode = BluetoothGattCharacteristic(
            UUID_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        keyboardReportChar = BluetoothGattCharacteristic(
            UUID_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(UUID_CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            addDescriptor(BluetoothGattDescriptor(UUID_REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ))
        }

        mouseReportChar = BluetoothGattCharacteristic(
            UUID_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(UUID_CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            addDescriptor(BluetoothGattDescriptor(UUID_REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ))
        }

        hidService.addCharacteristic(hidInfo)
        hidService.addCharacteristic(reportMap)
        hidService.addCharacteristic(controlPoint)
        hidService.addCharacteristic(protoMode)
        hidService.addCharacteristic(keyboardReportChar)
        hidService.addCharacteristic(mouseReportChar)

        val added = server.addService(hidService)
        log("Servicio BLE HID agregado: $added")

        advertiser = btAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("ERROR: Esta tablet no soporta emisión BLE (Advertiser null).")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID_HID_SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleHid() {
        try {
            if (isAdvertising) advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (_: Exception) {}
        isAdvertising = false
        gattServer = null
        connectedHost = null
        log("Señal BLE detenida.")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            log("SEÑAL BLE ACTIVA. Ahora busca esta Tab S6 desde el Bluetooth de la Lenovo Y700 y conéctala.")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            log("ERROR al emitir señal BLE (código $errorCode).")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device
                log("CONECTADO con ${device.name ?: device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedHost?.address == device.address) connectedHost = null
                log("DESCONECTADO de ${device.name ?: device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            val fullData = when (characteristic.uuid) {
                UUID_HID_INFO -> byteArrayOf(0x11, 0x01, 0x00, 0x02)
                UUID_REPORT_MAP -> hidDescriptor
                UUID_PROTOCOL_MODE -> protocolMode
                UUID_REPORT -> if (characteristic === keyboardReportChar) ByteArray(8) else ByteArray(4)
                else -> ByteArray(0)
            }
            val slice = if (offset >= fullData.size) ByteArray(0) else fullData.copyOfRange(offset, fullData.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == UUID_PROTOCOL_MODE && value != null && value.isNotEmpty()) {
                protocolMode = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val data = when (descriptor.uuid) {
                UUID_REPORT_REF -> {
                    if (descriptor.characteristic === keyboardReportChar) byteArrayOf(0x01, 0x01)
                    else byteArrayOf(0x02, 0x01)
                }
                UUID_CCCD -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> ByteArray(0)
            }
            val slice = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid == UUID_CCCD) {
                log("Canal HID listo para recibir teclado y mouse.")
            }
        }
    }

    private fun startCapture() {
        if (connectedHost == null) {
            log("Aviso: aún no se conecta la Y700, pero activando captura de prueba...")
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
                captureZone.text = "CONTROLANDO LA Y700\n(Ctrl + Shift + Espacio o toca aquí con el dedo para volver)"
                log("Captura ACTIVA: mouse y teclado redirigidos.")
            } else {
                captureZone.setBackgroundColor(Color.parseColor("#1F3A5F"))
                captureZone.text = "2. TOCA AQUÍ PARA CONTROLAR LA Y700\n(Para volver a la Tab S6: Ctrl + Shift + Espacio o toca aquí con el dedo)"
                log("Captura LIBERADA: control devuelto a Tab S6.")
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun sendBleReport(char: BluetoothGattCharacteristic?, data: ByteArray) {
        val host = connectedHost ?: return
        val server = gattServer ?: return
        val c = char ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(host, c, false, data)
            } else {
                c.value = data
                server.notifyCharacteristicChanged(host, c, false)
            }
        } catch (_: Exception) {}
    }

    private fun handleMouseEvent(event: MotionEvent) {
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
        sendBleReport(mouseReportChar, report)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isCapturing) return super.dispatchKeyEvent(event)

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

    private fun sendKeyReport(modifier: Int) {
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        var i = 2
        for (k in pressedKeys) {
            if (i < 8) report[i++] = k
        }
        sendBleReport(keyboardReportChar, report)
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
