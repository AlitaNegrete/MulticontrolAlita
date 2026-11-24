package com.alita.multicontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.*
import java.util.ArrayDeque
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
    private val serviceQueue = ArrayDeque<BluetoothGattService>()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnBle: TextView
    private lateinit var speedLabel: TextView
    private lateinit var captureZone: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var helpScroll: ScrollView

    private var isCapturing = false
    private var isAdvertising = false
    private val pressedKeys = LinkedHashSet<Byte>()

    private val mouseLock = Any()
    private var accDx = 0f
    private var accDy = 0f
    private var accWheel = 0f
    private var currentButtons = 0
    private var lastSentButtons = 0
    private var sensitivity = 1.0f
    @Volatile private var isBleBusy = false
    @Volatile private var lastSendTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mouseTicker = object : Runnable {
        override fun run() {
            flushMouseReport()
            mainHandler.postDelayed(this, 14L)
        }
    }

    // Servicios estándar HOGP (HID sobre BLE)
    private val UUID_DIS_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val UUID_PNP_ID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    private val UUID_BAT_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val UUID_BAT_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val UUID_HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val UUID_HID_INFO = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT_MAP = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
    private val UUID_CONTROL_POINT = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
    private val UUID_PROTOCOL_MODE = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
    private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val UUID_REPORT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

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
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        btManager = getSystemService(BluetoothManager::class.java)
        btAdapter = btManager?.adapter
        buildUi()
        checkAndRequestPermissions()
        mainHandler.post(mouseTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(mouseTicker)
        stopBleHid()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun makePillButton(label: String, fillHex: String = "#232736", onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E5E7EB"))
            gravity = Gravity.CENTER
            background = roundedBg(fillHex, 10, "#343B52")
            setPadding(dp(9), dp(6), dp(9), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1117"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) }
            background = roundedBg("#6B7280", 10)
        }
        topBar.addView(statusDot)

        statusText = TextView(this).apply {
            text = "Iniciando..."
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E5E7EB"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        topBar.addView(statusText)

        val btnMinus = makePillButton("−") { adjustSpeed(-0.2f) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(3)
            }
        }
        speedLabel = TextView(this).apply {
            text = String.format("%.1fx", sensitivity)
            textSize = 12f
            setTextColor(Color.parseColor("#C4B5FD"))
            setPadding(dp(3), 0, dp(3), 0)
        }
        val btnPlus = makePillButton("+") { adjustSpeed(0.2f) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(3)
                rightMargin = dp(5)
            }
        }

        val btnReconn = makePillButton("↺", "#1E3A4C") {
            if (!isAdvertising) startBleHid() else tryReconnectLastHost(manual = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(4)
            }
        }

        btnBle = makePillButton("BLE", "#2D254C") {
            if (isAdvertising) stopBleHid() else startBleHid()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(4)
            }
        }

        val btnLog = makePillButton("Log") {
            helpScroll.visibility = View.GONE
            logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(4)
            }
        }

        val btnHelp = makePillButton("?", "#1F3A5F") {
            logScroll.visibility = View.GONE
            helpScroll.visibility = if (helpScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        topBar.addView(btnMinus)
        topBar.addView(speedLabel)
        topBar.addView(btnPlus)
        topBar.addView(btnReconn)
        topBar.addView(btnBle)
        topBar.addView(btnLog)
        topBar.addView(btnHelp)
        root.addView(topBar)

        captureZone = TextView(this).apply {
            text = "CONTROLAR Y700\nEsperando conexión BLE..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#243056", 14, "#4C63A6")
            setPadding(dp(16), dp(18), dp(16), dp(18))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
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
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A7F3D0"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        logScroll = ScrollView(this).apply {
            background = roundedBg("#161923", 10, "#262B3D")
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(130)
            ).apply { topMargin = dp(10) }
            addView(logText)
        }
        root.addView(logScroll)

        val helpText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E5E7EB"))
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = """
                USO DIARIO / AL VOLVER DE UN BREAK
                1. Deja esta app abierta en BLE ON.
                2. Toca ↺ para buscar la Y700, o toca esta tablet en el Bluetooth de la Y700.
                3. Cuando el punto esté verde, toca el botón azul. Salir: Ctrl + Shift + Espacio.

                PRIMER EMPAREJAMIENTO (CLAVE)
                1. Olvida el vínculo anterior en AMBAS tablets (y NO abras Ajustes BT en la Tab S6).
                2. Abre esta app (BLE ON) y busca desde la Y700.
                3. Elige el ícono de BLUETOOTH (nunca el de llamada/celular) y acepta vincular.
            """.trimIndent()
        }
        helpScroll = ScrollView(this).apply {
            background = roundedBg("#161D2F", 10, "#3B5284")
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(155)
            ).apply { topMargin = dp(10) }
            addView(helpText)
        }
        root.addView(helpScroll)

        setContentView(root)
    }

    private fun adjustSpeed(delta: Float) {
        sensitivity = (sensitivity + delta).coerceIn(0.4f, 4.0f)
        speedLabel.text = String.format("%.1fx", sensitivity)
        log("Velocidad mouse: ${speedLabel.text}")
    }

    private fun updateCaptureZoneUi() {
        runOnUiThread {
            when {
                isCapturing -> {
                    captureZone.background = roundedBg("#116B52", 14, "#34D399")
                    captureZone.text = "CONTROLANDO Y700\nSalir: Ctrl + Shift + Espacio (o toca aquí)"
                }
                connectedHost != null -> {
                    captureZone.background = roundedBg("#243056", 14, "#4C63A6")
                    captureZone.text = "CONTROLAR Y700\nClic aquí  •  Salir: Ctrl + Shift + Espacio"
                }
                else -> {
                    captureZone.background = roundedBg("#1E2230", 14, "#374151")
                    captureZone.text = "SIN CONEXIÓN\nToca ↺ o conecta desde la Y700"
                }
            }
        }
    }

    private fun updateStatus(text: String, dotHex: String) {
        runOnUiThread {
            statusText.text = text
            statusDot.background = roundedBg(dotHex, 10)
            btnBle.text = if (isAdvertising) "BLE ON" else "BLE OFF"
            btnBle.background = roundedBg(if (isAdvertising) "#3B2866" else "#232736", 10, "#5B4499")
        }
        updateCaptureZoneUi()
    }

    private fun log(msg: String) {
        runOnUiThread {
            logText.append("• $msg\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun saveLastHost(address: String) {
        getSharedPreferences("multicontrol_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_host_addr", address).apply()
    }

    private fun getLastHostAddress(): String? {
        return getSharedPreferences("multicontrol_prefs", Context.MODE_PRIVATE)
            .getString("last_host_addr", null)
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
            startBleHid()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPermissions()) {
            startBleHid()
        } else {
            updateStatus("Sin permiso BT", "#EF4444")
            log("ERROR: Faltan permisos de Dispositivos Cercanos.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleHid() {
        if (!hasBtPermissions()) return
        if (gattServer != null) return

        val server = btManager?.openGattServer(this, gattCallback)
        if (server == null) {
            updateStatus("Error GATT", "#EF4444")
            log("ERROR: No se pudo abrir el servidor GATT BLE.")
            return
        }
        gattServer = server
        serviceQueue.clear()

        // 1. Device Information Service (PnP ID)
        val disService = BluetoothGattService(UUID_DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    UUID_PNP_ID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }

        // 2. Battery Service
        val batService = BluetoothGattService(UUID_BAT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    UUID_BAT_LEVEL,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }

        // 3. HID Service con cifrado obligatorio para guardar llave BLE permanente
        val hidService = BluetoothGattService(UUID_HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hidInfo = BluetoothGattCharacteristic(
            UUID_HID_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        val reportMap = BluetoothGattCharacteristic(
            UUID_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        val controlPoint = BluetoothGattCharacteristic(
            UUID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )
        val protoMode = BluetoothGattCharacteristic(
            UUID_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        keyboardReportChar = BluetoothGattCharacteristic(
            UUID_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID_CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
            addDescriptor(
                BluetoothGattDescriptor(UUID_REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
            )
        }

        mouseReportChar = BluetoothGattCharacteristic(
            UUID_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID_CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
            addDescriptor(
                BluetoothGattDescriptor(UUID_REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
            )
        }

        hidService.addCharacteristic(hidInfo)
        hidService.addCharacteristic(reportMap)
        hidService.addCharacteristic(controlPoint)
        hidService.addCharacteristic(protoMode)
        hidService.addCharacteristic(keyboardReportChar)
        hidService.addCharacteristic(mouseReportChar)

        serviceQueue.add(disService)
        serviceQueue.add(batService)
        serviceQueue.add(hidService)

        updateStatus("Cargando HID...", "#F59E0B")
        addNextServiceOrAdvertise()
    }

    @SuppressLint("MissingPermission")
    private fun addNextServiceOrAdvertise() {
        val server = gattServer ?: return
        val next = serviceQueue.poll()
        if (next != null) {
            server.addService(next)
        } else {
            startAdvertisingNow()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingNow() {
        advertiser = btAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            updateStatus("Sin soporte BLE", "#EF4444")
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
    private fun tryReconnectLastHost(manual: Boolean = false) {
        if (!hasBtPermissions() || gattServer == null) return
        val addr = getLastHostAddress()
        if (addr == null) {
            if (manual) log("Aún no hay Y700 guardada. Vincula desde la Y700 primero.")
            return
        }
        try {
            val bonded = btAdapter?.bondedDevices?.firstOrNull { it.address == addr }
            if (bonded != null) {
                gattServer?.connect(bonded, true)
                log("Buscando reconexión con ${bonded.name ?: addr}...")
            } else if (manual) {
                log("La Y700 no figura vinculada en BLE. Conecta desde la Y700.")
            }
        } catch (e: Exception) {
            if (manual) log("No se pudo iniciar reconexión: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleHid() {
        try {
            if (isCapturing) releaseCapture()
            if (isAdvertising) advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (_: Exception) {}
        serviceQueue.clear()
        isAdvertising = false
        gattServer = null
        connectedHost = null
        isBleBusy = false
        updateStatus("BLE detenido", "#6B7280")
        log("Señal BLE detenida.")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            updateStatus("Listo (esperando Y700)", "#F59E0B")
            log("Servicios HOGP listos. Señal BLE activa.")
            tryReconnectLastHost(manual = false)
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            updateStatus("Error BLE ($errorCode)", "#EF4444")
            log("ERROR al emitir señal BLE (código $errorCode).")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Aviso al cargar servicio ${service.uuid}: $status")
            }
            mainHandler.post { addNextServiceOrAdvertise() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device
                isBleBusy = false
                saveLastHost(device.address)
                val name = device.name ?: "Y700"
                updateStatus("Conectado: $name", "#10B981")
                log("Conectado con $name")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedHost?.address == device.address) connectedHost = null
                isBleBusy = false
                runOnUiThread {
                    if (isCapturing) releaseCapture()
                }
                updateStatus(if (isAdvertising) "Listo (esperando Y700)" else "Desconectado", "#F59E0B")
                log("Desconectado de ${device.name ?: device.address}")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            isBleBusy = false
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            val fullData = when (characteristic.uuid) {
                UUID_PNP_ID -> byteArrayOf(0x02, 0x05, 0x05, 0x01, 0x00, 0x01, 0x00)
                UUID_BAT_LEVEL -> byteArrayOf(100.toByte())
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
                log("Canal HID listo.")
            }
        }
    }

    private fun startCapture() {
        if (connectedHost == null) {
            log("No hay tablet conectada todavía. Toca ↺ o conecta desde la Y700.")
            updateCaptureZoneUi()
            return
        }
        synchronized(mouseLock) {
            accDx = 0f
            accDy = 0f
            accWheel = 0f
            currentButtons = 0
            lastSentButtons = 0
        }
        captureZone.requestFocus()
        captureZone.requestPointerCapture()
    }

    private fun releaseCapture() {
        try {
            captureZone.releasePointerCapture()
        } catch (_: Exception) {}
        synchronized(mouseLock) {
            accDx = 0f
            accDy = 0f
            accWheel = 0f
            currentButtons = 0
            lastSentButtons = 0
        }
        pressedKeys.clear()
        sendKeyReport(0)
        sendBleReport(mouseReportChar, byteArrayOf(0, 0, 0, 0), trackBusy = false)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        isCapturing = hasCapture
        updateCaptureZoneUi()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun sendBleReport(char: BluetoothGattCharacteristic?, data: ByteArray, trackBusy: Boolean = true) {
        val host = connectedHost ?: return
        val server = gattServer ?: return
        val c = char ?: return
        try {
            if (trackBusy) {
                isBleBusy = true
                lastSendTime = System.currentTimeMillis()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(host, c, false, data)
            } else {
                c.value = data
                server.notifyCharacteristicChanged(host, c, false)
            }
        } catch (_: Exception) {
            isBleBusy = false
        }
    }

    private fun handleMouseEvent(event: MotionEvent) {
        var buttonChanged = false
        synchronized(mouseLock) {
            var buttons = 0
            if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or 0x01
            if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or 0x02
            if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or 0x04
            if (buttons != currentButtons) {
                currentButtons = buttons
                buttonChanged = true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                    for (i in 0 until event.historySize) {
                        accDx += event.getHistoricalX(i) * sensitivity
                        accDy += event.getHistoricalY(i) * sensitivity
                    }
                    accDx += event.x * sensitivity
                    accDy += event.y * sensitivity
                }
                MotionEvent.ACTION_SCROLL -> {
                    accWheel += event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                }
            }
        }
        if (buttonChanged) flushMouseReport()
    }

    private fun flushMouseReport() {
        if (!isCapturing || connectedHost == null) return
        val now = System.currentTimeMillis()
        if (isBleBusy && (now - lastSendTime) < 50L) return

        val report: ByteArray
        synchronized(mouseLock) {
            val dx = accDx.toInt().coerceIn(-127, 127)
            val dy = accDy.toInt().coerceIn(-127, 127)
            val wheel = accWheel.toInt().coerceIn(-127, 127)
            val btn = currentButtons
            val btnChanged = btn != lastSentButtons

            if (dx == 0 && dy == 0 && wheel == 0 && !btnChanged) return

            accDx -= dx
            accDy -= dy
            accWheel -= wheel
            lastSentButtons = btn
            report = byteArrayOf(btn.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())
        }
        sendBleReport(mouseReportChar, report, trackBusy = true)
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
        sendBleReport(keyboardReportChar, report, trackBusy = false)
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
