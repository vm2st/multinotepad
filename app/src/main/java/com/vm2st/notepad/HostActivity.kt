package com.vm2st.notepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HostActivity : ThemedActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvClientCount: TextView
    private lateinit var tvAccessCode: TextView
    private lateinit var etContent: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val serverRunning = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var connection: NoteConnection? = null

    @Volatile
    private var latestNote = ""

    private var applyingRemoteText = false
    private val lastClientSequence = AtomicLong(0L)
    private var accessCode = 0

    private val publishNote = Runnable {
        latestNote = etContent.text?.toString().orEmpty()
        NoteStorage.save(this, latestNote)
        connection?.let {
            it.sendSnapshot(lastClientSequence.get(), latestNote)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureBluetoothReady()
        } else {
            showStopped(R.string.bluetooth_required)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val adapter = bluetoothAdapter()
        if (adapter?.isEnabled == true) {
            startServer(adapter)
        } else {
            showStopped(R.string.bluetooth_disabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        setupThemedScreen(
            findViewById(R.id.rootContainer),
            findViewById<MaterialToolbar>(R.id.topAppBar),
            showBackButton = true
        )

        tvStatus = findViewById(R.id.tvStatus)
        tvClientCount = findViewById(R.id.tvClientCount)
        tvAccessCode = findViewById(R.id.tvAccessCode)
        etContent = findViewById(R.id.etContent)

        accessCode = savedInstanceState?.getInt(STATE_ACCESS_CODE)
            ?.takeIf { it in ACCESS_CODE_MIN..ACCESS_CODE_MAX }
            ?: SecureRandom().nextInt(ACCESS_CODE_RANGE) + ACCESS_CODE_MIN
        tvAccessCode.text = String.format(Locale.ROOT, "%06d", accessCode)

        latestNote = NoteStorage.load(this)
        etContent.filters = arrayOf(InputFilter.LengthFilter(BluetoothProtocol.MAX_NOTE_CHARACTERS))
        etContent.setText(latestNote)
        etContent.setSelection(etContent.text?.length ?: 0)
        etContent.addTextChangedListener(noteWatcher)

        findViewById<MaterialButton>(R.id.btnHelp).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.host_help_title)
                .setMessage(R.string.host_help_message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
        tvStatus.setOnClickListener { ensureBluetoothReady() }

        ensureBluetoothReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ACCESS_CODE, accessCode)
        super.onSaveInstanceState(outState)
    }

    private val noteWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (applyingRemoteText) return
            latestNote = s?.toString().orEmpty()
            mainHandler.removeCallbacks(publishNote)
            mainHandler.postDelayed(publishNote, NOTE_DEBOUNCE_MILLIS)
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
            showStopped(R.string.bluetooth_not_supported)
            return
        }
        if (!adapter.isEnabled) {
            runCatching {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }.onFailure {
                showStopped(R.string.bluetooth_disabled)
            }
            return
        }
        startServer(adapter)
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    private fun startServer(adapter: BluetoothAdapter) {
        if (!serverRunning.compareAndSet(false, true) || stopping.get()) return
        showWaiting()
        serverExecutor.execute {
            try {
                hostLoop(adapter)
            } finally {
                serverRunning.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun hostLoop(adapter: BluetoothAdapter) {
        while (!stopping.get()) {
            val disconnected = CountDownLatch(1)
            var activeConnection: NoteConnection? = null
            try {
                runOnUiThread { showWaiting() }
                val localServer = adapter.listenUsingRfcommWithServiceRecord(
                    BluetoothProtocol.SERVICE_NAME,
                    BluetoothProtocol.SERVICE_UUID
                )
                serverSocket = localServer
                val socket = localServer.accept()
                runCatching { localServer.close() }
                serverSocket = null
                if (stopping.get()) {
                    runCatching { socket.close() }
                    break
                }

                activeConnection = NoteConnection(socket)
                if (!activeConnection.authenticateAsHost(accessCode)) {
                    activeConnection.close()
                    runOnUiThread { showAuthFailure() }
                    continue
                }

                connection = activeConnection
                lastClientSequence.set(0L)
                runOnUiThread {
                    showConnected()
                    activeConnection.sendSnapshot(lastClientSequence.get(), latestNote)
                }
                activeConnection.start(
                    onFrame = { frame ->
                        if (frame.type == BluetoothProtocol.TYPE_EDIT) {
                            applyIncomingEdit(activeConnection, frame.revision, frame.text)
                        }
                    },
                    onClosed = { disconnected.countDown() }
                )
                while (!stopping.get() && !disconnected.await(500, TimeUnit.MILLISECONDS)) {
                    // Wait while keeping shutdown responsive.
                }
            } catch (_: IOException) {
                if (!stopping.get()) {
                    runOnUiThread { showStopped(R.string.server_start_failed) }
                    Thread.sleep(RESTART_DELAY_MILLIS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } finally {
                if (connection === activeConnection) connection = null
                activeConnection?.close()
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    private fun applyIncomingEdit(source: NoteConnection, sequence: Long, text: String) {
        runOnUiThread {
            if (stopping.get() || connection !== source) return@runOnUiThread
            if (sequence <= lastClientSequence.get()) {
                source.sendSnapshot(lastClientSequence.get(), latestNote)
                return@runOnUiThread
            }
            mainHandler.removeCallbacks(publishNote)
            applyingRemoteText = true
            applyTextPatch(etContent, text)
            applyingRemoteText = false
            latestNote = text
            NoteStorage.save(this, text)
            lastClientSequence.set(sequence)
            source.sendSnapshot(sequence, text)
        }
    }

    private fun showWaiting() {
        tvStatus.setText(R.string.status_waiting)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
        tvClientCount.setText(R.string.connected_count_zero)
    }

    private fun showConnected() {
        tvStatus.setText(R.string.status_connected)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        tvClientCount.setText(R.string.connected_count_one)
    }

    private fun showAuthFailure() {
        tvStatus.setText(R.string.status_auth_failed)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        tvClientCount.setText(R.string.connected_count_zero)
    }

    private fun showStopped(messageRes: Int) {
        tvStatus.setText(messageRes)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        tvClientCount.setText(R.string.connected_count_zero)
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        stopping.set(true)
        mainHandler.removeCallbacks(publishNote)
        NoteStorage.save(this, etContent.text?.toString().orEmpty())
        connection?.close()
        runCatching { serverSocket?.close() }
        serverExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val STATE_ACCESS_CODE = "access_code"
        const val ACCESS_CODE_MIN = 100_000
        const val ACCESS_CODE_MAX = 999_999
        const val ACCESS_CODE_RANGE = 900_000
        const val NOTE_DEBOUNCE_MILLIS = 300L
        const val RESTART_DELAY_MILLIS = 700L
    }
}