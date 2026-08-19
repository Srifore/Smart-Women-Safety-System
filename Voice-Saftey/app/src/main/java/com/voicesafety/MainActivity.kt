package com.voicesafety

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.UUID
import java.util.Locale
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var deviceStatus: TextView
    private lateinit var eventStatus: TextView
    private lateinit var sosStateText: TextView
    private lateinit var contactTitle: TextView
    private lateinit var contactList: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var sosButton: Button
    private lateinit var voiceButton: Button
    private lateinit var voiceStatus: TextView

    private val contacts = mutableListOf<Contact>()
    private val historyEntries = mutableListOf<HistoryEntry>()
    private val nearbyDevices = mutableListOf<NearbyDevice>()
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var devicePickerDialog: AlertDialog? = null
    private var devicePickerContainer: LinearLayout? = null
    private var devicePickerStatus: TextView? = null
    private var devicePickerProgress: ProgressBar? = null
    private var lastAlertAt = 0L
    private var bleConnected = false
    private var bleServicesReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceMonitoringEnabled = false
    private var voiceListening = false
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingPermissionAction?.invoke()
        } else {
            toast("Required permission was not granted")
        }
        pendingPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("safety", MODE_PRIVATE)
        if (!prefs.getBoolean("logged_in", false)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        deviceStatus = findViewById(R.id.deviceStatus)
        eventStatus = findViewById(R.id.eventStatus)
        sosStateText = findViewById(R.id.sosStateText)
        contactTitle = findViewById(R.id.contactTitle)
        contactList = findViewById(R.id.contactList)
        historyList = findViewById(R.id.historyList)
        connectButton = findViewById(R.id.connectButton)
        sosButton = findViewById(R.id.sosButton)
        voiceButton = findViewById(R.id.voiceButton)
        voiceStatus = findViewById(R.id.voiceStatus)
        connectButton.text = "Auto Connect"
        sosButton.isEnabled = false
        updateSosUi()

        loadContacts()
        renderContacts()
        renderHistory()

        voiceMonitoringEnabled = getSharedPreferences("safety", MODE_PRIVATE)
            .getBoolean("voice_monitor_enabled", false)
        updateVoiceUi()
        if (voiceMonitoringEnabled) {
            startVoiceMonitoring()
        }

        findViewById<Button>(R.id.addContactButton).setOnClickListener { showContactDialog() }
        sosButton.setOnClickListener { sendEmergency("SOS button pressed") }
        connectButton.setOnClickListener { requestPermissionsAndScan() }
        voiceButton.setOnClickListener {
            if (voiceMonitoringEnabled) {
                stopVoiceMonitoring(persistEnabled = false)
            } else {
                requestVoicePermissionAndStart()
            }
        }

        requestPermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (voiceMonitoringEnabled && !voiceListening) {
            startVoiceMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        if (voiceMonitoringEnabled) {
            stopVoiceMonitoring(persistEnabled = true)
        }
    }

    private fun requestPermissionsAndScan() {
        val wanted = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            wanted += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }

        val missing = wanted.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            pendingPermissionAction = ::scanForDevice
            permissionLauncher.launch(wanted.toTypedArray())
        } else {
            scanForDevice()
        }
    }

    private fun requestVoicePermissionAndStart() {
        val missing = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (missing) {
            pendingPermissionAction = { startVoiceMonitoring() }
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            startVoiceMonitoring()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceMonitoring() {
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            pendingPermissionAction = { startVoiceMonitoring() }
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus.text = "Voice recognition is not available on this device"
            toast("Voice recognition unavailable")
            return
        }

        voiceMonitoringEnabled = true
        getSharedPreferences("safety", MODE_PRIVATE)
            .edit()
            .putBoolean("voice_monitor_enabled", true)
            .apply()

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        voiceListening = true
                        runOnUiThread { voiceStatus.text = "Listening for help words..." }
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        voiceListening = false
                    }

                    override fun onError(error: Int) {
                        voiceListening = false
                        runOnUiThread {
                            voiceStatus.text = "Voice monitor restarting..."
                            updateVoiceUi()
                        }
                        if (voiceMonitoringEnabled) {
                            voiceButton.postDelayed({ startVoiceListeningSession() }, 900)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        voiceListening = false
                        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        handleVoiceResults(phrases)
                        if (voiceMonitoringEnabled) {
                            voiceButton.postDelayed({ startVoiceListeningSession() }, 300)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val phrases = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        handleVoicePreview(phrases)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        updateVoiceUi()
        startVoiceListeningSession()
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceListeningSession() {
        if (!voiceMonitoringEnabled || speechRecognizer == null || voiceListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speechRecognizer?.startListening(intent)
            voiceListening = true
            voiceStatus.text = "Listening for help words..."
        } catch (e: Exception) {
            voiceListening = false
            voiceStatus.text = "Voice monitor error: ${e.message}"
        }
        updateVoiceUi()
    }

    private fun handleVoicePreview(phrases: List<String>) {
        if (phrases.isEmpty()) return
        runOnUiThread {
            voiceStatus.text = "Hearing: ${phrases.first()}"
        }
    }

    private fun handleVoiceResults(phrases: List<String>) {
        if (phrases.isEmpty()) return
        val heard = phrases.joinToString(" ").lowercase(Locale.getDefault())
        val normalized = heard.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val hit = listOf("somebody help", "help me", "help", "save me", "please help")
            .any { normalized.contains(it) }

        runOnUiThread {
            voiceStatus.text = if (hit) {
                "Emergency phrase detected"
            } else {
                "Heard: ${phrases.first()}"
            }
        }

        if (hit && System.currentTimeMillis() - lastAlertAt >= 30_000) {
            lastAlertAt = System.currentTimeMillis()
            sendEmergency("Voice help phrase detected", "VOICE")
        }
    }

    private fun stopVoiceMonitoring(persistEnabled: Boolean) {
        voiceListening = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        voiceMonitoringEnabled = persistEnabled
        getSharedPreferences("safety", MODE_PRIVATE)
            .edit()
            .putBoolean("voice_monitor_enabled", persistEnabled)
            .apply()
        updateVoiceUi()
    }

    private fun updateVoiceUi() {
        voiceButton.text = if (voiceMonitoringEnabled) "Stop voice monitoring" else "Start voice monitoring"
        voiceStatus.text = when {
            voiceMonitoringEnabled && voiceListening -> "Listening for help words..."
            voiceMonitoringEnabled && speechRecognizer == null -> "Voice monitor paused"
            voiceMonitoringEnabled -> "Voice monitor enabled"
            else -> "Voice monitor is off"
        }
    }

    private fun updateSosUi() {
        sosButton.text = if (bleConnected) "SOS" else "SOS DISABLED"
        sosButton.isEnabled = bleConnected
        sosButton.alpha = if (bleConnected) 1f else 0.45f
        sosButton.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (bleConnected) R.color.signal_red else R.color.sos_circle
        )
        sosStateText.text = if (bleConnected) "SOS ENABLED" else "SOS DISABLED"
    }

    @SuppressLint("MissingPermission")
    private fun scanForDevice() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Turn on Bluetooth first")
            return
        }

        nearbyDevices.clear()
        scanner = adapter.bluetoothLeScanner
        deviceStatus.text = "Auto-connecting to SafeLink-01..."
        connectButton.isEnabled = false
        scanner?.startScan(scanCallback)

        scanTimeoutRunnable?.let { connectButton.removeCallbacks(it) }
        scanTimeoutRunnable = Runnable {
            stopScan()
            deviceStatus.text = "No SafeLink device found. Keep the ESP32 on and retry."
            connectButton.isEnabled = true
        }
        connectButton.postDelayed(scanTimeoutRunnable!!, 12_000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed device"
            val address = result.device.address ?: return
            val serviceMatches = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(ALERT_SERVICE.toString(), ignoreCase = true)
            } ?: false
            if (!serviceMatches && !name.startsWith("SafeLink", ignoreCase = true)) {
                return
            }
            nearbyDevices.clear()
            nearbyDevices += NearbyDevice(result.device, name, address, result.rssi)
            stopScan()
            deviceStatus.text = "Connecting to $name..."
            connectToDevice(result.device, name)
        }

        override fun onScanFailed(errorCode: Int) {
            deviceStatus.text = "Bluetooth scan failed ($errorCode)"
            connectButton.isEnabled = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, name: String) {
        deviceStatus.text = "Pairing with $name..."
        connectButton.isEnabled = false
        gatt = device.connectGatt(this, false, gattCallback)
        devicePickerDialog?.dismiss()
    }

    private fun stopScan() {
        scanner?.stopScan(scanCallback)
        scanTimeoutRunnable?.let { connectButton.removeCallbacks(it) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    bleConnected = true
                    bleServicesReady = false
                    updateSosUi()
                    deviceStatus.text = "Connected - listening for device alerts"
                    connectButton.isEnabled = true
                    gatt.discoverServices()
                } else {
                    bleConnected = false
                    bleServicesReady = false
                    updateSosUi()
                    deviceStatus.text = "Device disconnected"
                    connectButton.isEnabled = true
                    if (!isFinishing) {
                        connectButton.postDelayed({ requestPermissionsAndScan() }, 3000)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            bleServicesReady = true
            val characteristic = gatt.getService(ALERT_SERVICE)?.getCharacteristic(ALERT_CHARACTERISTIC) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleBleEvent(characteristic.value?.toString(Charsets.UTF_8).orEmpty().trim())
        }
    }

    private fun handleBleEvent(event: String) = runOnUiThread {
        if (event !in setOf("VOICE_HELP", "BUTTON_LONG_PRESS")) return@runOnUiThread
        if (System.currentTimeMillis() - lastAlertAt < 30_000) {
            eventStatus.text = "Duplicate $event ignored for 30 seconds"
            return@runOnUiThread
        }
        lastAlertAt = System.currentTimeMillis()
        sendEmergency(
            if (event == "VOICE_HELP") {
                "Voice help alert detected"
            } else {
                "Device button held for 3 seconds"
            },
            "HARDWARE"
        )
    }

    private fun sendEmergency(reason: String, source: String = "APP") {
        if (!bleConnected) {
            eventStatus.text = "Pair a BLE device before sending SOS."
            toast("BLE device not connected")
            return
        }

        sendEspTriggerCommand()

        if (contacts.size < 3) {
            eventStatus.text = "Add at least 3 contacts before sending an alert."
            toast("Minimum 3 emergency contacts required")
            return
        }

        val requiredPermissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            pendingPermissionAction = { sendEmergency(reason, source) }
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        eventStatus.text = "Getting your current location..."
        getCurrentLocation { location ->
            sendEmergencySms(reason, location, source)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendEspTriggerCommand() {
        if (!bleConnected || !bleServicesReady) return
        val characteristic = gatt?.getService(ALERT_SERVICE)?.getCharacteristic(COMMAND_CHARACTERISTIC) ?: return
        characteristic.value = "TRIGGER_SOS".toByteArray(Charsets.UTF_8)
        gatt?.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(onLocation: (Location?) -> Unit) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val provider = providers.firstOrNull { locationManager.isProviderEnabled(it) }

        if (provider == null) {
            onLocation(providers.firstNotNullOfOrNull { locationManager.getLastKnownLocation(it) })
            return
        }

        var completed = false
        lateinit var listener: LocationListener
        val finish: (Location?) -> Unit = { location ->
            if (!completed) {
                completed = true
                locationManager.removeUpdates(listener)
                onLocation(location)
            }
        }
        listener = LocationListener { location -> finish(location) }
        locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

        eventStatus.postDelayed({
            if (!completed) {
                finish(providers.firstNotNullOfOrNull { locationManager.getLastKnownLocation(it) })
            }
        }, LOCATION_TIMEOUT_MS)
    }

    private fun sendEmergencySms(reason: String, location: Location?, source: String) {
        val locationText = location?.let {
            "\nLocation: https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "\nLocation unavailable. Please contact me immediately."
        val message = "EMERGENCY ALERT: $reason. Please contact me immediately. Sent from Voice Safety.$locationText"
        try {
            val sms = SmsManager.getDefault()
            contacts.forEach { contact ->
                val parts = sms.divideMessage(message)
                sms.sendMultipartTextMessage(contact.phone, null, ArrayList(parts), null, null)
            }
            eventStatus.text = "Emergency SMS with location sent to ${contacts.size} contacts - $reason"
            addHistoryEntry(source, "SMS_SENT", location?.let {
                "https://maps.google.com/?q=${it.latitude},${it.longitude}"
            } ?: "Location unavailable")
        } catch (e: Exception) {
            eventStatus.text = "Could not send SMS: ${e.message}"
            addHistoryEntry(source, "SMS_FAILED", e.message ?: "Unknown error")
        }
    }

    private fun showContactDialog(existing: Contact? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 18, 56, 0)
        }
        val name = EditText(this).apply {
            hint = "Name"
            setText(existing?.name ?: "")
        }
        val phone = EditText(this).apply {
            hint = "Phone number with country code"
            inputType = 3
            setText(existing?.phone ?: "")
        }

        box.addView(name)
        box.addView(phone)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Emergency contact" else "Edit contact")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val value = phone.text.toString().replace(" ", "")
                if (name.text.isBlank() || value.length < 8) {
                    toast("Enter a name and valid phone number")
                    return@setPositiveButton
                }
                if (existing == null) {
                    contacts += Contact(name.text.toString().trim(), value)
                } else {
                    existing.name = name.text.toString().trim()
                    existing.phone = value
                }
                saveContacts()
                renderContacts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderContacts() {
        contactTitle.text = "EMERGENCY CONTACTS (${contacts.size}/6)"
        contactList.removeAllViews()

        if (contacts.isEmpty()) {
            contactList.addView(TextView(this).apply {
                text = "No emergency contacts yet."
                setTextColor(getColor(R.color.dark_muted))
                textSize = 15f
            })
        } else {
            contacts.forEachIndexed { index, contact ->
                contactList.addView(
                    MaterialCardView(this).apply {
                        setCardBackgroundColor(getColor(R.color.dark))
                        radius = 22f
                        strokeColor = getColor(R.color.dark_stroke)
                        strokeWidth = 1
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = 12
                        }

                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(18, 18, 18, 18)

                            val contactText = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            contactText.addView(TextView(this@MainActivity).apply {
                                text = contact.name
                                setTextColor(getColor(R.color.dark_text))
                                textSize = 18f
                                setPadding(0, 0, 0, 4)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            })
                            contactText.addView(TextView(this@MainActivity).apply {
                                text = contact.phone
                                setTextColor(getColor(R.color.dark_muted))
                                textSize = 15f
                            })

                            addView(contactText)

                            addView(TextView(this@MainActivity).apply {
                                text = "Priority ${index + 1}"
                                setTextColor(getColor(R.color.signal_teal))
                                textSize = 13f
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            })

                            setOnClickListener { showContactDialog(contact) }
                            setOnLongClickListener {
                                contacts.remove(contact)
                                saveContacts()
                                renderContacts()
                                true
                            }
                        })
                    }
                )
            }
        }

        findViewById<Button>(R.id.addContactButton).isEnabled = contacts.size < 6
    }

    private fun addHistoryEntry(source: String, status: String, detail: String) {
        historyEntries.add(0, HistoryEntry(source, status, detail, timestampNow()))
        while (historyEntries.size > 5) {
            historyEntries.removeLast()
        }
        renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        if (historyEntries.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = "No SOS events yet."
                setTextColor(getColor(R.color.dark_muted))
                textSize = 15f
            })
            return
        }

        historyEntries.forEach { entry ->
            historyList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 18)

                    addView(TextView(this@MainActivity).apply {
                        text = "${entry.source} â€¢ ${entry.status}"
                        setTextColor(getColor(R.color.dark_text))
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = entry.detail
                        setTextColor(getColor(R.color.dark_muted))
                        textSize = 14f
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = entry.time
                        setTextColor(getColor(R.color.signal_teal))
                        textSize = 12f
                    })
                }
            )
        }
    }

    private fun loadContacts() {
        val raw = getSharedPreferences("safety", MODE_PRIVATE).getString("contacts", "[]") ?: "[]"
        val list = JSONArray(raw)
        repeat(list.length()) { index ->
            list.getJSONObject(index).let {
                contacts += Contact(it.getString("name"), it.getString("phone"))
            }
        }
    }

    private fun saveContacts() {
        val list = JSONArray()
        contacts.forEach {
            list.put(JSONObject().put("name", it.name).put("phone", it.phone))
        }
        getSharedPreferences("safety", MODE_PRIVATE)
            .edit()
            .putString("contacts", list.toString())
            .apply()
    }

    override fun onDestroy() {
        stopScan()
        devicePickerDialog?.dismiss()
        gatt?.close()
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun timestampNow(): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

    data class Contact(var name: String, var phone: String)
    data class HistoryEntry(val source: String, val status: String, val detail: String, val time: String)
    data class NearbyDevice(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int)

    companion object {
        val ALERT_SERVICE: UUID = UUID.fromString("d7c7f030-6b59-4bf0-8a08-2f20c2e50000")
        val ALERT_CHARACTERISTIC: UUID = UUID.fromString("d7c7f030-6b59-4bf0-8a08-2f20c2e50004")
        val COMMAND_CHARACTERISTIC: UUID = UUID.fromString("d7c7f030-6b59-4bf0-8a08-2f20c2e50007")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val LOCATION_TIMEOUT_MS = 10_000L
    }
}

