package com.vm2st.notepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

class HostActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private lateinit var tvStatus: TextView
    private lateinit var etContent: EditText
    private lateinit var tvClientCount: TextView
    private var isConnected = false
    private var isUpdating = false
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val REQUEST_BLUETOOTH_PERMISSIONS = 101

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        tvStatus = findViewById(R.id.tvStatus)
        etContent = findViewById(R.id.etContent)
        tvClientCount = findViewById(R.id.tvClientCount)
        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            showHelpDialog()
        }

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
            startBluetoothServer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetoothServer()
            } else {
                Toast.makeText(this, "Необходимы разрешения Bluetooth", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
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

        Thread {
            try {
                serverSocket = bluetoothAdapter!!.listenUsingRfcommWithServiceRecord("NotepadHost", uuid)
                runOnUiThread {
                    tvStatus.text = "Ожидание подключения..."
                    tvClientCount.text = "Подключено: 0"
                }

                clientSocket = serverSocket!!.accept()
                runOnUiThread {
                    tvStatus.text = "Клиент подключён"
                    tvClientCount.text = "Подключено: 1"
                    isConnected = true
                }

                dataInputStream = DataInputStream(clientSocket!!.inputStream)
                dataOutputStream = DataOutputStream(clientSocket!!.outputStream)

                val currentText = etContent.text.toString()
                if (currentText.isNotEmpty()) {
                    dataOutputStream?.writeUTF(currentText)
                    dataOutputStream?.flush()
                }

                listenForMessages()

            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Ошибка сервера: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
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
            } catch (e: IOException) {
                // Соединение разорвано
            } finally {
                runOnUiThread {
                    tvStatus.text = "Отключён"
                    tvClientCount.text = "Подключено: 0"
                    isConnected = false
                }
                try { clientSocket?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Инструкция")
            .setMessage("1. Убедитесь, что Bluetooth включён.\n2. Нажмите ОК, чтобы начать ожидание подключения.\n3. На устройстве-клиенте выберите это устройство (имя: ${bluetoothAdapter?.name ?: "Неизвестно"}).")
            .setPositiveButton("ОК", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
    }
}