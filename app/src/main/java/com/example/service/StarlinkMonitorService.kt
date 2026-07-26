package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.Device
import com.example.receiver.NotificationReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetAddress

class StarlinkMonitorService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "monitor_channel",
                "مراقبة الشبكة في الخلفية",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, "monitor_channel")
            .setContentTitle("مدير Starlink")
            .setContentText("جاري مراقبة الشبكة في الخلفية...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startMonitoring() {
        scope.launch {
            db.deviceDao().getAll().collect { devices ->
                updateForegroundNotification(devices)
            }
        }

        scope.launch {
            while (isActive) {
                scanNetwork()
                
                // Refresh notification manually to update time-based stats dynamically
                val currentDevices = db.deviceDao().getAll().first()
                updateForegroundNotification(currentDevices)
                
                delay(60000) // فحص الشبكة كل دقيقة
            }
        }
    }

    private fun updateForegroundNotification(devices: List<Device>) {
        val now = System.currentTimeMillis()
        var activeCount = 0
        var endedCount = 0
        var expiringSoonCount = 0
        
        for (device in devices) {
            val remainingMillis = if (device.isPaused) {
                device.remainingWhenPaused
            } else {
                maxOf(0L, device.endTime - now)
            }
            
            if (device.endTime > 0) {
                if (remainingMillis > 0) {
                    activeCount++
                    if (remainingMillis < 15 * 60 * 1000) { // أقل من 15 دقيقة
                        expiringSoonCount++
                    }
                } else if (remainingMillis <= 0L && !device.isPaused) {
                    endedCount++
                }
            }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val rssi = wifiInfo.rssi
        val quality = when {
            rssi > -50 -> "ممتاز"
            rssi > -60 -> "جيد جداً"
            rssi > -70 -> "جيد"
            rssi > -80 -> "ضعيف"
            else -> "سيء"
        }
        val speed = wifiInfo.linkSpeed
        val ssid = wifiInfo.ssid?.replace("\"", "") ?: "شبكة"

        val summaryText = "نشط: $activeCount | منتهي: $endedCount | قارب للانتهاء: $expiringSoonCount"
        val fullText = "$summaryText\nالشبكة: $ssid | الإشارة: $quality ($speed Mbps)"

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "monitor_channel")
            .setContentTitle("إحصائيات Starlink")
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    private suspend fun scanNetwork() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifiManager.dhcpInfo
        val ipAddress = dhcp.ipAddress
        if (ipAddress == 0) return

        val ipString = String.format("%d.%d.%d", ipAddress and 0xff, ipAddress shr 8 and 0xff, ipAddress shr 16 and 0xff)
        val jobs = (2..60).map { i ->
            scope.async {
                val testIp = "$ipString.$i"
                try {
                    val process = Runtime.getRuntime().exec("ping -c 1 -W 1 $testIp")
                    val exitVal = process.waitFor()
                    if (exitVal == 0) {
                        val inet = InetAddress.getByName(testIp)
                        val hostName = inet.hostName
                        handleDeviceFound(testIp, hostName)
                    }
                } catch (e: Exception) {}
            }
        }
        jobs.awaitAll()
    }

    private suspend fun handleDeviceFound(ip: String, hostName: String) {
        val existing = db.deviceDao().getByIp(ip)
        if (existing == null) {
            val nameToSave = if (hostName != ip) hostName else "جهاز غير معروف"
            sendNewDeviceNotification(this, ip, nameToSave)
            // إضافة الجهاز بصلاحية منتهية حتى لا يظهر الإشعار مرة أخرى لنفس الجهاز
            db.deviceDao().insert(Device(ip = ip, name = "$nameToSave ($ip)", endTime = 0))
        }
    }

    private fun sendNewDeviceNotification(context: Context, ip: String, name: String) {
        val notifId = ip.hashCode()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val intent1 = Intent(context, NotificationReceiver::class.java).apply {
            action = "ADD_TIME"
            putExtra("DEVICE_IP", ip)
            putExtra("HOURS", 1)
            putExtra("NOTIF_ID", notifId)
        }
        val pIntent1 = PendingIntent.getBroadcast(context, 1 + notifId, intent1, flags)

        val intent2 = Intent(context, NotificationReceiver::class.java).apply {
            action = "ADD_TIME"
            putExtra("DEVICE_IP", ip)
            putExtra("HOURS", 2)
            putExtra("NOTIF_ID", notifId)
        }
        val pIntent2 = PendingIntent.getBroadcast(context, 2 + notifId, intent2, flags)
        
        val intent3 = Intent(context, NotificationReceiver::class.java).apply {
            action = "ADD_TIME"
            putExtra("DEVICE_IP", ip)
            putExtra("HOURS", 3)
            putExtra("NOTIF_ID", notifId)
        }
        val pIntent3 = PendingIntent.getBroadcast(context, 3 + notifId, intent3, flags)

        val notification = NotificationCompat.Builder(context, "starlink_channel")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("جهاز جديد متصل!")
            .setContentText("IP: $ip ($name)")
            .addAction(0, "ساعة", pIntent1)
            .addAction(0, "ساعتين", pIntent2)
            .addAction(0, "3 ساعات", pIntent3)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        try { NotificationManagerCompat.from(context).notify(notifId, notification) } catch (e: Exception) {}
    }
}
