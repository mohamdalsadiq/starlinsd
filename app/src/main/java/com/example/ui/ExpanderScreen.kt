package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.db.Shortcut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpanderScreen(viewModel: MainViewModel) {
    val shortcuts by viewModel.shortcuts.collectAsState()
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "إضافة اختصار")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("اختصاراتي (${shortcuts.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { 
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "إعدادات الخدمة")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("💡 خطوة تفعيل الخدمة في أندرويد 13/14 (مهم جداً):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("1. اضغط زر الإعدادات أعلاه ⚙️ للذهاب لإمكانية الوصول.\n2. إذا ظهرت لك عبارة (إعداد مقيد / Restricted setting):\nاذهب إلى إعدادات الهاتف ← التطبيقات ← مدير Starlink ← اضغط (⋮) بالأعلى ← (السماح بالإعدادات المقيدة).", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("فتح معلومات التطبيق للسماح بالإعداد المقيد")
                    }
                }
            }
            
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shortcuts, key = { it.id }) { shortcut ->
                    ShortcutCard(shortcut, viewModel)
                }
            }
        }
    }

    if (showDialog) {
        AddShortcutDialog(
            onDismiss = { showDialog = false },
            onSave = { k, p -> 
                viewModel.saveShortcut(k, p)
                showDialog = false
            }
        )
    }
}

@Composable
fun ShortcutCard(shortcut: Shortcut, viewModel: MainViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(shortcut.keyword, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(shortcut.phrase, fontSize = 14.sp)
            }
            Row {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("shortcut", shortcut.phrase))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ")
                }
                IconButton(onClick = { viewModel.deleteShortcut(shortcut) }) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AddShortcutDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var showTimeDialog by remember { mutableStateOf(false) }

    if (showTimeDialog) {
        TimeAdditionDialog(
            onDismiss = { showTimeDialog = false },
            onConfirm = { hours ->
                val tag = if (hours > 0) "%time+${hours}h%" else "%time%"
                phrase += tag
                showTimeDialog = false
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة اختصار جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("الكلمة المفتاحية (مثل mn)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("النص الممتد") },
                    modifier = Modifier.height(120.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { phrase += "%date%" }) { Text("التاريخ") }
                    Button(onClick = { phrase += "%day%" }) { Text("اليوم") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { phrase += "%time%" }) { Text("الوقت الحالي") }
                    Button(onClick = { showTimeDialog = true }) { Text("إضافة ساعات") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (keyword.isNotBlank() && phrase.isNotBlank()) {
                    onSave(keyword.trim(), phrase)
                }
            }) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun TimeAdditionDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var hours by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كم عدد الساعات المراد إضافتها؟") },
        text = {
            OutlinedTextField(
                value = hours,
                onValueChange = { if (it.all { char -> char.isDigit() }) hours = it },
                label = { Text("عدد الساعات") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(hours.toIntOrNull() ?: 0) }) { Text("تأكيد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
