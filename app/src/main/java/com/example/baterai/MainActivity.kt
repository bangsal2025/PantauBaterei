package com.example.baterai

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0E141B")
    private val CARD = Color.parseColor("#16202A")
    private val LINE = Color.parseColor("#26343F")
    private val TEXT = Color.parseColor("#EAF0F6")
    private val SUB = Color.parseColor("#8FA3B5")
    private val GOLD = Color.parseColor("#E8C872")
    private val GREEN = Color.parseColor("#2ECC9A")
    private val RED = Color.parseColor("#E5575A")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private lateinit var tvPct: TextView
    private lateinit var tvState: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnToggle: Button
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var etWaPhone: EditText
    private lateinit var etWaKey: EditText
    private lateinit var swTg: Switch
    private lateinit var swWa: Switch

    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateStatus(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            fitsSystemWindows = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, dp(20))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.parseColor("#12303A"), 20, LINE)
        }, LinearLayout.LayoutParams(dp(60), dp(66)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        titles.addView(text("Pemantau Baterai", 22f, TEXT, true))
        titles.addView(text("Alarm  •  WhatsApp  •  Telegram", 13f, SUB))
        header.addView(titles)
        root.addView(header)

        // ---- Status ----
        val status = card(root)
        sectionTitle(status, "Kondisi saat ini")
        tvPct = text("--%", 44f, TEXT, true)
        status.addView(tvPct)
        tvState = text("Membaca baterai...", 14f, SUB)
        status.addView(tvState)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
        }
        status.addView(bar, LinearLayout.LayoutParams(MATCH, dp(8)).apply { topMargin = dp(14) })

        // ---- Batas alarm ----
        val limits = card(root)
        sectionTitle(limits, "Batas alarm")
        slider(limits, "Penuh (saat dicas)", 50, 100, prefs.getInt("high", 80), { "$it%" }) {
            prefs.edit().putInt("high", it).apply()
        }
        slider(limits, "Rendah (saat tidak dicas)", 5, 50, prefs.getInt("low", 20), { "$it%" }) {
            prefs.edit().putInt("low", it).apply()
        }
        val savedTemp = prefs.getInt("temp", 0)
        slider(limits, "Suhu panas", 34, 60, if (savedTemp == 0) 34 else savedTemp,
            { if (it <= 34) "Nonaktif" else "$it°C" }) {
            prefs.edit().putInt("temp", if (it <= 34) 0 else it).apply()
        }
        limits.addView(text(
            "Alarm berbunyi sampai dimatikan, atau berhenti sendiri saat adaptor dicabut (penuh) / dicolok (habis).",
            12f, SUB).apply { setPadding(0, dp(6), 0, 0) })

        // ---- Telegram ----
        val tg = card(root)
        sectionTitle(tg, "Telegram")
        swTg = switchView("Kirim ke Telegram", prefs.getBoolean("tgOn", false)); tg.addView(swTg)
        etTgToken = input("Bot token (dari @BotFather)", prefs.getString("tgToken", "") ?: ""); tg.addView(etTgToken, fieldLp())
        etTgChat = input("Chat ID", prefs.getString("tgChat", "") ?: ""); tg.addView(etTgChat, fieldLp())

        // ---- WhatsApp ----
        val wa = card(root)
        sectionTitle(wa, "WhatsApp (Fonnte)")
        swWa = switchView("Kirim ke WhatsApp", prefs.getBoolean("waOn", false)); wa.addView(swWa)
        etWaKey = input("Token device Fonnte", prefs.getString("waKey", "") ?: ""); wa.addView(etWaKey, fieldLp())
        etWaPhone = input("Nomor tujuan, mis. 08123456789", prefs.getString("waPhone", "") ?: ""); wa.addView(etWaPhone, fieldLp())

        // ---- Tombol ----
        btnToggle = button("", true, GOLD) { toggleMonitoring() }
        root.addView(btnToggle)
        root.addView(button("Matikan alarm", false, RED) {
            startService(Intent(this, BatteryService::class.java).setAction(BatteryService.ACTION_STOP_ALARM))
        })
        root.addView(button("Tes kirim pesan", false, GREEN) {
            save()
            startForegroundService(Intent(this, BatteryService::class.java).setAction(BatteryService.ACTION_TEST))
            toast("Mengirim pesan tes...")
        })
        refreshToggle()
    }

    override fun onStart() {
        super.onStart()
        updateStatus(registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        refreshToggle()
    }

    override fun onStop() {
        try { unregisterReceiver(battReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    // ---------- Logika ----------
    private fun toggleMonitoring() {
        if (prefs.getBoolean("running", false)) {
            prefs.edit().putBoolean("running", false).apply()
            stopService(Intent(this, BatteryService::class.java))
            toast("Pemantauan dihentikan")
        } else {
            save()
            prefs.edit().putBoolean("running", true).apply()
            startForegroundService(Intent(this, BatteryService::class.java))
            toast("Pemantauan dimulai")
        }
        refreshToggle()
    }

    private fun refreshToggle() {
        val running = prefs.getBoolean("running", false)
        btnToggle.text = if (running) "Hentikan pemantauan" else "Mulai pemantauan"
        btnToggle.background = if (running) rounded(Color.TRANSPARENT, 14, GOLD) else rounded(GOLD, 14)
        btnToggle.setTextColor(if (running) GOLD else BG)
    }

    private fun updateStatus(i: Intent?) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        tvPct.text = "$pct%"
        tvState.text = "${if (charging) "Sedang mengisi" else "Tidak mengisi"}  •  ${"%.1f".format(temp)}°C"
        bar.progress = pct
        val color = when {
            charging -> GREEN
            pct <= prefs.getInt("low", 20) -> RED
            else -> GOLD
        }
        bar.progressTintList = ColorStateList.valueOf(color)
        tvPct.setTextColor(color)
    }

    private fun save() {
        prefs.edit()
            .putBoolean("tgOn", swTg.isChecked)
            .putString("tgToken", etTgToken.text.toString().trim())
            .putString("tgChat", etTgChat.text.toString().trim())
            .putBoolean("waOn", swWa.isChecked)
            .putString("waPhone", etWaPhone.text.toString().trim())
            .putString("waKey", etWaKey.text.toString().trim())
            .apply()
    }

    // ---------- Helper UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20, LINE)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        return c
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionTitle(parent: LinearLayout, t: String) {
        parent.addView(text(t.uppercase(), 12f, GOLD, true).apply {
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun fieldLp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }

    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(Color.parseColor("#5F7284"))
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        background = rounded(Color.parseColor("#0E161E"), 12, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun switchView(label: String, checked: Boolean) = Switch(this).apply {
        text = label
        setTextColor(TEXT)
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(GREEN, SUB))
        trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#2A6B58"), Color.parseColor("#2B3A4A")))
    }

    private fun slider(
        parent: LinearLayout, title: String, min: Int, max: Int, current: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val tvTitle = text(title, 14f, TEXT)
        val tvVal = text(fmt(current), 14f, GOLD, true).apply { gravity = Gravity.END }
        row.addView(tvTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(tvVal, LinearLayout.LayoutParams(WRAP, WRAP))
        parent.addView(row)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = current.coerceIn(min, max) - min
            progressTintList = ColorStateList.valueOf(GOLD)
            thumbTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tvVal.text = fmt(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        parent.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun button(t: String, filled: Boolean, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) BG else color)
        background = if (filled) rounded(color, 14) else rounded(Color.TRANSPARENT, 14, color)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
