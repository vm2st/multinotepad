package com.vm2st.notepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

class ClientActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnRetry: ImageButton
    private lateinit var layoutContent: LinearLayout
    private lateinit var etContent: EditText
    private lateinit var layoutConnectionStatus: LinearLayout
    private lateinit var tvConnectionState: TextView
    private lateinit var btnReconnect: ImageButton

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var clientSocket: BluetoothSocket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null
    private var isConnected = false
    private var isUpdating = false
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val REQUEST_BLUETOOTH_PERMISSIONS = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        tvStatus = findViewById(R.id.tvStatus)
        btnRetry = findViewById(R.id.btnRetry)
        layoutContent = findViewById(R.id.layoutContent)
        etContent = findViewById(R.id.etContent)
        layoutConnectionStatus = findViewById(R.id.layoutConnectionStatus)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        btnReconnect = findViewById(R.id.btnReconnect)

        layoutContent.visibility = View.GONE
        layoutConnectionStatus.visibility = View.GONE

        etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdating && isConnected) {
                    val newText = s.toString()
                    try {
                        dataOutputStream?.writeUTF(newText)
                        dataOutputStream?.flush()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        })

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        } else {
            showDeviceSelectionDialog()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showDeviceSelectionDialog()
            } else {
                Toast.makeText(this, "Необходимы разрешения Bluetooth", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth не поддерживается", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val pairedDevices = bluetoothAdapter!!.bondedDevices
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых устройств. Сначала выполните сопряжение в настройках Bluetooth.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val deviceNames = pairedDevices.map { it.name }
        val devicesArray = deviceNames.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите устройство хоста")
            .setItems(devicesArray) { _, which ->
                val selectedDevice = pairedDevices.elementAt(which)
                connectToDevice(selectedDevice)
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Подключение..."
        btnRetry.visibility = View.GONE
        layoutContent.visibility = View.GONE
        layoutConnectionStatus.visibility = View.GONE

        Thread {
            try {
                clientSocket = device.createRfcommSocketToServiceRecord(uuid)
                clientSocket?.connect()

                dataInputStream = DataInputStream(clientSocket!!.inputStream)
                dataOutputStream = DataOutputStream(clientSocket!!.outputStream)
                isConnected = true

                runOnUiThread { onConnected() }

                listenForMessages()

            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    onConnectionFailed("Ошибка подключения")
                }
            }
        }.start()
    }

    private fun onConnectionFailed(reason: String) {
        tvStatus.text = reason
        btnRetry.visibility = View.VISIBLE
        btnRetry.setOnClickListener { showDeviceSelectionDialog() }
        layoutContent.visibility = View.GONE
        layoutConnectionStatus.visibility = View.GONE
    }

    private fun onConnected() {
        tvStatus.visibility = View.GONE
        btnRetry.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE
        layoutConnectionStatus.visibility = View.VISIBLE
        tvConnectionState.text = "ПОДКЛЮЧЕН"
        tvConnectionState.setTextColor(android.graphics.Color.GREEN)
        btnReconnect.visibility = View.GONE
    }

    private fun listenForMessages() {
        Thread {
            try {
                while (true) {
                    val message = dataInputStream?.readUTF() ?: break
                    runOnUiThread {
                        val selectionStart = etContent.selectionStart
                        val selectionEnd = etContent.selectionEnd

                        isUpdating = true
                        etContent.setText(message)
                        isUpdating = false

                        val length = etContent.text.length
                        if (selectionStart <= length && selectionEnd <= length) {
                            etContent.setSelection(selectionStart, selectionEnd)
                        } else {
                            etContent.setSelection(length)
                        }
                    }
                }
                onDisconnected()
            } catch (e: IOException) {
                onDisconnected()
            }
        }.start()
    }

    private fun onDisconnected() {
        isConnected = false
        runOnUiThread {
            tvConnectionState.text = "ОТКЛЮЧЁН"
            tvConnectionState.setTextColor(android.graphics.Color.RED)
            btnReconnect.visibility = View.VISIBLE
            btnReconnect.setOnClickListener { reconnect() }
        }
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null
        dataInputStream = null
        dataOutputStream = null
    }

    private fun reconnect() {
        onDisconnected()
        showDeviceSelectionDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
    }
}