package com.example.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainViewModel
import com.example.db.*
import com.example.domain.*
import com.example.notifications.SubscriptionAlarms
import com.example.service.ExpanderHealth
import com.example.service.TextExpanderService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

internal fun stamp(at: Long): String = SimpleDateFormat("dd/MM · hh:mm a", Locale.forLanguageTag("ar")).format(Date(at))
internal fun remaining(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + 59999) / 60000
    return "${minutes / 60} س ${minutes % 60} د"
}
internal fun amount(minor: Long): String {
    val number = java.text.NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 }
    return "${number.format(java.math.BigDecimal.valueOf(minor, 2))} ج.س"
}
@Composable internal fun Title(title: String, subtitle: String = "") {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, single: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = single, minLines = if (single) 1 else 3)
}
@Composable internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) })
}
@Composable internal fun Payment(value: String, change: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("كاش", value == "CASH") { change("CASH") }
        Choice("بنكك", value == "BANK") { change("BANK") }
    }
}
@Composable internal fun Form(title: String, dismiss: () -> Unit, save: () -> Unit, valid: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }, confirmButton = { Button(onClick = save, enabled = valid) { Text("حفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable fun ManagerApp(vm: MainViewModel, requestedSession: String?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newSession by rememberSaveable { mutableStateOf(false) }
    val plans by vm.plans.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val config by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { now = System.currentTimeMillis(); vm.refresh(); delay(15000) }
    } }
    LaunchedEffect(requestedSession) { if (requestedSession != null) tab = 1 }
    LaunchedEffect(message) { message?.let { host.showSnackbar(it); vm.message.value = null } }
    val labels = listOf("الرئيسية", "المشتركون", "الباقات", "اختصارات", "الإعدادات")
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.People, Icons.Default.LocalOffer, Icons.Default.Keyboard, Icons.Default.Settings)
    Scaffold(snackbarHost = { SnackbarHost(host) }, bottomBar = {
        NavigationBar { labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index,
            onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(label, maxLines = 1) }) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Dashboard(sessions, config ?: BusinessSettings(), now) { newSession = true }
                    1 -> SessionsScreen(sessions, now, requestedSession, busy, { newSession = true }, vm::change, vm::rename)
                    2 -> PlansScreen(plans, config?.premiumBps ?: 2500, busy, vm::savePlan)
                    3 -> ShortcutsScreen(shortcuts, plans, busy, vm::saveShortcut, vm::deleteShortcut)
                    4 -> SettingsScreen(vm, config, now)
                }
            }
        }
    }
    if (newSession) SessionForm(plans.filter { it.enabled }, { newSession = false }) { client, plan, payment ->
        vm.create(client, plan, payment); newSession = false; tab = 1
    }
}

@Composable private fun SessionForm(plans: List<Plan>, dismiss: () -> Unit, save: (String, Long, String) -> Unit) {
    var client by rememberSaveable { mutableStateOf("") }
    var planId by rememberSaveable { mutableStateOf(plans.firstOrNull()?.id) }
    var payment by rememberSaveable { mutableStateOf("CASH") }
    val plan = plans.find { it.id == planId }
    Form("تسجيل اشتراك", dismiss, { plan?.let { save(client.trim(), it.id, payment) } }, client.length <= 80 && plan != null) {
        Field("اسم المشترك · اختياري", client, { client = it })
        Text("إن تركته فارغًا سيُنشأ اسم برقم مميز، ويمكنك تسميته لاحقًا.")
        if (plans.isEmpty()) Text("أضف باقة أو فعّل باقة من تبويب الباقات أولًا.")
        plans.forEach { p -> Choice("${if (p.home) "✅ " else ""}${p.name} · ${p.minutes} دقيقة", p.id == planId) { planId = p.id } }
        if (plan?.home != true) Payment(payment) { payment = it }
        plan?.let { Text(if (it.home) "لأهل البيت · دون إيراد" else "سعر الاشتراك: ${amount(if (payment == "BANK") it.bank else it.cash)}") }
        Text("يبدأ الوقت عند الحفظ. ستتلقى تنبيهًا لتراجع الاتصال وتفصله يدويًا.")
    }
}

@Composable private fun SessionsScreen(sessions: List<Session>, now: Long, requested: String?, busy: Boolean, add: () -> Unit, change: (String, String) -> Unit, rename: (String, String) -> Unit) {
    var renaming by remember { mutableStateOf<Session?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var cancel by remember { mutableStateOf<Session?>(null) }
    val labels = listOf("ALL" to "الكل", "ACTIVE" to "نشط", "PAUSED" to "متوقف", "ENDED" to "منتهٍ", "CANCELLED" to "ملغي", "HOME" to "أهل البيت")
    val rows = sessions.filter { (filter == "ALL" || it.state == filter || filter == "HOME" && it.home) && (it.client.contains(search, true) || it.plan.contains(search, true)) }
        .sortedByDescending { it.id == requested }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("المشتركون", "كل اشتراك يحتفظ بسعره وشروطه وقت التسجيل") }
        item { Button(onClick = add, enabled = !busy) { Text("اشتراك جديد") } }
        item { Field("ابحث بالاسم أو الباقة", search, { search = it }) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEach { (id, title) -> Choice(title, filter == id) { filter = id } } } }
        if (rows.isEmpty()) item { Panel { Text("لا توجد اشتراكات هنا بعد. سجّل مشتركًا أو غيّر البحث.") } }
        items(rows, key = { it.id }) { s -> Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.home) Icons.Default.Home else Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                Text(s.client, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(enabled = !busy, onClick = { renaming = s }) { Icon(Icons.Default.Edit, "تسمية المشترك") }
            }
            Text("رقم الاشتراك #${s.reference.ifBlank { s.id.take(8) }}", style = MaterialTheme.typography.labelLarge)
            Text("${s.plan} · ${labels.firstOrNull { it.first == s.state }?.second.orEmpty()}")
            Text("البداية: ${stamp(s.started)}")
            if (s.state in listOf("ACTIVE", "PAUSED")) Text("المتبقّي ${remaining(Rules.remaining(s.clock(), now))}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (s.state == "ACTIVE") Text("النهاية: ${stamp(s.resumed + s.duration - s.served)}")
            Text(when {
                s.home -> "مجاني · لا يدخل في الإيراد"
                s.recognized > 0 -> "مثبّت: ${amount(s.amount)} ${if (s.payment == "BANK") "بنكك" else "كاش"}"
                s.state == "CANCELLED" -> "ألغي قبل التثبيت · دون إيراد"
                else -> "قيد التثبيت: ${amount(s.amount)} · بعد ${s.grace / 60000} دقيقة استخدام"
            })
            if (s.state in listOf("ACTIVE", "PAUSED")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { change(s.id, if (s.state == "ACTIVE") "PAUSE" else "RESUME") }) { Text(if (s.state == "ACTIVE") "إيقاف الوقت" else "استئناف") }
                TextButton(enabled = !busy, onClick = { cancel = s }) { Text("إنهاء مبكر") }
            }
            if (s.state == "ENDED") Text("انتهى الوقت؛ راجع فصل المشترك من الراوتر.", color = MaterialTheme.colorScheme.error)
        } }
    }
    renaming?.let { s -> RenameSessionForm(s, { renaming = null }) { rename(s.id, it); renaming = null } }
    cancel?.let { s -> AlertDialog(onDismissRequest = { cancel = null }, title = { Text("إنهاء اشتراك ${s.client}؟") }, text = {
        Text(if (s.recognized > 0 || !s.home && Rules.qualifies(s.clock(), now, s.grace, s.home)) "سيظل الإيراد المثبّت محفوظًا. افصل الإنترنت يدويًا إذا لزم." else "لن يُحتسب إيراد إن لم يصل الاستخدام لمهلة التثبيت عند التأكيد. افصل الإنترنت يدويًا إذا لزم.")
    }, confirmButton = { Button(onClick = { change(s.id, "CANCEL"); cancel = null }) { Text("إنهاء الاشتراك") } }, dismissButton = { TextButton(onClick = { cancel = null }) { Text("رجوع") } }) }
}

@Composable private fun PlansScreen(plans: List<Plan>, premium: Int, busy: Boolean, save: (Plan) -> Unit) {
    var editing by remember { mutableStateOf<Plan?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("باقاتك وأسعارك", "الأسعار الجديدة تطبق على الاشتراكات الجديدة فقط") }
        item { Button(enabled = !busy, onClick = { editing = Plan(name = "", minutes = 60, cash = 0, bank = 0) }) { Text("إضافة باقة") } }
        items(plans, key = { it.id }) { p -> Panel {
            Text("${if (p.home) "✅ " else ""}${p.name}", style = MaterialTheme.typography.titleLarge)
            DetailLine(Icons.Default.Timer, "المدة والحالة", "${p.minutes} دقيقة · ${if (p.enabled) "متاحة" else "متوقفة"}")
            if (p.home) DetailLine(Icons.Default.Home, "أهل البيت", "اشتراك مجاني · دون إيراد")
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { MoneyLine("السعر كاش", p.cash) }
                Column(Modifier.weight(1f)) { MoneyLine("السعر بنكك", p.bank) }
            }
            Row { TextButton(enabled = !busy, onClick = { editing = p }) { Text("تعديل") }; TextButton(enabled = !busy, onClick = { save(p.copy(enabled = !p.enabled)) }) { Text(if (p.enabled) "إيقاف الباقة" else "تفعيل الباقة") } }
        } }
    }
    editing?.let { p -> PlanForm(p, premium, { editing = null }) { save(it); editing = null } }
}

@Composable internal fun PlanForm(plan: Plan, premium: Int, dismiss: () -> Unit, save: (Plan) -> Unit) {
    var name by rememberSaveable { mutableStateOf(plan.name) }
    var minutes by rememberSaveable { mutableStateOf(plan.minutes.toString()) }
    var cash by rememberSaveable { mutableStateOf(Money.show(plan.cash)) }
    var bank by rememberSaveable { mutableStateOf(Money.show(plan.bank)) }
    var home by rememberSaveable { mutableStateOf(plan.home) }
    val m = Money.normalize(minutes).toIntOrNull()
    val c = Money.parse(cash); val b = Money.parse(bank)
    Form(if (plan.id == 0L) "باقة جديدة" else "تعديل الباقة", dismiss, {
        save(plan.copy(name = name.trim(), minutes = m!!, cash = if (home) 0 else c!!, bank = if (home) 0 else b!!, home = home))
    }, name.isNotBlank() && name.length <= 60 && m != null && m in 1..525600 && (home || c != null && b != null)) {
        Field("اسم الباقة", name, { name = it })
        Field("المدة بالدقائق", minutes, { minutes = it })
        Row(Modifier.fillMaxWidth().toggleable(home, role = Role.Checkbox, onValueChange = { home = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(home, null); Text("✅ أهل البيت · دون إيراد") }
        if (!home) {
            Field("السعر كاش بالجنيه", cash, { cash = it })
            Field("السعر بنكك بالجنيه", bank, { bank = it })
            TextButton(enabled = c != null, onClick = { bank = Money.show(Money.cashToBank(c!!, premium)) }) { Text("احسب بنكك بزيادة ${Money.show(premium.toLong())}٪") }
        }
    }
}

@Composable private fun SettingsScreen(vm: MainViewModel, config: BusinessSettings?, now: Long) {
    val context = LocalContext.current
    val bound by ExpanderHealth.connected.collectAsStateWithLifecycle()
    val selectedApps = remember(now) { ExpanderHealth.allowed(context) }
    var appsDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val component = ComponentName(context, TextExpanderService::class.java)
    val enabled = remember(now, bound) { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component } }
    fun open(intent: Intent) { try { context.startActivity(intent) } catch (_: Exception) { vm.message.value = "هذا الإعداد غير متاح هنا؛ افتحه من إعدادات الهاتف." } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("الإعدادات", "تحكّم في الحساب والتنبيهات واستمرارية الاختصارات") }
        item { Panel {
            SectionHeading(Icons.Default.VerifiedUser, "جاهزية التطبيق")
            Text("الاختصارات: ${if (bound) "الخدمة متصلة" else if (enabled) "مفعّلة؛ النظام لم يربط الخدمة حاليًا" else "تحتاج تفعيل إمكانية الوصول"}")
            Text("تطبيقات مسموحة: ${selectedApps.size}")
            Text("الإشعارات: ${if (SubscriptionAlarms.notificationsAllowed(context)) "مسموحة" else "غير مسموحة"}")
            Text("دقة الموعد: ${if (SubscriptionAlarms.exactAllowed(context)) "الإذن متاح" else "قد يتأخر التنبيه؛ فعّل المنبّهات الدقيقة"}")
            TextButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("إعدادات الاختصارات") }
            TextButton(onClick = { appsDialog = true }) { Text("اختيار التطبيقات المسموحة") }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("تفعيل الإشعارات") }
            if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("إذن المنبّهات الدقيقة") }
            Text("إذن إمكانية الوصول يستبدل النص في التطبيقات التي تختارها. لا نرسل النصوص إلى خادم، ولا نقرأ حقول كلمات المرور.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.BatteryChargingFull, "استمرارية الاختصارات")
            Text("في Honor: افتح تشغيل التطبيقات، عطّل الإدارة التلقائية لهذا التطبيق واسمح بالتشغيل في الخلفية. راجع أيضًا تحسين البطارية. تختلف أسماء الخيارات حسب الهاتف.")
            TextButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("إعدادات تحسين البطارية") }
            TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("معلومات التطبيق") }
            Text("أندرويد قد يوقف الخدمة أو يمنع التنبيهات بعد الإيقاف الإجباري. افتح التطبيق لإعادة الجدولة؛ تفعيل إمكانية الوصول يبقى بيدك.")
            context.getSharedPreferences("service_health", 0).getString("error", null)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }
        item { Panel {
            SectionHeading(Icons.Default.Calculate, "الحساب ودورة الاشتراك")
            Text("مهلة التثبيت: ${config?.graceMinutes ?: 30} دقيقة · زيادة بنكك: ${Money.show((config?.premiumBps ?: 2500).toLong())}٪")
            TextButton(enabled = !busy && config != null, onClick = { edit = true }) { Text("تعديل إعدادات الحساب") }
            Text("تغيير الأسعار أو النسبة لا يعيد تسعير السجلات السابقة. سجّل المصروفات بالقيمة المكافئة للكاش.")
        } }
        item { Panel {
            SectionHeading(Icons.Default.Save, "البيانات والتصدير")
            Text("${devices.size} من سجلات الأجهزة القديمة محفوظة. التصدير يشمل المشتركين والباقات والإعدادات والاختصارات والأجهزة.")
            TextButton(enabled = !busy, onClick = { export.launch("starlink-records-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.json") }) { Text("تصدير السجل JSON") }
            Text("التصدير للقراءة والأرشفة؛ استيراد النسخ لم يُضف بعد.", style = MaterialTheme.typography.bodySmall)
        } }
    }
    if (edit && config != null) AccountingForm(config, { edit = false }) { vm.saveSettings(it); edit = false }
    if (appsDialog) AppsForm({ appsDialog = false; vm.refresh() })
}

@Composable private fun AppsForm(dismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(ExpanderHealth.allowed(context)) }
    val apps = remember {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("تطبيقات الاختصارات") }, text = {
        LazyColumn { item { Text("فعّل فقط التطبيقات التي تريد استبدال النص داخلها.") }; items(apps, key = { it.first }) { (pkg, name) ->
            Row(Modifier.fillMaxWidth().toggleable(pkg in selected, role = Role.Checkbox, onValueChange = { selected = if (it) selected + pkg else selected - pkg }), verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in selected, null); Text(name) }
        } }
    }, confirmButton = { Button(onClick = { ExpanderHealth.preferences(context).edit().putStringSet("apps", selected).apply(); dismiss() }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable private fun AccountingForm(s: BusinessSettings, dismiss: () -> Unit, save: (BusinessSettings) -> Unit) {
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false } }
    var grace by rememberSaveable { mutableStateOf(s.graceMinutes.toString()) }
    var premium by rememberSaveable { mutableStateOf(Money.show(s.premiumBps.toLong())) }
    var usd by rememberSaveable { mutableStateOf(Money.show(s.usdCents)) }
    var rate by rememberSaveable { mutableStateOf(Money.show(s.bankRate)) }
    var expenses by rememberSaveable { mutableStateOf(Money.show(s.expenses)) }
    var start by rememberSaveable { mutableStateOf(if (s.cycleStart == 0L) "" else format.format(Date(s.cycleStart))) }
    var end by rememberSaveable { mutableStateOf(if (s.cycleEnd == 0L) "" else format.format(Date(s.cycleEnd - 1))) }
    fun date(value: String): Long? = try { val v = Money.normalize(value); if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(v)) null else format.parse(v)?.time } catch (_: Exception) { null }
    val begin = date(start); val last = date(end)
    val endExclusive = last?.let { Calendar.getInstance().apply { timeInMillis = it; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    val g = Money.normalize(grace).toIntOrNull(); val p = Money.parse(premium)
    val u = Money.parse(usd); val r = Money.parse(rate); val e = Money.parse(expenses)
    val valid = g != null && g in 0..1440 && p != null && p in 0..100000 && u != null && r != null && e != null &&
        (start.isBlank() && end.isBlank() || begin != null && begin > 0 && endExclusive != null && endExclusive > begin)
    Form("إعدادات الحساب", dismiss, {
        save(s.copy(graceMinutes = g!!, premiumBps = p!!.toInt(), usdCents = u!!, bankRate = r!!, expenses = e!!, cycleStart = begin ?: 0, cycleEnd = endExclusive ?: 0))
    }, valid) {
        Field("تثبيت سعر الباقة بعد كم دقيقة؟", grace, { grace = it })
        Field("زيادة بنكك ٪", premium, { premium = it })
        Field("اشتراك Starlink بالدولار", usd, { usd = it })
        Field("سعر الدولار بالجنيه في بنكك", rate, { rate = it })
        Field("مصروفات الدورة بقيمة الكاش", expenses, { expenses = it })
        Field("أول يوم · yyyy-MM-dd", start, { start = it })
        Field("آخر يوم شاملًا · yyyy-MM-dd", end, { end = it })
        Text("اترك التاريخين فارغين إن لم تبدأ دورة. مهلة التثبيت والنسبة يطبّقان على الاشتراكات الجديدة؛ بيانات الدورة تستخدم للتقرير الحالي.")
        if (!valid) Text("راجع المبالغ والتواريخ. المهلة 0–1440 دقيقة والنسبة 0–1000٪.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun RenameSessionForm(session: Session, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.client) }
    Form("تسمية المشترك", dismiss, { save(name.trim()) }, name.isNotBlank() && name.trim().length <= 80) {
        Text("رقم الاشتراك #${session.reference.ifBlank { session.id.take(8) }} يبقى ثابتًا. الاسم الجديد سيظهر في تنبيه الانتهاء.")
        Field("الاسم أو وصف الجهاز", name, { name = it })
    }
}
