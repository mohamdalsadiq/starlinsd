package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun StarlinkControlPanel(
    clients: List<StarlinkProtocol.Client>, readBusy: Boolean, onBusy: (Boolean) -> Unit,
    controller: RouterControl? = null,
) {
    val context = LocalContext.current
    val control = remember(context, controller) { controller ?: RouterControl(context.applicationContext) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingPause?>(null) }
    var preview by remember { mutableStateOf<PausePreview?>(null) }
    var selected by remember { mutableStateOf<StarlinkProtocol.Client?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var storageError by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf("") }
    fun refreshPending() {
        try { pending = control.pending(); storageError = false }
        catch (_: Exception) { storageError = true; message = "تعذر قراءة سجل الاسترجاع؛ استخدم تطبيق Starlink لإعادة الإنترنت. لا تحذف بيانات Slotra." }
    }
    LaunchedEffect(control) { refreshPending() }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun execute(action: suspend () -> Unit) {
        busy = true; onBusy(true); message = "جاري قراءة بيانات الراوتر والتحقق…"
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = controlError(e); diagnostic = "CONTROL: ${errorCode(e)}" }
            finally { refreshPending(); busy = false; onBusy(false) }
        }
    }
    Panel {
        Text("تجربة إيقاف جهاز واحد", style = MaterialTheme.typography.titleLarge)
        Text("اختر هاتفًا آخر تملكه للتجربة. نقرأ الهوية أولًا، ثم نعرض تأكيدًا قبل تغيير الإيقاف. التجربة مستقلة عن الاشتراكات والحسابات.")
        Text("جهّز تطبيق Starlink لإلغاء الإيقاف عند الحاجة. مغادرة الصفحة أثناء الإرسال قد تترك النتيجة غير مؤكدة؛ سجل الاسترجاع يبقى محفوظًا.", style = MaterialTheme.typography.bodySmall)
        if (pending != null) {
            Text("اختبار يحتاج مراجعة: ${pending!!.device.name}", style = MaterialTheme.typography.titleMedium)
            Text("قد يكون الجهاز موقوفًا. افحص إعادة الإنترنت حتى لو لم تصلك نتيجة الأمر السابق.")
            Button(enabled = !busy && !readBusy, modifier = Modifier.fillMaxWidth().testTag("starlink-restore"), onClick = {
                execute { preview = control.prepareRestore(); acknowledged = false; message = "راجع الجهاز ثم أكد إعادة الإنترنت." }
            }) { Text("فحص إعادة الإنترنت") }
        } else {
            OutlinedButton(enabled = !busy && !readBusy && !storageError && clients.any { it.id != null },
                modifier = Modifier.fillMaxWidth().testTag("starlink-select"), onClick = { choosing = true }) { Text("اختيار جهاز للتجربة") }
            if (clients.isEmpty()) Text("ابدأ اختبار القراءة لعرض الأجهزة.")
            else if (clients.none { it.id != null }) Text("الرد لم يُرجع معرّفًا مناسبًا للتحكم. انسخ التشخيص؛ لن نعتمد على IP وحده.")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, modifier = Modifier.testTag("starlink-control-result"))
        if (diagnostic.isNotBlank()) TextButton(onClick = {
            clipboard.setText(AnnotatedString("Slotra ${com.example.BuildConfig.VERSION_NAME} · اختبار التحكم\n$diagnostic\n$message"))
        }) { Text("نسخ تشخيص التحكم") }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("اختيار الهاتف") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            clients.filter { it.id != null && (it.role == null || it.role == 0L || it.role == 1L) && it.ip.isNotBlank() }.forEach { device ->
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { selected = device; choosing = false }) {
                    Text("${device.name}\nID: ${device.id} · ${device.ip}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("رجوع") } })
    selected?.let { device -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("فحص ${device.name}") }, text = {
        Text("ID: ${device.id}\nIP: ${device.ip}\nالفحص يقرأ البيانات فقط. لو الهوية والصلاحيات مناسبة، يظهر تأكيد منفصل للإيقاف.")
    }, confirmButton = { TextButton(enabled = !busy && !readBusy, modifier = Modifier.testTag("starlink-prepare"), onClick = {
        selected = null
        execute { preview = control.prepare(device); acknowledged = false; message = "اكتمل فحص الهوية والإعدادات. صلاحية الكتابة لا تُثبت إلا عند التجربة." }
    }) { Text("فحص التحكم") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("إلغاء") } }) }
    preview?.let { prepared -> AlertDialog(onDismissRequest = { preview = null }, title = { Text(if (prepared.pause) "تأكيد إيقاف الإنترنت" else "تأكيد إعادة الإنترنت") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${prepared.pending.device.name}\nID: ${prepared.pending.device.id}\nIP: ${prepared.pending.device.ip}")
            Text(if (prepared.pause) "سيُرسل أمر تجريبي لإيقاف الإنترنت لهذا الجهاز. قد يظل متصلًا بالـWi-Fi. أعد الإنترنت بعد الاختبار من هنا أو من تطبيق Starlink."
                else "سيُزال فقط جدول الإيقاف الذي أضافه اختبار Slotra، مع الحفاظ على أي جداول أخرى.")
            Row { Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, modifier = Modifier.testTag("starlink-control-ack"))
                Text("تأكدت من الجهاز وأستطيع إلغاء الإيقاف من تطبيق Starlink.", modifier = Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton(enabled = acknowledged && !busy && !readBusy, modifier = Modifier.testTag("starlink-control-confirm"), onClick = {
        preview = null
        execute { val result = control.apply(prepared); message = result.message; diagnostic = result.diagnostic }
    }) { Text(if (prepared.pause) "إيقاف الجهاز المحدد" else "إعادة الإنترنت") } }, dismissButton = {
        TextButton(onClick = { preview = null }) { Text("إلغاء") }
    }) }
}
