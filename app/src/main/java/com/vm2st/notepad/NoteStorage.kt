package com.vm2st.notepad

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal object NoteStorage {
    private const val FILE_NAME = "shared_note.txt"
    private val writer = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun load(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile || file.length() > BluetoothProtocol.MAX_NOTE_BYTES) return ""
        return runCatching {
            file.readText(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun save(context: Context, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BluetoothProtocol.MAX_NOTE_BYTES) return
        val appContext = context.applicationContext
        writer.execute {
            synchronized(lock) {
                val atomicFile = AtomicFile(File(appContext.filesDir, FILE_NAME))
                val stream = runCatching { atomicFile.startWrite() }.getOrNull()
                    ?: return@synchronized
                try {
                    stream.write(bytes)
                    stream.flush()
                    atomicFile.finishWrite(stream)
                } catch (_: Exception) {
                    atomicFile.failWrite(stream)
                }
            }
        }
    }
}