package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainViewModel
import com.example.network.StarlinkProtocol

@Composable fun DeviceTrackingScreen(vm: MainViewModel) {
    val scanned by vm.scannedDevices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val household by vm.householdIps.collectAsStateWithLifecycle()
    val watchlist by vm.watchlistIps.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val householdSet = remember(household) { household.map { it.ip }.toSet() }
    val watchlistSet = remember(watchlist) { watchlist.map { it.ip }.toSet() }
    val linkedClientIds = remember(sessions) { sessions.mapNotNull { it.deviceClientId }.toSet() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Title("تتبع الأجهزة", "اقرأ الأجهزة المتصلة بالراوتر واربطها بالاشتراكات") }

        item {
            Button(
                onClick = { vm.scanDevices() },
                enabled = !scanning && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("جاري البحث عن الأجهزة…")
                } else {
                    Icon(Icons.Default.Wifi, null)
                    Spacer(Modifier.width(8.dp))
                    Text("قراءة الأجهزة من الراوتر")
                }
            }
        }

        if (scanned.isNotEmpty()) {
            item {
                Panel {
                    Text("الأجهزة المكتشفة: ${scanned.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("بمعرّف: ${scanned.count { it.id != null }} · مرتبطة باشتراكات: ${scanned.count { it.id != null && it.id.toString() in linkedClientIds }}", style = MaterialTheme.typography.bodySmall)
                }
            }

            items(scanned, key = { it.ip }) { client ->
                DeviceCard(
                    client = client,
                    isHousehold = client.ip in householdSet,
                    isWatchlist = client.ip in watchlistSet,
                    isLinked = client.id != null && client.id.toString() in linkedClientIds,
                    linkedSession = sessions.find { it.deviceClientId == client.id?.toString() },
                    onAddHousehold = { vm.addToHousehold(client.ip) },
                    onAddWatchlist = { vm.addToWatchlist(client.ip) },
                    onRemove = { type -> vm.removeFromList(client.ip, type) },
                )
            }
        } else if (!scanning) {
            item {
                Panel {
                    Text("لم يتم البحث بعد")
                    Text("اضغط «قراءة الأجهزة من الراوتر» لاكتشاف الأجهزة المتصلة. تأكد من اتصالك بشبكة Wi-Fi الخاصة بالراوتر.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        if (household.isNotEmpty()) {
            item {
                Panel {
                    SectionHeading(Icons.Default.Home, "أهل البيت", "غير مراقبين · ${household.size}")
                    household.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(entry.ip, Modifier.weight(1f).padding(horizontal = 8.dp))
                            TextButton(onClick = { vm.removeFromList(entry.ip, "HOUSEHOLD") }) { Text("إزالة") }
                        }
                    }
                }
            }
        }

        if (watchlist.isNotEmpty()) {
            item {
                Panel {
                    SectionHeading(Icons.Default.Visibility, "قائمة المراقبة", "أجهزة تحت متابعة دقيقة · ${watchlist.size}")
                    watchlist.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Text(entry.ip, Modifier.weight(1f).padding(horizontal = 8.dp))
                            TextButton(onClick = { vm.removeFromList(entry.ip, "WATCHLIST") }) { Text("إزالة") }
                        }
                    }
                }
            }
        }

        item {
            Panel {
                SectionHeading(Icons.Default.Info, "كيف يعمل التتبع", "")
                Text("1. اقرأ الأجهزة من الراوتر", style = MaterialTheme.typography.bodySmall)
                Text("2. عند إنشاء اشتراك، اختر الجهاز المرتبط", style = MaterialTheme.typography.bodySmall)
                Text("3. عند انفصال الجهاز، يُوقف الاشتراك تلقائيًا", style = MaterialTheme.typography.bodySmall)
                Text("4. عند عودة الجهاز، يُستأنف الاشتراف تلقائيًا", style = MaterialTheme.typography.bodySmall)
                Text("5. أهل البيت مستثنون من كل المراقبة", style = MaterialTheme.typography.bodySmall)
                Text("الربط يتم عبر clientId من الراوتر وليس MAC. القوائم تعتمد على IP كما طلبت.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun DeviceCard(
    client: StarlinkProtocol.Client,
    isHousehold: Boolean,
    isWatchlist: Boolean,
    isLinked: Boolean,
    linkedSession: com.example.db.Session?,
    onAddHousehold: () -> Unit,
    onAddWatchlist: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val categoryColor = when {
        isHousehold -> MaterialTheme.colorScheme.primary
        isWatchlist -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val categoryLabel = when {
        isHousehold -> "أهل البيت"
        isWatchlist -> "مراقبة"
        else -> "غير مصنّف"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (client.active) Icons.Default.PhoneAndroid else Icons.Default.PhoneDisabled,
                    null, tint = categoryColor
                )
                Text(client.name, Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
                Surface(color = categoryColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(categoryLabel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = categoryColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("IP: ${client.ip}", style = MaterialTheme.typography.bodySmall)
            if (client.id != null) Text("clientId: ${client.id}", style = MaterialTheme.typography.bodySmall)
            if (client.mac.isNotEmpty()) Text("MAC: ${client.mac}", style = MaterialTheme.typography.bodySmall)
            Text(if (client.active) "متصل" else "غير متصل", style = MaterialTheme.typography.bodySmall, color = if (client.active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)

            if (isLinked && linkedSession != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("مرتبط بـ: ${linkedSession.client} · ${linkedSession.plan}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isHousehold) TextButton(onClick = onAddHousehold) { Text("أهل البيت") }
                else TextButton(onClick = { onRemove("HOUSEHOLD") }) { Text("إزالة من أهل البيت") }
                if (!isWatchlist) TextButton(onClick = onAddWatchlist) { Text("مراقبة") }
                else TextButton(onClick = { onRemove("WATCHLIST") }) { Text("إزالة من المراقبة") }
            }
        }
    }
}
