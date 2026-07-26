package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.Device
import com.example.db.Shortcut
import com.example.receiver.NotificationReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val devices = db.deviceDao().getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val shortcuts = db.shortcutDao().getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    init {
        createNotificationChannel(application)
        startTimerTick()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "starlink_channel",
                "تنبيهات Starlink",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startTimerTick() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val currentDevices = devices.value
                val now = System.currentTimeMillis()
                for (device in currentDevices) {
                    if (!device.isPaused && device.endTime > 0 && device.endTime <= now) {
                        sendTimeUpNotification(getApplication(), device.ip, device.name)
                        db.deviceDao().update(device.copy(endTime = 0))
                    }
                }
            }
        }
    }

    fun startScan(context: Context) {
        if (_isScanning.value) return
        _isScanning.value = true
        
        viewModelScope.launch(Dispatchers.IO) {
            val foundIps = mutableSetOf<String>()

            // Method 1: Read ARP table (/proc/net/arp)
            try {
                val file = java.io.File("/proc/net/arp")
                if (file.exists()) {
                    file.forEachLine { line ->
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 4 && parts[0] != "IP" && parts[2] == "0x2") {
                            val arpIp = parts[0]
                            if (arpIp != "0.0.0.0" && !arpIp.startsWith("127.")) {
                                foundIps.add(arpIp)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}

            // Method 2: Discover base subnet IP
            var baseSubnet = getLocalSubnet(context)
            if (baseSubnet.isEmpty()) {
                baseSubnet = "192.168.1"
            }

            // Ping active range
            val jobs = (2..254).map { i ->
                async {
                    val testIp = "$baseSubnet.$i"
                    try {
                        val inet = InetAddress.getByName(testIp)
                        if (inet.isReachable(300)) {
                            foundIps.add(testIp)
                        } else {
                            val process = Runtime.getRuntime().exec("ping -c 1 -w 1 $testIp")
                            if (process.waitFor() == 0) {
                                foundIps.add(testIp)
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            jobs.awaitAll()

            // Save discovered devices
            for (ip in foundIps) {
                try {
                    val inet = InetAddress.getByName(ip)
                    val hostName = inet.hostName
                    handleDeviceFound(ip, if (hostName != ip) hostName else "جهاز متصل", context)
                } catch (e: Exception) {
                    handleDeviceFound(ip, "جهاز متصل", context)
                }
            }

            _isScanning.value = false
        }
    }

    fun addManualDevice(ip: String, name: String) {
        val trimmedIp = ip.trim()
        val trimmedName = if (name.isBlank()) "جهاز يدوياً ($trimmedIp)" else name.trim()
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.deviceDao().getByIp(trimmedIp)
            if (existing == null) {
                db.deviceDao().insert(Device(ip = trimmedIp, name = trimmedName, endTime = 0))
            }
        }
    }

    private fun getLocalSubnet(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            val ipAddress = dhcp.ipAddress
            if (ipAddress != 0) {
                return String.format("%d.%d.%d", ipAddress and 0xff, ipAddress shr 8 and 0xff, ipAddress shr 16 and 0xff)
            }
            
            // Fallback to NetworkInterface
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isLoopback && iface.isUp) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return "192.168.1"
    }

    private suspend fun handleDeviceFound(ip: String, hostName: String, context: Context) {
        val existing = db.deviceDao().getByIp(ip)
        if (existing == null) {
            val nameToSave = if (hostName != ip) hostName else "جهاز جديد"
            sendNewDeviceNotification(context, ip, nameToSave)
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
        
        val intent2 = Intent(context, NotificationReceiver::class.java).apply {
            action = "ADD_TIME"
            putExtra("DEVICE_IP", ip)
            putExtra("HOURS", 2)
            putExtra("NOTIF_ID", notifId)
        }
        val pIntent2 = PendingIntent.getBroadcast(context, 2 + notifId, intent2, flags)

        val intent5 = Intent(context, NotificationReceiver::class.java).apply {
            action = "ADD_TIME"
            putExtra("DEVICE_IP", ip)
            putExtra("HOURS", 5)
            putExtra("NOTIF_ID", notifId)
        }
        val pIntent5 = PendingIntent.getBroadcast(context, 5 + notifId, intent5, flags)

        val notification = NotificationCompat.Builder(context, "starlink_channel")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("جهاز جديد متصل!")
            .setContentText("IP: $ip ($name)")
            .addAction(0, "ساعتين", pIntent2)
            .addAction(0, "5 ساعات", pIntent5)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        try { NotificationManagerCompat.from(context).notify(notifId, notification) } catch (e: Exception) {}
    }

    private fun sendTimeUpNotification(context: Context, ip: String, name: String) {
        val notification = NotificationCompat.Builder(context, "starlink_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("انتهى الوقت!")
            .setContentText("الجهاز $ip ($name) انتهى اشتراكه.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        try { NotificationManagerCompat.from(context).notify(ip.hashCode() + 100, notification) } catch (e: Exception) {}
    }

    fun addDeviceTime(device: Device, hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val additionalMillis = hours * 3600000L
            val newEndTime = if (device.isPaused) {
                System.currentTimeMillis() + device.remainingWhenPaused + additionalMillis
            } else {
                maxOf(System.currentTimeMillis(), device.endTime) + additionalMillis
            }
            db.deviceDao().update(device.copy(endTime = newEndTime, isPaused = false, remainingWhenPaused = 0))
        }
    }

    fun togglePause(device: Device) {
        viewModelScope.launch(Dispatchers.IO) {
            if (device.isPaused) {
                val newEndTime = System.currentTimeMillis() + device.remainingWhenPaused
                db.deviceDao().update(device.copy(isPaused = false, endTime = newEndTime, remainingWhenPaused = 0))
            } else {
                val remaining = maxOf(0L, device.endTime - System.currentTimeMillis())
                db.deviceDao().update(device.copy(isPaused = true, remainingWhenPaused = remaining))
            }
        }
    }
    
    fun removeDevice(device: Device) {
        viewModelScope.launch(Dispatchers.IO) { db.deviceDao().delete(device) }
    }

    fun updateDeviceName(device: Device, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deviceDao().update(device.copy(name = newName))
        }
    }

    fun saveShortcut(keyword: String, phrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.shortcutDao().insert(Shortcut(keyword = keyword, phrase = phrase))
        }
    }

    fun deleteShortcut(shortcut: Shortcut) {
        viewModelScope.launch(Dispatchers.IO) {
            db.shortcutDao().delete(shortcut)
        }
    }
}
