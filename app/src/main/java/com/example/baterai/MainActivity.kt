package com.example.baterai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var etHigh: EditText
    private lateinit var etLow: EditText
    private lateinit var etTemp: EditText
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var etWaPhone: EditText
    private lateinit var etWaKey: EditText
    private lateinit var swTg: Switch
    private lateinit var swWa: Switch

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 28, 0, 4) }
        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            else inputType = InputType.TYPE_CLASS_TEXT
        }

        root.addView(TextView(this).apply { text = "Pemantau Baterai"; textSize = 24f })

        root.addView(label("Alarm saat baterai PENUH (%) — aktif ketika sedang dicas"))
        etHigh = field("mis. 80", prefs.getInt("high", 80).toString(), true); root.addView(etHigh)

        root.addView(label("Alarm saat baterai RENDAH (%) — aktif ketika tidak dicas"))
        etLow = field("mis. 20", prefs.getInt("low", 20).toString(), true); root.addView(etLow)

        root.addView(label("Alarm saat suhu TERLALU PANAS (°C), 0 = nonaktif"))
        etTemp = field("mis. 42", prefs.getInt("temp", 0).toString(), true); root.addView(etTemp)

        root.addView(label("— Telegram —"))
        swTg = Switch(this).apply { text = "Kirim ke Telegram"; isChecked = prefs.getBoolean("tgOn", false) }
        root.addView(swTg)
        etTgToken = field("Bot token (dari @BotFather)", prefs.getString("tgToken", "") ?: ""); root.addView(etTgToken)
        etTgChat = field("Chat ID", prefs.getString("tgChat", "") ?: ""); root.addView(etTgChat)

        root.addView(label("— WhatsApp (Fonnte) —"))
        swWa = Switch(this).apply { text = "Kirim ke WhatsApp"; isChecked = prefs.getBoolean("waOn", false) }
        root.addView(swWa)
        etWaPhone = field("Nomor tujuan, mis. 08123456789 (pisah koma jika banyak)", prefs.getString("waPhone", "") ?: ""); root.addView(etWaPhone)
        etWaKey = field("Token device Fonnte", prefs.getString("waKey", "") ?: ""); root.addView(etWaKey)

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        root.addView(View(this).apply { minimumHeight = 24 })
        root.addView(button("SIMPAN & MULAI PEMANTAUAN") {
            save()
            startForegroundService(Intent(this, BatteryService::class.java))
            prefs.edit().putBoolean("running", true).apply()
            toast("Pemantauan dimulai")
        })
        root.addView(button("HENTIKAN PEMANTAUAN") {
            prefs.edit().putBoolean("running", false).apply()
            stopService(Intent(this, BatteryService::class.java))
            toast("Pemantauan dihentikan")
        })
        root.addView(button("MATIKAN ALARM") {
            startService(Intent(this, BatteryService::class.java).setAction(BatteryService.ACTION_STOP_ALARM))
        })
        root.addView(button("TES KIRIM PESAN (WA/Telegram)") {
            save()
            startForegroundService(Intent(this, BatteryService::class.java).setAction(BatteryService.ACTION_TEST))
            toast("Mengirim pesan tes...")
        })
    }

    private fun save() {
        prefs.edit()
            .putInt("high", etHigh.text.toString().toIntOrNull() ?: 80)
            .putInt("low", etLow.text.toString().toIntOrNull() ?: 20)
            .putInt("temp", etTemp.text.toString().toIntOrNull() ?: 0)
            .putBoolean("tgOn", swTg.isChecked)
            .putString("tgToken", etTgToken.text.toString().trim())
            .putString("tgChat", etTgChat.text.toString().trim())
            .putBoolean("waOn", swWa.isChecked)
            .putString("waPhone", etWaPhone.text.toString().trim())
            .putString("waKey", etWaKey.text.toString().trim())
            .apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
