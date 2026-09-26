package com.vm2st.notepad

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class MainActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupThemedScreen(
            findViewById(R.id.rootContainer),
            findViewById<MaterialToolbar>(R.id.topAppBar),
            showBackButton = false
        )

        findViewById<MaterialButton>(R.id.btnHost).setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnClient).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
        findViewById<TextView>(R.id.tvTelegramLink).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, TELEGRAM_URL.toUri()))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TELEGRAM_URL = "https://t.me/vm2_studios"
    }
}