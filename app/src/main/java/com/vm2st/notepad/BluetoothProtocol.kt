package com.vm2st.notepad

import android.bluetooth.BluetoothSocket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object BluetoothProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("c5a15170-4f6a-4a3c-a9b2-d4c75ae6e361")
    const val SERVICE_NAME = "MultiNotepad"
    const val MAX_NOTE_BYTES = 512 * 1024
    const val MAX_NOTE_CHARACTERS = 100_000

    const val TYPE_EDIT = 1
    const val TYPE_SNAPSHOT = 2

    private const val MAGIC = 0x4D4E5044 // MNPD
    private const val VERSION = 2
    private const val AUTH_OK = 1
    private const val AUTH_FAILED = 0

    data class Frame(val type: Int, val revision: Long, val text: String)

    fun authenticateClient(
        input: DataInputStream,
        output: DataOutputStream,
        accessCode: Int
    ): Boolean {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeInt(accessCode)
        output.flush()
        return input.readInt() == AUTH_OK
    }

    fun authenticateHost(
        input: DataInputStream,
        output: DataOutputStream,
        expectedAccessCode: Int
    ): Boolean {
        val accepted = input.readInt() == MAGIC &&
            input.readInt() == VERSION &&
            input.readInt() == expectedAccessCode
        output.writeInt(if (accepted) AUTH_OK else AUTH_FAILED)
        output.flush()
        return accepted
    }

    fun writeFrame(output: DataOutputStream, frame: Frame) {
        val payload = frame.text.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_NOTE_BYTES) {
            throw IOException("Note exceeds protocol limit")
        }
        output.writeByte(frame.type)
        output.writeLong(frame.revision)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        if (type != TYPE_EDIT && type != TYPE_SNAPSHOT) {
            throw IOException("Unknown frame type")
        }
        val revision = input.readLong()
        val length = input.readInt()
        if (length !in 0..MAX_NOTE_BYTES) {
            throw IOException("Invalid frame length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, revision, String(payload, StandardCharsets.UTF_8))
    }
}

internal class NoteConnection(private val socket: BluetoothSocket) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
    private val readerExecutor = Executors.newSingleThreadExecutor()
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val pendingFrame = AtomicReference<BluetoothProtocol.Frame?>(null)
    private val writerScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeNotified = AtomicBoolean(false)

    @Volatile
    private var onClosed: (() -> Unit)? = null

    fun authenticateAsClient(accessCode: Int): Boolean {
        return withAuthenticationTimeout {
            BluetoothProtocol.authenticateClient(input, output, accessCode)
        }
    }

    fun authenticateAsHost(accessCode: Int): Boolean {
        return withAuthenticationTimeout {
            BluetoothProtocol.authenticateHost(input, output, accessCode)
        }
    }

    private fun withAuthenticationTimeout(block: () -> Boolean): Boolean {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeout = timeoutExecutor.schedule(
            { runCatching { socket.close() } },
            AUTH_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS
        )
        return try {
            block()
        } finally {
            timeout.cancel(false)
            timeoutExecutor.shutdownNow()
        }
    }

    fun start(onFrame: (BluetoothProtocol.Frame) -> Unit, onClosed: () -> Unit) {
        this.onClosed = onClosed
        readerExecutor.execute {
            try {
                while (!closed.get()) {
                    onFrame(BluetoothProtocol.readFrame(input))
                }
            } catch (_: IOException) {
                terminate()
            } catch (_: RuntimeException) {
                terminate()
            }
        }
    }

    fun sendEdit(sequence: Long, text: String) {
        enqueue(BluetoothProtocol.Frame(BluetoothProtocol.TYPE_EDIT, sequence, text))
    }

    fun sendSnapshot(revision: Long, text: String) {
        enqueue(BluetoothProtocol.Frame(BluetoothProtocol.TYPE_SNAPSHOT, revision, text))
    }

    private fun enqueue(frame: BluetoothProtocol.Frame) {
        if (closed.get()) return
        pendingFrame.set(frame)
        scheduleWriter()
    }

    private fun scheduleWriter() {
        if (!writerScheduled.compareAndSet(false, true)) return
        try {
            writerExecutor.execute {
                try {
                    while (!closed.get()) {
                        val frame = pendingFrame.getAndSet(null) ?: break
                        BluetoothProtocol.writeFrame(output, frame)
                    }
                } catch (_: IOException) {
                    terminate()
                } catch (_: RuntimeException) {
                    terminate()
                } finally {
                    writerScheduled.set(false)
                    if (!closed.get() && pendingFrame.get() != null) {
                        scheduleWriter()
                    }
                }
            }
        } catch (_: RuntimeException) {
            terminate()
        }
    }

    fun close() {
        terminate()
    }

    private fun terminate() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            readerExecutor.shutdownNow()
            writerExecutor.shutdownNow()
        }
        if (closeNotified.compareAndSet(false, true)) {
            onClosed?.invoke()
        }
    }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 15_000L
    }
}