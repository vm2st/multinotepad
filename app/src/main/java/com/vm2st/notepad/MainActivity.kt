package com.vm2st.notepad

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.net.Uri

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_host).setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        findViewById<Button>(R.id.btn_client).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        // Обработка ссылки на Telegram
        val telegramLink = findViewById<TextView>(R.id.tvTelegramLink)
        telegramLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/vm2_studios"))
            startActivity(intent)
        }
    }}