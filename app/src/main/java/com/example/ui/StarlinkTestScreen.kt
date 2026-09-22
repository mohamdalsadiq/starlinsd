package com.example.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.network.ProbeReport
import com.example.network.StarlinkProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable internal fun StarlinkTestScreen(runProbe: (suspend ((String) -> Unit) -> ProbeReport)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val probe = remember(context) { StarlinkProbe(context) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var controlling by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var accountReady by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ProbeReport?>(null) }
    var notice by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().testTag("starlink-screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Panel {
            SectionHeading(Icons.Default.Router, "اختبار الاتصال المحلي", "قراءة تجريبية من راوتر Starlink")
            Text("اتصل بشبكة Wi-Fi الرئيسية لراوتر Starlink مباشرة. زر القراءة يعرض الأجهزة وحالة الطبق. تجربة الإيقاف منفصلة وتحتاج اختيار جهاز وتأكيدًا منك.")
            Text("اختبار القراءة لا يحتاج حساب Starlink؛ التحكم عبر الحساب يحتاج ربطًا منفصلًا. لا يعمل تلقائيًا في الخلفية، وتتوقف المحاولة عند مغادرة الصفحة. قد لا يتيح تحديث الراوتر قراءة القائمة.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running && !controlling && !linking, modifier = Modifier.fillMaxWidth().testTag("starlink-start"), onClick = {
                running = true; report = null; notice = ""; progress = "جاري فحص Wi-Fi…"
                job = scope.launch {
                    try { report = if (runProbe != null) runProbe { progress = it } else probe.run { progress = it } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice = "تعذر إكمال الاختبار. تحقق من Wi-Fi وأذونات الشبكة ثم أعد المحاولة." }
                    finally { running = false; job = null; progress = "" }
                }
            }) { Text("بدء اختبار القراءة") }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress)
                TextButton(modifier = Modifier.testTag("starlink-cancel"), onClick = { job?.cancel(); notice = "أُلغي الاختبار؛ لم تتغير إعدادات الراوتر." }) { Text("إلغاء الاختبار") }
            }
            TextButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { notice = "افتح إعدادات Wi-Fi من الهاتف." }
            }) { Text("إعدادات Wi-Fi") }
            if (notice.isNotBlank()) Text(notice)
        } }
        item { StarlinkAccountPanel(running || controlling, { linking = it }, { accountReady = it }) }
        item { StarlinkControlPanel(report?.clients.orEmpty(), running || linking, { controlling = it }, cloud = true, accountReady = accountReady) }
        report?.let { current ->
            item { Panel {
                Text(current.summary, style = MaterialTheme.typography.titleMedium)
                Text("وقت القراءة: ${stamp(current.at)} · لقطة وقت الاختبار وليست متابعة مباشرة")
                if (current.clients != null) {
                    Text("سجلات الأجهزة المستلمة: ${current.clients.size}")
                    Text("قد تتضمن القائمة أجهزة سابقة أو وحدات Mesh. لا نعتبر السجل متصلًا الآن ما لم يُرجع الراوتر حالة صريحة؛ وعدم ظهور جهاز لا يثبت فصله.", style = MaterialTheme.typography.bodySmall)
                    if (current.clients.isEmpty()) Text("الراوتر أعاد قائمة فارغة؛ قارنها بتطبيق Starlink قبل الاعتماد عليها.")
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(current.diagnostic())); notice = "نُسخت نتيجة التشخيص بدون أسماء الأجهزة أو عناوينها." }) { Text("نسخ نتيجة التشخيص") }
            } }
            itemsIndexed(current.clients.orEmpty()) { index, device -> Panel {
                Text("${index + 1}. ${device.name}", style = MaterialTheme.typography.titleMedium)
                Text("IP: ${device.ip.ifBlank { "غير متاح" }}")
                Text("MAC: ${device.mac.ifBlank { "غير متاح" }}")
                Text("ID: ${device.id ?: "غير متاح"}")
                Text(when (device.blocked) { true -> "الإنترنت: موقوف من الراوتر"; false -> "الإنترنت: غير موقوف حسب الرد"; null -> "حالة الإيقاف: الحقل غير موجود في الرد" })
                Text(when (device.active) { true -> "الحالة من الراوتر: نشط"; false -> "الحالة من الراوتر: غير نشط"; null -> "حالة الاتصال: غير مؤكدة من الرد" })
            } }
            item { Title("تفاصيل المحاولة") }
            itemsIndexed(current.steps) { _, step -> Panel {
                Text(step.target, style = MaterialTheme.typography.labelLarge)
                Text(step.outcome, color = if (step.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            } }
        }
    }
}
