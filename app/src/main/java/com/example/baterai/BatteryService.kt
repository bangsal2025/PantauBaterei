package com.example.baterai

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BatteryService : Service() {

    companion object {
        const val ACTION_STOP_ALARM = "com.example.baterai.STOP_ALARM"
        const val ACTION_TEST = "com.example.baterai.TEST"
        const val CH_MONITOR = "monitor"
        const val CH_ALARM = "alarm"
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var highTriggered = false
    private var lowTriggered = false
    private var tempTriggered = false
    private var alarmActive = false
    private var lastLevel = -1
    private var lastCharging: Boolean? = null

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = check(i)
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Pemantauan", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Alarm baterai", NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(1, monitorNotification("Memulai pemantauan..."))
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_TEST -> sendMessages("✅ Tes dari Pemantau Baterai berhasil.")
        }
        return START_STICKY
    }

    private fun check(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        if (pct != lastLevel || charging != lastCharging) {
            getSystemService(NotificationManager::class.java).notify(
                1,
                monitorNotification("Baterai $pct% • ${if (charging) "mengisi" else "tidak mengisi"} • ${"%.1f".format(temp)}°C")
            )
        }
        lastLevel = pct
        lastCharging = charging

        val high = prefs.getInt("high", 80)
        val low = prefs.getInt("low", 20)
        val tLimit = prefs.getInt("temp", 0)

        // Penuh
        if (charging && pct >= high) {
            if (!highTriggered) {
                highTriggered = true
                trigger("🔋 Baterai sudah $pct% (batas $high%). Cabut charger.")
            }
        } else if (!charging || pct < high) highTriggered = false

        // Rendah
        if (!charging && pct <= low) {
            if (!lowTriggered) {
                lowTriggered = true
                trigger("🪫 Baterai tinggal $pct% (batas $low%). Segera dicas.")
            }
        } else if (charging || pct > low) lowTriggered = false

        // Suhu
        if (tLimit > 0 && temp >= tLimit) {
            if (!tempTriggered) {
                tempTriggered = true
                trigger("🌡️ Suhu baterai ${"%.1f".format(temp)}°C (batas $tLimit°C).")
            }
        } else if (temp < tLimit - 2) tempTriggered = false

        // Alarm mati otomatis bila kondisi pemicu sudah tidak berlaku:
        // penuh -> adaptor dicabut, habis -> adaptor dicolok, suhu sudah turun.
        if (alarmActive && !highTriggered && !lowTriggered && !tempTriggered) {
            stopAlarm()
        }
    }

    private fun trigger(msg: String) {
        startAlarm(msg)
        sendMessages(msg)
    }

    private fun startAlarm(msg: String) {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, BatteryService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ALARM BATERAI")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "MATIKAN ALARM", stopPi).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)

        alarmActive = true
        if (player?.isPlaying == true) return
        try {
            val am = getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@BatteryService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }

        vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 600, 400), 0)
        }
    }

    private fun stopAlarm() {
        alarmActive = false
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        vibrator?.cancel()
        getSystemService(NotificationManager::class.java).cancel(2)
    }

    private fun sendMessages(text: String) {
        Thread {
            if (prefs.getBoolean("tgOn", false)) {
                val token = prefs.getString("tgToken", "") ?: ""
                val chat = prefs.getString("tgChat", "") ?: ""
                if (token.isNotEmpty() && chat.isNotEmpty()) {
                    httpGet("https://api.telegram.org/bot$token/sendMessage?chat_id=${enc(chat)}&text=${enc(text)}")
                }
            }
            if (prefs.getBoolean("waOn", false)) {
                val target = prefs.getString("waPhone", "") ?: ""
                val token = prefs.getString("waKey", "") ?: ""
                if (target.isNotEmpty() && token.isNotEmpty()) {
                    // Fonnte: POST https://api.fonnte.com/send, header Authorization = token
                    httpPost(
                        "https://api.fonnte.com/send",
                        "target=${enc(target)}&message=${enc(text)}&countryCode=62",
                        token
                    )
                }
            }
        }.start()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.responseCode
            c.disconnect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun httpPost(url: String, body: String, token: String) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", token)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText()
            android.util.Log.d("Fonnte", "HTTP $code $resp")
            c.disconnect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun monitorNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Pemantau Baterai aktif")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopAlarm()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
