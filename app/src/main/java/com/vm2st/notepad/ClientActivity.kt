package com.vm2st.notepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ClientActivity : ThemedActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var noteInputLayout: TextInputLayout
    private lateinit var etContent: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor = Executors.newSingleThreadExecutor()
    private val connecting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    @Volatile
    private var connection: NoteConnection? = null

    private var applyingRemoteText = false
    private var lastDevice: BluetoothDevice? = null
    private var lastAccessCode: Int? = null
    private val revisionTracker = ClientRevisionTracker()

    private val sendNote = Runnable {
        val text = etContent.text?.toString().orEmpty()
        NoteStorage.save(this, text)
        connection?.sendEdit(revisionTracker.current, text)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureBluetoothReady()
        } else {
            showDisconnected(R.string.bluetooth_required)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter()?.isEnabled == true) {
            showDeviceSelectionDialog()
        } else {
            showDisconnected(R.string.bluetooth_disabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        setupThemedScreen(
            findViewById(R.id.rootContainer),
            findViewById<MaterialToolbar>(R.id.topAppBar),
            showBackButton = true
        )

        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        noteInputLayout = findViewById(R.id.noteInputLayout)
        etContent = findViewById(R.id.etContent)

        etContent.filters = arrayOf(InputFilter.LengthFilter(BluetoothProtocol.MAX_NOTE_CHARACTERS))
        etContent.setText(NoteStorage.load(this))
        etContent.setSelection(etContent.text?.length ?: 0)
        etContent.addTextChangedListener(noteWatcher)

        btnConnect.setOnClickListener {
            val device = lastDevice
            val code = lastAccessCode
            if (device != null && code != null && connection == null) {
                connectToDevice(device, code)
            } else {
                ensureBluetoothReady()
            }
        }
        showDisconnected(R.string.status_disconnected)
        ensureBluetoothReady()
    }

    private val noteWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (applyingRemoteText || connection == null) return
            revisionTracker.onLocalChange()
            mainHandler.removeCallbacks(sendNote)
            mainHandler.postDelayed(sendNote, NOTE_DEBOUNCE_MILLIS)
        }
    }

    private fun ensureBluetoothReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            showDisconnected(R.string.bluetooth_not_supported)
            return
        }
        if (!adapter.isEnabled) {
            runCatching {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }.onFailure {
                showDisconnected(R.string.bluetooth_disabled)
            }
            return
        }
        showDeviceSelectionDialog()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val adapter = bluetoothAdapter() ?: return
        val devices = adapter.bondedDevices
            .sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.address }))
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_host_device)
                .setMessage(R.string.paired_devices_empty)
                .setPositiveButton(R.string.open_bluetooth_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val labels = devices.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() }
                ?: getString(R.string.unknown_device)
            "$name · ${device.address.takeLast(5)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_host_device)
            .setItems(labels) { _, which ->
                showAccessCodeDialog(devices[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAccessCodeDialog(device: BluetoothDevice) {
        val input = EditText(this).apply {
            hint = getString(R.string.access_code_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(ACCESS_CODE_LENGTH))
            textSize = 22f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val container = FrameLayout(this).apply {
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.access_code_dialog_title)
            .setMessage(R.string.access_code_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.connect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString().orEmpty()
                val code = value.toIntOrNull()
                if (value.length == ACCESS_CODE_LENGTH && code != null) {
                    dialog.dismiss()
                    lastDevice = device
                    lastAccessCode = code
                    connectToDevice(device, code)
                } else {
                    input.error = getString(R.string.access_code_dialog_message)
                }
            }
        }
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, accessCode: Int) {
        if (!connecting.compareAndSet(false, true) || stopping.get()) return
        connection?.close()
        connection = null
        showConnecting()

        connectExecutor.execute {
            var localConnection: NoteConnection? = null
            try {
                val socket = device.createRfcommSocketToServiceRecord(
                    BluetoothProtocol.SERVICE_UUID
                )
                pendingSocket = socket
                socket.connect()
                if (stopping.get()) {
                    socket.close()
                    return@execute
                }

                localConnection = NoteConnection(socket)
                if (!localConnection.authenticateAsClient(accessCode)) {
                    localConnection.close()
                    runOnUiThread { showDisconnected(R.string.wrong_code) }
                    return@execute
                }

                connection = localConnection
                runOnUiThread {
                    revisionTracker.reset()
                    showConnected()
                }
                localConnection.start(
                    onFrame = { frame ->
                        if (frame.type == BluetoothProtocol.TYPE_SNAPSHOT) {
                            applySnapshot(localConnection, frame.revision, frame.text)
                        }
                    },
                    onClosed = {
                        if (connection === localConnection) {
                            connection = null
                            runOnUiThread {
                                if (!stopping.get()) {
                                    showDisconnected(R.string.connection_closed)
                                }
                            }
                        }
                    }
                )
            } catch (_: IOException) {
                localConnection?.close()
                runCatching { pendingSocket?.close() }
                runOnUiThread {
                    if (!stopping.get()) {
                        showDisconnected(R.string.connection_failed)
                    }
                }
            } catch (_: SecurityException) {
                localConnection?.close()
                runOnUiThread { showDisconnected(R.string.bluetooth_required) }
            } finally {
                pendingSocket = null
                connecting.set(false)
            }
        }
    }

    private fun applySnapshot(
        source: NoteConnection,
        acknowledgedSequence: Long,
        text: String
    ) {
        runOnUiThread {
            if (stopping.get() || connection !== source) return@runOnUiThread
            if (!revisionTracker.shouldApplySnapshot(acknowledgedSequence)) {
                return@runOnUiThread
            }
            mainHandler.removeCallbacks(sendNote)
            applyingRemoteText = true
            applyTextPatch(etContent, text)
            applyingRemoteText = false
            NoteStorage.save(this, text)
        }
    }

    private fun showConnecting() {
        tvStatus.setText(R.string.status_connecting)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
        btnConnect.isEnabled = false
        etContent.isEnabled = false
        noteInputLayout.hint = getString(R.string.note_read_only_hint)
    }

    private fun showConnected() {
        tvStatus.setText(R.string.status_connected)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        btnConnect.visibility = View.GONE
        btnConnect.isEnabled = true
        etContent.isEnabled = true
        noteInputLayout.hint = getString(R.string.note_hint)
    }

    private fun showDisconnected(messageRes: Int) {
        tvStatus.setText(messageRes)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        btnConnect.visibility = View.VISIBLE
        btnConnect.isEnabled = true
        btnConnect.setText(
            if (lastDevice == null) R.string.select_device else R.string.reconnect
        )
        etContent.isEnabled = false
        noteInputLayout.hint = getString(R.string.note_read_only_hint)
        if (messageRes != R.string.status_disconnected) {
            Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        mainHandler.removeCallbacks(sendNote)
        NoteStorage.save(this, etContent.text?.toString().orEmpty())
        connection?.close()
        runCatching { pendingSocket?.close() }
        connectExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val ACCESS_CODE_LENGTH = 6
        const val NOTE_DEBOUNCE_MILLIS = 300L
    }
}