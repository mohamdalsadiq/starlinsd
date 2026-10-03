package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.network.router.IdentityStabilityReport
import com.example.network.router.RouterClient
import com.example.network.router.RouterDiscoveryReport
import com.example.network.router.RouterDiscoveryService
import com.example.network.router.RouterIdentityComparison
import com.example.network.router.Stability
import com.example.network.router.identityLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Experimental, isolated diagnostic panel for the router API prototype. It performs bounded
 * read-only snapshots on explicit taps only. It is not wired to any subscription, timer, revenue,
 * HOME, notification, shortcut, recovery or backup behaviour.
 */
@Composable internal fun RouterPrototypePanel(
    discover: (suspend () -> RouterDiscoveryReport)? = null,
    onRunning: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val service = remember(context) { RouterDiscoveryService(context) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<RouterDiscoveryReport?>(null) }
    var second by remember { mutableStateOf<RouterDiscoveryReport?>(null) }
    var comparison by remember { mutableStateOf<IdentityStabilityReport?>(null) }

    fun snapshot(slot: Int) {
        running = true; onRunning(true); notice = ""
        job = scope.launch {
            try {
                val report = discover?.invoke() ?: service.discover()
                if (slot == 1) { first = report; second = null; comparison = null } else {
                    second = report
                    comparison = first?.takeIf { it.clients.isNotEmpty() && report.clients.isNotEmpty() }
                        ?.let { RouterIdentityComparison.compare(it.clients, report.clients) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = "تعذر إكمال القراءة. تحقق من الاتصال بشبكة الراوتر ثم أعد المحاولة."
            } finally {
                running = false; onRunning(false); job = null
            }
        }
    }

    Panel {
        Text("قراءة أجهزة الراوتر مباشرة (تجريبي)", style = MaterialTheme.typography.titleMedium)
        Text("قراءة فقط من 192.168.1.1:9000. لا إيقاف ولا حظر ولا تغيير في الراوتر أو الاشتراكات. " +
            "خُذ اللقطة ١، ثم افصل الجهاز وأعد وصله، ثم اللقطة ٢ للمقارنة.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running, modifier = Modifier.weight(1f).testTag("router-snapshot-1"), onClick = { snapshot(1) }) { Text("لقطة ١") }
            OutlinedButton(enabled = !running && first != null, modifier = Modifier.weight(1f).testTag("router-snapshot-2"), onClick = { snapshot(2) }) { Text("لقطة ٢ · مقارنة") }
        }
        if (running) Text("جاري القراءة…")
        if (notice.isNotBlank()) Text(notice)
        first?.let { Text("لقطة ١: ${it.clients.size} سجلًا · ${if (it.reachable) "وصلنا للراوتر" else "تعذر الوصول"}") }
        second?.let { Text("لقطة ٢: ${it.clients.size} سجلًا · ${if (it.reachable) "وصلنا للراوتر" else "تعذر الوصول"}") }
        val shown = second ?: first
        shown?.let { report ->
            TextButton(onClick = { clipboard.setText(AnnotatedString(report.diagnostic())); notice = "نُسخ التشخيص بدون أسماء الأجهزة أو عناوينها." }) { Text("نسخ تشخيص القراءة") }
        }
        comparison?.let { result ->
            Text("نتيجة المقارنة", style = MaterialTheme.typography.titleMedium)
            Text("أزواج: ${result.pairedCount} · ظهر: ${result.appeared.size} · اختفى: ${result.disappeared.size}")
            Text("MAC: ${verdictText(result.macStable)} · IP: ${verdictText(result.ipStable)} · clientId: ${verdictText(result.clientIdStable)}")
            Text("deviceId: ${verdictText(result.deviceIdStable)} · معرّف ثابت آخر: ${result.otherStableIdentifier ?: "لا يوجد"}")
            TextButton(onClick = { clipboard.setText(AnnotatedString(result.diagnostic())); notice = "نُسخ تشخيص المقارنة بدون بيانات تعريفية." }) { Text("نسخ تشخيص المقارنة") }
        }
        if (shown != null && shown.clients.isNotEmpty()) {
            Text("سجلات اللقطة ${if (second != null) "٢" else "١"}", style = MaterialTheme.typography.titleMedium)
            shown.clients.forEachIndexed { index, client -> ClientRow(index, client) }
        }
        if (shown?.duplicates?.isNotEmpty() == true) {
            Text("تكرار في المعرّفات داخل نفس اللقطة: ${shown.duplicates.size} — لا تُطابَق تلقائيًا.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun ClientRow(index: Int, client: RouterClient) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${index + 1}. ${client.hostname.ifBlank { "جهاز بدون اسم" }}", style = MaterialTheme.typography.labelLarge)
        Text("MAC: ${client.mac ?: client.rawMac.ifBlank { "غير متاح" }}${if (client.macMasked) " (مقنّع)" else ""}", style = MaterialTheme.typography.bodySmall)
        Text("IP (حالي): ${client.ip ?: client.rawIp.ifBlank { "غير متاح" }} · clientId: ${client.clientId ?: "غير متاح"}", style = MaterialTheme.typography.bodySmall)
        Text("deviceId: ${client.deviceId.ifBlank { "غير متاح" }} · الدور: ${client.role ?: "غير متاح"} · الحالة: ${when (client.active) { true -> "نشط"; false -> "غير نشط"; null -> "غير مؤكدة" }}", style = MaterialTheme.typography.bodySmall)
        Text("الهوية المقترحة: ${client.identityLabel()} · الحظر: ${when (client.blocked) { true -> "موقوف"; false -> "غير موقوف"; null -> "غير موجود" }}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun verdictText(value: Stability) = when (value) {
    Stability.STABLE -> "ثابت"
    Stability.CHANGED -> "تغيّر"
    Stability.UNKNOWN -> "غير معروف"
}
