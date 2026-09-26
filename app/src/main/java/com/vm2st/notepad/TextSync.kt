package com.vm2st.notepad

import android.widget.EditText

internal class ClientRevisionTracker {
    var current: Long = 0L
        private set

    fun reset() {
        current = 0L
    }

    fun onLocalChange(): Long {
        current += 1L
        return current
    }

    fun shouldApplySnapshot(acknowledgedSequence: Long): Boolean =
        acknowledgedSequence >= current
}

/**
 * Applies only the changed text range instead of calling setText().
 * This preserves the IME mode (for example the numeric keyboard) and avoids
 * restarting composition while a user is typing.
 */
internal fun applyTextPatch(editText: EditText, newText: String): Boolean {
    val editable = editText.text ?: return false
    val oldText = editable.toString()
    if (oldText == newText) return false

    val commonLimit = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < commonLimit && oldText[prefix] == newText[prefix]) {
        prefix += 1
    }

    var suffix = 0
    while (
        suffix < oldText.length - prefix &&
        suffix < newText.length - prefix &&
        oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]
    ) {
        suffix += 1
    }

    val oldEnd = oldText.length - suffix
    val newEnd = newText.length - suffix
    val selectionStart = editText.selectionStart.coerceAtLeast(0)
    val selectionEnd = editText.selectionEnd.coerceAtLeast(0)
    editable.replace(prefix, oldEnd, newText, prefix, newEnd)

    fun adjustedSelection(position: Int): Int = when {
        position <= prefix -> position
        position >= oldEnd -> position + (newEnd - prefix) - (oldEnd - prefix)
        else -> newEnd
    }.coerceIn(0, editable.length)

    editText.setSelection(
        adjustedSelection(selectionStart),
        adjustedSelection(selectionEnd)
    )
    return true
}