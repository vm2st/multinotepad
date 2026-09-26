package com.vm2st.notepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class BluetoothProtocolTest {
    @Test
    fun frameRoundTripPreservesRussianText() {
        val expected = BluetoothProtocol.Frame(
            type = BluetoothProtocol.TYPE_SNAPSHOT,
            revision = 42L,
            text = "Общий текст ✨\nВторая строка"
        )
        val bytes = ByteArrayOutputStream()
        BluetoothProtocol.writeFrame(DataOutputStream(bytes), expected)

        val actual = BluetoothProtocol.readFrame(
            DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        )

        assertEquals(expected, actual)
    }

    @Test
    fun invalidPayloadLengthIsRejected() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeByte(BluetoothProtocol.TYPE_EDIT)
            writeLong(0L)
            writeInt(BluetoothProtocol.MAX_NOTE_BYTES + 1)
        }

        assertThrows(IOException::class.java) {
            BluetoothProtocol.readFrame(
                DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
            )
        }
    }

    @Test
    fun unknownThemeFallsBackToLight() {
        assertEquals(AppTheme.LIGHT, AppTheme.from("unknown"))
        assertEquals(AppTheme.DARK, AppTheme.from("dark"))
        assertEquals(AppTheme.BURGUNDY, AppTheme.from("burgundy"))
    }

    @Test
    fun staleSnapshotDoesNotOverridePendingLocalEdit() {
        val tracker = ClientRevisionTracker()
        tracker.onLocalChange()
        tracker.onLocalChange()

        assertEquals(false, tracker.shouldApplySnapshot(1L))
        assertEquals(true, tracker.shouldApplySnapshot(2L))
    }
}