package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.db.AppDatabase
import com.example.db.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val ip = intent.getStringExtra("DEVICE_IP") ?: return
        val hours = intent.getIntExtra("HOURS", 0)
        val notifId = intent.getIntExtra("NOTIF_ID", 0)

        if (action == "ADD_TIME" && hours > 0) {
            val db = AppDatabase.getDatabase(context)
            CoroutineScope(Dispatchers.IO).launch {
                val device = db.deviceDao().getByIp(ip)
                val additionalMillis = hours * 3600000L
                if (device != null) {
                    val newEndTime = if (device.isPaused) {
                        System.currentTimeMillis() + device.remainingWhenPaused + additionalMillis
                    } else {
                        maxOf(System.currentTimeMillis(), device.endTime) + additionalMillis
                    }
                    db.deviceDao().update(device.copy(endTime = newEndTime, isPaused = false, remainingWhenPaused = 0))
                } else {
                    db.deviceDao().insert(Device(ip = ip, name = "جهاز جديد ($ip)", endTime = System.currentTimeMillis() + additionalMillis))
                }
            }
        }
        
        try {
            NotificationManagerCompat.from(context).cancel(notifId)
        } catch (e: Exception) {}
    }
}
