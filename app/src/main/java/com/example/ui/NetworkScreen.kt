package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.db.Device
import kotlinx.coroutines.delay

@Composable
fun NetworkScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val context = LocalContext.current
    var showAddManualDialog by remember { mutableStateOf(false) }
    
    val activeDevicesCount = devices.count { (!it.isPaused && it.endTime > System.currentTimeMillis()) || (it.isPaused && it.remainingWhenPaused > 0) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("الأجهزة المشتركة: $activeDevicesCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row {
                IconButton(onClick = { showAddManualDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة جهاز يدوياً")
                }
                Button(onClick = { viewModel.startScan(context) }, enabled = !isScanning) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    Spacer(Modifier.width(4.dp))
                    Text(if (isScanning) "جاري..." else "فحص")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.id }) { device ->
                DeviceCard(device, viewModel)
            }
        }
    }

    if (showAddManualDialog) {
        var ipInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddManualDialog = false },
            title = { Text("إضافة جهاز بالـ IP يدوياً") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("عنوان IP (مثال: 192.168.1.50)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("اسم الجهاز (اختياري)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ipInput.isNotBlank()) {
                        viewModel.addManualDevice(ipInput, nameInput)
                    }
                    showAddManualDialog = false
                }) { Text("إضافة") }
            },
            dismissButton = {
                TextButton(onClick = { showAddManualDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun DeviceCard(device: Device, viewModel: MainViewModel) {
    var remainingText by remember { mutableStateOf(formatTime(device)) }
    var showRenameDialog by remember { mutableStateOf(false) }
    
    // Auto update remaining time
    LaunchedEffect(device.endTime, device.isPaused) {
        while (true) {
            remainingText = formatTime(device)
            delay(1000)
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("إعادة تسمية الجهاز") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("اسم الجهاز") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.updateDeviceName(device, newName.trim())
                    }
                    showRenameDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("إلغاء") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(device.ip, fontWeight = FontWeight.Bold)
                Text(remainingText, color = if (device.isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الاسم: ${device.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", modifier = Modifier.size(20.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row {
                    Button(onClick = { viewModel.addDeviceTime(device, 1) }) { Text("+1س") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { viewModel.addDeviceTime(device, 2) }) { Text("+2س") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { viewModel.addDeviceTime(device, 3) }) { Text("+3س") }
                }
                Row {
                    IconButton(onClick = { viewModel.togglePause(device) }) {
                        Icon(if (device.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = "إيقاف/استئناف")
                    }
                    IconButton(onClick = { viewModel.removeDevice(device) }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun formatTime(device: Device): String {
    val remainingMillis = if (device.isPaused) {
        device.remainingWhenPaused
    } else {
        maxOf(0L, device.endTime - System.currentTimeMillis())
    }
    
    if (remainingMillis <= 0 && device.endTime > 0) return "منتهي"
    if (device.endTime == 0L) return "بدون اشتراك"
    
    val hours = remainingMillis / 3600000
    val minutes = (remainingMillis % 3600000) / 60000
    val seconds = (remainingMillis % 60000) / 1000
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}
