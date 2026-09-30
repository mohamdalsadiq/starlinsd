package com.example.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.notifications.StatusPanel
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainViewModel
import com.example.data.DailyReconciliation
import com.example.data.DeviceAlerts
import com.example.data.DeviceRecovery
import com.example.data.DeviceSelection
import com.example.data.IpListStore
import com.example.data.IpLists
import com.example.data.SubscriptionRepository
import com.example.data.TrackedDevice
import com.example.db.*
import com.example.domain.*
import com.example.notifications.DeviceAlertsCoordinator
import com.example.notifications.SubscriptionAlarms
import com.example.service.ExpanderHealth
import com.example.service.TextExpanderService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

internal fun stamp(at: Long): String = SimpleDateFormat("dd/MM · hh:mm a", Locale.forLanguageTag("ar")).format(Date(at))
internal fun remaining(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
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
@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, single: Boolean = true, numeric: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = modifier, singleLine = single, minLines = if (single) 1 else 3, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
}
@Composable internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) })
}
@Composable internal fun Form(title: String, dismiss: () -> Unit, save: () -> Unit, valid: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(properties = DialogProperties(usePlatformDefaultWidth = false), onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }, confirmButton = { Button(onClick = save, enabled = valid) { Text("حفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable fun ManagerApp(vm: MainViewModel, requestedSession: String?, requestedConfirmation: String? = null, requestedRecovery: String? = null) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf("") }
    var newSession by rememberSaveable { mutableStateOf(false) }
    var bulk by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val stateHolder = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { vm.refresh(); delay(15000) }
    } }
    LaunchedEffect(requestedSession) { if (requestedSession != null) { tab = 1; detail = "" } }
    // Phase 3 confirmation tap-in (spec 21): opens the temporary confirmation detail.
    LaunchedEffect(requestedConfirmation) { if (requestedConfirmation != null) { tab = 4; detail = "تأكيد الأجهزة اليومية" } }
    LaunchedEffect(requestedRecovery) { if (requestedRecovery != null) { tab = 4; detail = "استعادة الاشتراكات" } }
    var requestedRecoveryState by remember { mutableStateOf(requestedRecovery) }
    LaunchedEffect(message) { message?.let { host.showSnackbar(it); vm.message.value = null } }
    BackHandler(detail.isNotBlank() || tab != 0) { if (detail.isNotBlank()) detail = "" else tab = 0 }
    val labels = listOf("الرئيسية", "المشتركون", "الديون", "الاختصارات", "المزيد")
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.People, Icons.Default.AccountBalanceWallet, Icons.Default.Keyboard, Icons.Default.MoreHoriz)
    Scaffold(snackbarHost = { SnackbarHost(host) }, bottomBar = {
        NavigationBar { labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index,
            onClick = { tab = index; detail = "" }, modifier = Modifier.testTag("nav-$index"), icon = { Icon(icons[index], null) }, label = { Text(label, maxLines = 1) }) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (detail.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detail = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                Text(detail, style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    stateHolder.SaveableStateProvider(if (detail.isNotBlank()) detail else "tab-$tab") {
                        when {
                            detail == "اختبار Starlink" -> StarlinkTestScreen()
                            detail == "إدارة الأجهزة" -> DevicesScreen(vm)
                            detail == "تأكيد الأجهزة اليومية" -> DeviceConfirmationScreen(vm)
                            detail == "استعادة الاشتراكات" -> RecoveryScreen(vm)
                            detail == "الإعدادات" -> {
                                val config by vm.settings.collectAsStateWithLifecycle()
                                val now by vm.clock.collectAsStateWithLifecycle()
                                SettingsScreen(vm, config, now)
                            }
                            detail == "الباقات والأسعار" -> {
                                val plans by vm.plans.collectAsStateWithLifecycle()
                                val config by vm.settings.collectAsStateWithLifecycle()
                                PlansScreen(plans, config?.premiumBps ?: 2500, busy, vm::savePlan)
                            }
                            detail == "التقارير" || tab == 0 || tab == 2 -> {
                                val snapshot by vm.financial.collectAsStateWithLifecycle()
                                val ready = snapshot
                                if (ready == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                else when {
                                    detail == "التقارير" -> ReportsScreen(ready, vm::correctRevenue)
                                    tab == 2 -> DebtsScreen(ready.data.debts, ready.data.payments, ready.budget, ready.day, busy, vm::saveDebt, vm::payDebt)
                                    else -> Dashboard(ready, { bulk = true }, { detail = "التقارير" }, vm::correctRevenue,
                                        live = { LiveOverview(vm) { tab = 1 } }) { newSession = true }
                                }
                            }
                            tab == 1 -> {
                                val sessions by vm.sessions.collectAsStateWithLifecycle()
                                val now by vm.clock.collectAsStateWithLifecycle()
                                val corrections by vm.corrections.collectAsStateWithLifecycle()
                                val paid = remember(sessions) { sessions.filterNot { it.home } }
                                SessionsScreen(paid, now, requestedSession, busy, { newSession = true }, vm::change, vm::rename, corrections)
                            }
                            tab == 3 -> {
                                val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
                                val plans by vm.plans.collectAsStateWithLifecycle()
                                ShortcutsScreen(shortcuts, plans, busy, vm::saveShortcut, vm::deleteShortcut)
                            }
                            else -> MoreScreen { detail = it }
                        }
                    }
                }
            }
        }
    }
    if (bulk) BulkSalesForm({ bulk = false }) { id, lines, payment -> vm.addSales(id, lines, payment); bulk = false }
    if (newSession) {
        val plans by vm.plans.collectAsStateWithLifecycle()
        NewSessionFlow(vm, plans.filter { it.enabled && !it.home }, { newSession = false }) { client, plan, payment, device ->
            vm.createWithDevice(client, plan, payment, device); newSession = false; tab = 1; detail = ""
        }
    }
}

/**
 * Device binding: reads live devices once (on demand), then applies the spec's three
 * cases without ever picking automatically: one free candidate = a suggestion the user
 * confirms; several = a picker list; none or a failed read = creation proceeds unbound.
 */
@Composable private fun NewSessionFlow(vm: MainViewModel, plans: List<Plan>, dismiss: () -> Unit, save: (String, Long, String, TrackedDevice?) -> Unit) {
    var stage by rememberSaveable { mutableIntStateOf(0) } // 0 = plan form, 1 = binding step
    var client by rememberSaveable { mutableStateOf("") }
    var planId by rememberSaveable { mutableStateOf(plans.firstOrNull()?.id) }
    var payment by rememberSaveable { mutableStateOf("CASH") }
    var device by remember { mutableStateOf<TrackedDevice?>(null) }
    var choices by remember { mutableStateOf<DeviceSelection.Result?>(null) }
    LaunchedEffect(stage) {
        if (stage == 1 && choices == null) choices = vm.bindingChoices()
    }
    if (stage == 0) SessionForm(plans, dismiss) { c, p, pay -> client = c; planId = p; payment = pay; stage = 1 }
    else Form("ربط الجهاز", dismiss, {
        val id = planId ?: return@Form
        save(client, id, payment, device)
    }, planId != null) {
        val result = choices
        when {
            result == null -> Text("تعذّر قراءة الأجهزة من الراوتر الآن؛ سيُنشأ الاشتراك بدون ربط جهاز.")
            result.unbound -> Text("لا يوجد جهاز مناسب غير مرتبط باشتراك نشط. سيُنشأ الاشتراك بدون ربط جهاز.")
            result.suggestion != null -> {
                val d = result.suggestion!!
                Text("يوجد جهاز واحد مناسب غير مرتبط باشتراك نشط:")
                DeviceRow(d)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(client, planId ?: return@Button, payment, d) }) { Text("ربط بهذا الجهاز") }
                    TextButton(onClick = { save(client, planId ?: return@TextButton, payment, null) }) { Text("بدون ربط") }
                }
            }
            else -> {
                Text("توجد عدة أجهزة مناسبة. اختر جهاز المشترك، أو احفظ بدون ربط:")
                result.options.forEach { d -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = device == d, onClick = { device = d })
                    DeviceRow(d)
                } }
            }
        }
    }
}

@Composable internal fun DeviceRow(d: TrackedDevice) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(d.name, fontWeight = FontWeight.Bold)
        Text("${d.ip} · ${d.mac} · معرّف ${d.clientId}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (d.category) {
            com.example.data.IpLists.Category.HOME -> "أهل البيت · غير متتبع"
            com.example.data.IpLists.Category.WATCH -> "قائمة المراقبة"
            com.example.data.IpLists.Category.UNKNOWN -> "غير مصنف"
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun LiveOverview(vm: MainViewModel, open: () -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now by vm.clock.collectAsStateWithLifecycle()
    val active = remember(sessions, now) { sessions.filter { !it.home && it.state == "ACTIVE" && Rules.remaining(it.clock(), now) > 0 } }
    val soon = active.count { Rules.remaining(it.clock(), now) <= 10 * Rules.MINUTE }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("المتابعة الآن · توقيت يدوي", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = open) { Text("المشتركون") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailMetric("نشط", active.size.toString(), Modifier.weight(1f))
            DetailMetric("ينتهي خلال 10 د", soon.toString(), Modifier.weight(1f))
            DetailMetric("متوقف", sessions.count { !it.home && it.state == "PAUSED" }.toString(), Modifier.weight(1f))
        }
    }
}
@Composable private fun DetailMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
/**
 * Live router clients + home/watch list management. Classification by IP; a HOME device
 * is never suggested for binding and never paused or resumed. The lists exist now;
 * watch-list notifications are a later feature by design.
 */
@Composable private fun DevicesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val homeIps by vm.homeIps.collectAsStateWithLifecycle()
    val watchIps by vm.watchIps.collectAsStateWithLifecycle()
    val identities by vm.identities.collectAsStateWithLifecycle()
    val scan by vm.deviceScanState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshDevices() }
    var newHome by rememberSaveable { mutableStateOf(false) }
    var newWatch by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("إدارة الأجهزة", "قراءة محلية من راوتر Starlink · بدون حساب سحابي") }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("الأجهزة المتصلة الآن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(scan?.let {
                            when {
                                it.failed -> "تعذّر الوصول للراوتر؛ لا تُوقف الاشتراكات عند فشل القراءة."
                                it.devices.isEmpty() -> "لا توجد سجلات أجهزة بمعرّف حاليًا."
                                else -> "حُدّث ${stamp(it.at)} · ${it.devices.size} جهاز"
                            }
                        } ?: "جاري التحديث…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !busy, onClick = { vm.refreshDevices() }) { Text("تحديث") }
                }
            }
        }
        items(scan?.devices.orEmpty(), key = { it.clientId }) { d ->
            Panel {
                DeviceRow(d)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Identity-based: records the stable clientId (MAC + last IP kept for
                    // display), so a DHCP IP change can never un-HOME the device.
                    TextButton(enabled = !busy, onClick = { vm.addHomeDevice(d) }) { Text("إضافة لأهل البيت") }
                    TextButton(enabled = !busy, onClick = { vm.addWatchDevice(d) }) { Text("إضافة للمراقبة") }
                }
            }
        }
        item { Panel {
            SectionHeading(Icons.Default.Home, "أهل البيت (بالهوية)", "مرتبطة بمعرّف الجهاز؛ تغيّر IP لا يُخرجها من القائمة")
            identities.filter { it.list == "HOME" }.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.name.ifBlank { "جهاز ${entry.deviceId}" }} · آخر عنوان ${entry.lastIp.ifBlank { "؟" }}", Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { vm.removeHomeDevice(entry.deviceId) }) { Text("حذف") }
                }
            }
            homeIps.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.ip}${if (entry.label.isBlank()) "" else " · ${entry.label}"} (IP قديم)", Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { vm.removeHomeIp(entry.ip) }) { Text("حذف") }
                }
            }
            if (identities.none { it.list == "HOME" } && homeIps.isEmpty()) Text("لا توجد أجهزة محفوظة بعد.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { newHome = true }) { Text("إضافة عنوان يدويًا (IP)") }
        } }
        item { Panel {
            SectionHeading(Icons.Default.Visibility, "قائمة المراقبة (بالهوية)", "مرتبطة بمعرّف الجهاز؛ إشعارها يعرض الاسم وآخر IP")
            identities.filter { it.list == "WATCH" }.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.name.ifBlank { "جهاز ${entry.deviceId}" }} · آخر عنوان ${entry.lastIp.ifBlank { "؟" }}", Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { vm.removeWatchDevice(entry.deviceId) }) { Text("حذف") }
                }
            }
            watchIps.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.ip}${if (entry.label.isBlank()) "" else " · ${entry.label}"} (IP قديم)", Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { vm.removeWatchIp(entry.ip) }) { Text("حذف") }
                }
            }
            if (identities.none { it.list == "WATCH" } && watchIps.isEmpty()) Text("لا توجد أجهزة محفوظة بعد.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { newWatch = true }) { Text("إضافة عنوان يدويًا (IP)") }
        } }
    }
    if (newHome) IpEntryForm("إضافة لأهل البيت", { newHome = false }) { ip, label -> vm.addHomeIp(ip, label); newHome = false }
    if (newWatch) IpEntryForm("إضافة للمراقبة", { newWatch = false }) { ip, label -> vm.addWatchIp(ip, label); newWatch = false }
}

@Composable private fun IpEntryForm(title: String, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    Form(title, dismiss, { save(ip.trim(), label) }, IpListStore.validate(ip)) {
        Field("العنوان · مثل 192.168.1.55", ip, { ip = it })
        Field("وصف · اختياري", label, { label = it })
        Text("الأولوية لأهل البيت؛ الجهاز المصنف أهل بيت لا يُتبع أبدًا حتى لو كان في القائمتين.")
    }
}

@Composable private fun MoreScreen(open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("المزيد", "أدوات مشروعك وإعدادات التطبيق") }
        item { Card(onClick = { open("إدارة الأجهزة") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.Devices, "إدارة الأجهزة", "الأجهزة الحية وقوائم أهل البيت والمراقبة") } } }
        item { Card(onClick = { open("استعادة الاشتراكات") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.Restore, "استعادة الاشتراكات", "إعادة ربط اشتراك قائم بجهازه بعد تغيير كلمة مرور الشبكة") } } }
        item { Card(onClick = { open("تأكيد الأجهزة اليومية") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.FactCheck, "تأكيد الأجهزة اليومية", "أجهزة اليوم المرتبطة وغير المرتبطة") } } }
        item { Card(onClick = { open("اختبار Starlink") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.Router, "اختبار Starlink", "قراءة الأجهزة وتجربة الإيقاف · محليًا") } } }
        item { Card(onClick = { open("الباقات والأسعار") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.LocalOffer, "الباقات والأسعار", "إدارة المدة والسعر وأهل البيت") } } }
        item { Card(onClick = { open("التقارير") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.BarChart, "التقارير", "الدخل والتغطية وسجل الأيام") } } }
        item { Card(onClick = { open("الإعدادات") }) { Column(Modifier.padding(16.dp)) { SectionHeading(Icons.Default.Settings, "الإعدادات", "النسخ الاحتياطي والصلاحيات والدورة") } } }
    }
}

@Composable private fun SessionForm(plans: List<Plan>, dismiss: () -> Unit, save: (String, Long, String) -> Unit) {
    var client by rememberSaveable { mutableStateOf("") }
    var planId by rememberSaveable { mutableStateOf(plans.firstOrNull()?.id) }
    LaunchedEffect(plans) { if (plans.none { it.id == planId }) planId = plans.firstOrNull()?.id }
    val plan = plans.find { it.id == planId }
    Form("تسجيل اشتراك", dismiss, { plan?.let { save(client.trim(), it.id, "CASH") } }, client.length <= 80 && plan != null) {
        Field("اسم المشترك · اختياري", client, { client = it })
        Text("إن تركته فارغًا سيُنشأ اسم برقم مميز، ويمكنك تسميته لاحقًا.")
        if (plans.isEmpty()) Text("أضف باقة أو فعّل باقة من المزيد ← الباقات والأسعار أولًا.")
        plans.forEach { p -> Choice("${if (p.home) "✅ " else ""}${p.name} · ${p.minutes} دقيقة", p.id == planId) { planId = p.id } }
        plan?.let { Text(if (it.home) "لأهل البيت · دون إيراد" else "سعر الاشتراك: ${amount(it.cash)}") }
        Text("يبدأ الوقت عند الحفظ. ستتلقى تنبيهًا لتراجع الاتصال وتفصله يدويًا.")
    }
}

@Composable private fun SessionsScreen(sessions: List<Session>, now: Long, requested: String?, busy: Boolean, add: () -> Unit, change: (String, String) -> Unit, rename: (String, String) -> Unit, corrections: List<RevenueCorrection>) {
    val ledger = remember(sessions, corrections) { Finance.ledger(sessions, emptyList(), corrections).associateBy { it.id } }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(if (requested == null) "ACTIVE" else "ALL") }
    LaunchedEffect(requested) { if (requested != null) { filter = "ALL"; search = "" } }
    var cancel by remember { mutableStateOf<Session?>(null) }
    val labels = listOf("ALL" to "الكل", "ACTIVE" to "نشط", "SOON" to "قارب الانتهاء", "PAUSED" to "متوقف", "ENDED" to "منتهٍ", "CANCELLED" to "ملغي")
    val rows = remember(sessions, search, filter, requested, if (filter == "SOON") now else 0L) {
        SessionSearch.filter(sessions, search, filter, requested, now)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("المشتركون", "توقيت يدوي · أوقف الوقت عند المغادرة واستأنفه عند العودة") }
        item { Button(onClick = add, enabled = !busy) { Text("اشتراك جديد") } }
        item { Field("ابحث بالاسم أو الرقم أو الباقة", search, { search = it }) }
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
            var expanded by rememberSaveable(s.id) { mutableStateOf(false) }
            if (expanded) Text("البداية: ${stamp(s.started)}")
            if (expanded && s.deviceClientId != null) Text("الجهاز: ${s.deviceName.ifBlank { "بدون اسم" }} · ${s.deviceIp.ifBlank { "IP غير معروف" }} · معرّف ${s.deviceClientId}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.state in listOf("ACTIVE", "PAUSED")) {
                // Tick only this timer, without rebuilding the financial history every second.
                val timerNow by produceState(now, s, now) {
                    if (s.state == "ACTIVE") while (true) { value = System.currentTimeMillis(); delay(1000) }
                }
                Text("الوقت المتبقي", style = MaterialTheme.typography.labelLarge)
                Text(remaining(Rules.remaining(s.clock(), timerNow)), Modifier.testTag("timer-${s.id}"),
                    style = MaterialTheme.typography.headlineLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                // Phase 4 (spec 6): END TIME is the primary value the operator sees,
                // always reflecting the real clock (a pause shifts it; resume re-extends).
                Text("النهاية: ${stamp(s.resumed + s.duration - s.served)}", Modifier.testTag("endtime-${s.id}"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (expanded && s.state == "ACTIVE") Text("النهاية: ${stamp(s.resumed + s.duration - s.served)}")
            val financial = ledger["session:${s.id}"]
            Text(when {
                financial?.voided == true -> "إيراد مستبعد من الحساب · المؤقت مستمر حتى نهايته"
                s.home -> "مجاني · لا يدخل في الإيراد"
                s.recognized > 0 -> "مثبّت: ${amount(financial?.value ?: s.cashEquivalent)}"
                s.state == "CANCELLED" -> "ألغي قبل التثبيت · دون إيراد"
                else -> "مدفوع · قيد الاعتماد: ${amount(s.cashEquivalent)} · بعد ${s.grace / 60000} دقائق محتسبة"
            })
            if (s.state in listOf("ACTIVE", "PAUSED")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { change(s.id, if (s.state == "ACTIVE") "PAUSE" else "RESUME") }) {
                    Icon(if (s.state == "ACTIVE") Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp)); Text(if (s.state == "ACTIVE") "إيقاف الوقت" else "استئناف")
                }
                TextButton(enabled = !busy, onClick = { cancel = s }) { Text("إنهاء مبكر") }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "إخفاء التفاصيل" else "تفاصيل الاشتراك") }
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
            DetailLine(Icons.Default.Timer, "المدة والحالة", "${if (p.home) "نص فقط · بلا مؤقت" else "${p.minutes} دقيقة"} · ${if (p.enabled) "متاحة" else "متوقفة"}")
            if (p.home) DetailLine(Icons.Default.Home, "أهل البيت", "اختصار نصي فقط · دون رقم أو تنبيه أو إيراد")
            else MoneyLine("سعر الاشتراك", p.cash)
            Row { TextButton(enabled = !busy, onClick = { editing = p }) { Text("تعديل") }; TextButton(enabled = !busy, onClick = { save(p.copy(enabled = !p.enabled)) }) { Text(if (p.enabled) "إيقاف الباقة" else "تفعيل الباقة") } }
        } }
    }
    editing?.let { p -> PlanForm(p, premium, { editing = null }) { save(it); editing = null } }
}

@Composable internal fun PlanForm(plan: Plan, premium: Int, dismiss: () -> Unit, save: (Plan) -> Unit) {
    var name by rememberSaveable { mutableStateOf(plan.name) }
    var minutes by rememberSaveable { mutableStateOf(plan.minutes.toString()) }
    var cash by rememberSaveable { mutableStateOf(Money.show(plan.cash)) }
    var home by rememberSaveable { mutableStateOf(plan.home) }
    val m = Money.normalize(minutes).toIntOrNull()
    val c = Money.parse(cash)
    Form(if (plan.id == 0L) "باقة جديدة" else "تعديل الباقة", dismiss, {
        save(plan.copy(name = name.trim(), minutes = if (home) 1 else m!!, cash = if (home) 0 else c!!, bank = if (home) 0 else if (plan.id != 0L) plan.bank else Money.cashToBank(c!!, premium), home = home))
    }, name.isNotBlank() && name.length <= 60 && (home || m != null && m in 1..525600) && (home || c != null)) {
        Field("اسم الباقة", name, { name = it })
        if (!home) Field("المدة بالدقائق", minutes, { minutes = it }, numeric = true)
        Row(Modifier.fillMaxWidth().toggleable(home, role = Role.Checkbox, onValueChange = { home = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(home, null); Text("أهل البيت · اختصار فقط") }
        if (!home) {
            Field("السعر بالجنيه السوداني", cash, { cash = it }, numeric = true)
        }
    }
}/**
 * Daily device reconciliation screen (device-identity-reconciliation-v1). The
 * operator picks one event day and sees the day's recognized devices split into
 * A) subscribed (bound ACTIVE/PAUSED/ENDED with its registered amount),
 * B) appeared without a subscription (proposed/uncertain),
 * C) HOME (identity-based; never enters the money),
 * D) WATCH (separate status; never auto-revenue).
 * Each A/B device has ONE editable confirmed amount; saving upserts the
 * (dayKey, deviceId) row and rebuilds the day's single ledger row — idempotent,
 * never a duplicate revenue record. Identity is clientId; IP is display-only.
 */
@Composable
private fun DeviceConfirmationScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val now by vm.clock.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<List<DeviceAlerts.DayDevice>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<com.example.db.Session>>(emptyList()) }
    var dayKey by rememberSaveable { mutableStateOf(DeviceAlerts.dayKey(System.currentTimeMillis())) }
    var amounts by remember(dayKey, sessions, devices) {
        mutableStateOf<Map<Long, String>>(emptyMap())
    }
    var payment by rememberSaveable(dayKey) { mutableStateOf("CASH") }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(now, dayKey) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            devices = DeviceAlertsCoordinator.historyFor(context, dayKey)
            sessions = com.example.db.AppDatabase.getDatabase(context).businessDao().sessions()
        }
    }
    // Subscription chain: device.clientId → deviceClientId → session (ACTIVE/PAUSED/ENDED).
    val subscribed = remember(devices, sessions) {
        devices.mapNotNull { device ->
            sessions.firstOrNull { !it.home && it.deviceClientId == device.clientId && it.state in listOf("ACTIVE", "PAUSED", "ENDED") }
                ?.let { device to it }
        }
    }
    val subscribedIds = remember(subscribed) { subscribed.map { it.first.clientId }.toSet() }
    val others = remember(devices, subscribedIds) { devices.filter { it.clientId !in subscribedIds } }
    val home = remember(others) { others.filter { it.category == IpLists.Category.HOME } }
    val watch = remember(others) { others.filter { it.category == IpLists.Category.WATCH } }
    val unregistered = remember(others, home, watch) { others.filter { it.category == IpLists.Category.UNKNOWN } }

    val confirmedRows by vm.manualSales.collectAsStateWithLifecycle()
    val dayLedger = remember(confirmedRows, dayKey) {
        DailyReconciliation.rowsFor(confirmedRows, dayKey).firstOrNull { it.id == DailyReconciliation.dailyRowId(dayKey) }
    }
    val busy by vm.busy.collectAsStateWithLifecycle()

    // Live totals over the operator's edits (المطلوب 7): count, registered, confirmed,
    // additions on unregistered, and the final confirmed total — computed per device.
    val registeredTotal = remember(subscribed) { subscribed.sumOf { it.second.amount } }
    fun confirmedOf(device: DeviceAlerts.DayDevice): Long {
        val typed = amounts[device.clientId]?.let { Money.parse(it) }
        if (typed != null) return typed
        val session = sessions.firstOrNull { !it.home && it.deviceClientId == device.clientId && it.state in listOf("ACTIVE", "PAUSED", "ENDED") }
        return session?.amount ?: 0L
    }
    val confirmedSubscribed = remember(amounts, subscribed) { subscribed.sumOf { confirmedOf(it.first) } }
    val confirmedUnregistered = remember(amounts, unregistered) { unregistered.sumOf { confirmedOf(it) } }
    val confirmedCount = remember(amounts, devices) { devices.count { confirmedOf(it) > 0 } }
    val grandTotal = confirmedSubscribed + confirmedUnregistered

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("تأكيد الأجهزة اليومية", "مطابقة أجهزة اليوم باشتراكاتها · المبلغ النهائي من تعديلك لكل جهاز") }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("يوم المراجعة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dayLabel(dayKey), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { showPicker = true }) { Text("اختيار يوم") }
            }
            Text("الأجهزة المُعرف عليها اليوم: ${devices.size}", style = MaterialTheme.typography.bodyMedium)
        } }
        item { Panel {
            SectionHeading(Icons.Default.CheckCircle, "أ) أجهزة لديها اشتراك مسجل", "المبلغ المسجل من الاشتراك نفسه؛ عدّله للمبلغ المدفوع فعليًا")
            if (subscribed.isEmpty()) Text("لا يوجد")
            subscribed.forEach { (device, session) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("· ${device.name.ifBlank { "جهاز ${device.clientId}" }} · ${session.client} #${session.reference.ifBlank { "?" }}")
                    Text("آخر عنوان: ${device.ip} · المسجل: ${amount(session.amount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("المدفوع فعليًا", amounts[device.clientId] ?: Money.show(session.amount), { value ->
                        amounts = amounts + (device.clientId to value)
                    }, numeric = true)
                }
            }
            MoneyLine("إجمالي المسجل (أ)", registeredTotal)
        } }
        item { Panel {
            SectionHeading(Icons.Default.HelpOutline, "ب) أجهزة ظهرت بدون اشتراك", "أدخل المبلغ الفعلي لكل جهاز أو اتركه صفرًا")
            if (unregistered.isEmpty()) Text("لا يوجد")
            unregistered.forEach { device ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("· ${device.name.ifBlank { "جهاز ${device.clientId}" }}")
                    Text("آخر عنوان: ${device.ip}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("المدفوع فعليًا", amounts[device.clientId] ?: "0", { value ->
                        amounts = amounts + (device.clientId to value)
                    }, numeric = true)
                }
            }
            MoneyLine("إجمالي غير المؤكد (ب)", confirmedUnregistered)
        } }
        item { Panel {
            SectionHeading(Icons.Default.Home, "ج) أهل البيت (HOME)", "لا تدخل في الإيراد ولا في التأكيد المالي إطلاقًا")
            if (home.isEmpty()) Text("لا يوجد")
            home.forEach { Text("· ${it.name.ifBlank { "جهاز ${it.clientId}" }} (${it.ip})") }
        } }
        item { Panel {
            SectionHeading(Icons.Default.Visibility, "د) قائمة المراقبة (WATCH)", "حالة منفصلة؛ لا تتحول تلقائيًا إلى إيراد")
            if (watch.isEmpty()) Text("لا يوجد")
            watch.forEach { Text("· ${it.name.ifBlank { "جهاز ${it.clientId}" }} (${it.ip})") }
        } }
        item { Panel {
            SectionHeading(Icons.Default.Calculate, "الملخص المالي لليوم", "حسب تعديلاتك أعلاه، لكل جهاز على حدة")
            Text("الأجهزة المؤكدة: $confirmedCount")
            Text("إجمالي المسجل: ${amount(registeredTotal)}")
            Text("إجمالي المدفوع فعليًا: ${amount(grandTotal)}")
            Text("إضافات أجهزة غير مشتركة: ${amount(confirmedUnregistered)}")
            if (dayLedger != null) Text("محفوظ مسبقًا لهذا اليوم: ${amount(dayLedger.amount)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("كاش", payment == "CASH") { payment = "CASH" }
                Choice("بنكك", payment == "BANK") { payment = "BANK" }
            }
            Button(enabled = !busy && devices.isNotEmpty(), onClick = {
                val entries = subscribed.map { (device, session) ->
                    SubscriptionRepository.DailyDeviceAmount(device.clientId, session.id, confirmedOf(device))
                } + unregistered.map { device ->
                    SubscriptionRepository.DailyDeviceAmount(device.clientId, "", confirmedOf(device))
                }
                vm.confirmDailyDevices(dayKey, entries, payment)
            }) { Text("حفظ وتأكيد إيراد اليوم") }
            Text("الحفظ يكتب سطرًا واحدًا لكل جهاز بنفس اليوم والمعرّف: إعادة الفتح أو التعديل يحدّث السجل نفسه ولا يضيف إيرادًا مكررًا.", style = MaterialTheme.typography.bodySmall)
        } }
    }
    if (showPicker) DayPickerDialog(dayKey) { picked -> dayKey = picked; showPicker = false }
}

/**
 * Phase 4 password-change recovery screen (§40 report point J): a short list of
 * active/paused subscriptions whose device disappeared, each showing name +
 * "...<last octets>" + actual end time; the operator picks a live device from
 * the last successful snapshot. No auto-pick by IP (spec 28); relink keeps the
 * remaining time and never creates a subscription, time, or revenue.
 */
@Composable
private fun RecoveryScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var board by remember { mutableStateOf<DeviceRecovery.Board?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf<DeviceRecovery.Candidate?>(null) }
    var options by remember { mutableStateOf<List<DeviceRecovery.Option>>(emptyList()) }
    var selectedOption by remember { mutableStateOf<DeviceRecovery.Option?>(null) }

    suspend fun load() {
        loading = true; error = null
        try { board = vm.recoveryBoard() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "تعذّر قراءة الأجهزة"; board = null }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("استعادة الاشتراكات", "بعد تغيير كلمة مرور الشبكة: أعد ربط الاشتراك القائم بجهازه دون إنشاء اشتراك جديد") }
        item { Panel {
            when {
                loading -> Text("جاري قراءة الأجهزة من الراوتر…")
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                board != null && !board!!.snapshotOk -> Text("تعذّر الوصول للراوتر؛ لا تُقترح أي استعادة قبل نجاح القراءة.", color = MaterialTheme.colorScheme.error)
                board != null && board!!.candidates.isEmpty() -> Text("لا توجد اشتراكات بحاجة لاستعادة: كل الأجهزة المرتبطة ظاهرة في آخر قراءة.")
                board != null -> Text("اشتراكات أجهزتها غير ظاهرة بعد تغيير كلمة المرور: ${board!!.candidates.size}")
            }
            TextButton(enabled = !loading && !busy, onClick = { scope.launch { load() } }) { Text("تحديث") }
        } }
        val current = board
        if (current != null) items(current.candidates, key = { it.sessionId }) { c ->
            Panel {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("…${DeviceRecovery.ipTail(c.ip)} · ${if (c.state == "PAUSED") "موقوف مؤقتًا" else "نشط"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ينتهي: ${stamp(c.endAt)} · متبقٍ ${remaining(c.remaining)}", style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !busy && current.options.isNotEmpty(), onClick = {
                    pairing = c; options = current.options
                }) { Text("إعادة الربط بجهاز") }
            }
        }
        if (current != null && current.candidates.isNotEmpty() && current.options.isEmpty() && !loading) {
            item { Panel { Text("لا توجد أجهزة حية مرشحة الآن. تأكد أن الأجهزة أعادت الاتصال بالشبكة ثم اضغط تحديث.", color = MaterialTheme.colorScheme.error) } } }
    }
    pairing?.let { candidate ->
        AlertDialog(onDismissRequest = { pairing = null; selectedOption = null }, title = { Text("اختر جهاز ${candidate.name}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الأجهزة الحية المتاحة (الاسم · آخر مقاطع العنوان):", style = MaterialTheme.typography.bodySmall)
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedOption == option, onClick = { selectedOption = option })
                        Text("${option.name.ifBlank { "جهاز ${option.clientId}" }} · …${DeviceRecovery.ipTail(option.ip)}")
                    }
                }
                Text("الاستعادة تحافظ على الوقت المتبقي والسعر ولا تنشئ اشتراكًا أو إيرادًا جديدًا.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(enabled = selectedOption != null && !busy, onClick = {
            val option = selectedOption!!
            val device = TrackedDevice(option.clientId, "", option.ip, option.mac, com.example.data.IpLists.Category.UNKNOWN, null)
            vm.relinkDevice(candidate.sessionId, device, deviceIsLive = true)
            pairing = null; selectedOption = null
        }) { Text("تأكيد الاستعادة") } }, dismissButton = { TextButton(onClick = { pairing = null; selectedOption = null }) { Text("إلغاء") } })
    }
}

private fun dayLabel(dayKey: String): String = try {
    SimpleDateFormat("EEEE، d MMMM yyyy", Locale.forLanguageTag("ar")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)!!)
} catch (_: Exception) { dayKey }

@Composable
private fun DayPickerDialog(current: String, onPick: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    val valid = Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)
    Form("اختيار يوم المراجعة", { onPick(current) }, { onPick(value) }, valid) {
        Field("اليوم · yyyy-MM-dd", value, { value = it })
        Text("الافتراضي اليوم. الأيام السابقة تبقى قابلة للتأكيد ضمن نافذة الاحتفاظ.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, config: BusinessSettings?, now: Long) {
    val context = LocalContext.current
    val bound by ExpanderHealth.connected.collectAsStateWithLifecycle()
    val selectedApps = remember(now) { ExpanderHealth.allowed(context) }
    var panelEnabled by remember { mutableStateOf(StatusPanel.enabled(context)) }
    var appsDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val importing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewRestore) }
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val backupStatus by vm.backupStatus.collectAsStateWithLifecycle()
    val finance by vm.financial.collectAsStateWithLifecycle()
    var balanceForm by rememberSaveable { mutableStateOf(false) }
    val component = ComponentName(context, TextExpanderService::class.java)
    val enabled = remember(now, bound) { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component } }
    fun open(intent: Intent) { try { context.startActivity(intent) } catch (_: Exception) { vm.message.value = "هذا الإعداد غير متاح هنا؛ افتحه من إعدادات الهاتف." } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("الإعدادات", "تحكّم في الحساب والتنبيهات واستمرارية الاختصارات") }
        item { Panel {
            SectionHeading(Icons.Default.AccountBalanceWallet, "الرصيد الموجود", "مطابقة الكاش وبنكك وإعادة حساب خطة الفاتورة")
            finance?.balance?.let { balance ->
                MoneyLine("الكاش الموجود حسب آخر تحديث والتحصيلات", balance.funds.cash)
                MoneyLine("رصيد بنكك", balance.funds.bank)
                finance?.availableBalanceSurplus?.let { MoneyLine("فائض الرصيد بعد تكلفة الدورة", it) }
                Text("آخر مطابقة: ${stamp(balance.update.at)} · ${balance.update.reason}", style = MaterialTheme.typography.bodySmall)
                Text(balanceDifference("الكاش عند آخر تحديث", balance.update.cash - balance.update.expectedCash), style = MaterialTheme.typography.bodySmall)
                Text(balanceDifference("بنكك عند آخر تحديث", balance.update.bank - balance.update.expectedBank), style = MaterialTheme.typography.bodySmall)
            } ?: Text("أدخل إجمالي الموجود من حصيلة الشهر ليحل محل الرصيد المحسوب ويعيد حساب المطلوب يوميًا.")
            Button(enabled = !busy && finance != null, onClick = { balanceForm = true }, modifier = Modifier.testTag("update-balance")) { Text("تحديث الكاش وبنكك") }
            Text("بعد أي سحب أو مصروف أو ردّ مبلغ، حدّث الموجود هنا. التحصيلات الجديدة تضاف مرة واحدة؛ تصحيح الإيراد أو إلغاؤه لا يسجّل حركة نقدية.", style = MaterialTheme.typography.bodySmall)
            finance?.data?.balanceUpdates?.takeLast(3)?.asReversed()?.forEach {
                Text("${stamp(it.at)} · ${it.reason} · كاش ${amount(it.cash)} · بنكك ${amount(it.bank)}", style = MaterialTheme.typography.bodySmall)
            }
        } }
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
            SectionHeading(Icons.Default.NotificationsActive, "لوحة المتابعة في الستارة")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("إظهار اللوحة الصامتة", Modifier.weight(1f))
                Switch(checked = panelEnabled, onCheckedChange = { panelEnabled = it; StatusPanel.setEnabled(context, it); vm.refresh() })
            }
            Text("النشطون، القريبون من الانتهاء خلال 10 دقائق، المنتهون بالسجل، إيراد اليوم والمتبقي للفاتورة وربح الدورة. أهل البيت خارج العدّ.")
            Text("تتحدث عند تغيّر السجلات والمواعيد وبداية اليوم، مع زر تحديث يدوي. وسّع الإشعار لرؤية المبالغ؛ التفاصيل مخفية على شاشة القفل.", style = MaterialTheme.typography.bodySmall)
            if (panelEnabled && !StatusPanel.allowed(context)) Text("إشعارات اللوحة غير مسموحة؛ فعّلها من إعدادات الهاتف.", color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(enabled = panelEnabled, onClick = vm::refresh) { Text("إظهار / تحديث اللوحة") }
                TextButton(onClick = { open(if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, StatusPanel.CHANNEL) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("إعدادات اللوحة") }
            }
            Text("اللوحة مستقلة عن تنبيهات انتهاء الوقت. قد يسمح Android بسحبها؛ أعد إظهارها من هنا. الإيقاف الإجباري وقيود البطارية قد يؤخران التحديث.", style = MaterialTheme.typography.bodySmall)
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
            Text("مهلة التثبيت: ${Rules.RECOGNITION_MINUTES} دقيقة")
            TextButton(enabled = !busy && config != null, onClick = { edit = true }) { Text("تعديل إعدادات الحساب") }
            Text("تغيير الأسعار أو النسبة لا يعيد تسعير السجلات السابقة. سجّل المصروفات بالقيمة المكافئة للكاش.")
        } }
        item { Panel {
            SectionHeading(Icons.Default.Lock, "إغلاق الشبكة اليومي", "ينهي كل الاشتراكات النشطة تلقائيًا في وقت محدد")
            // Re-reads on every clock tick (and right after a save, since work{} bumps `now`
            // immediately) so the field reflects what's actually saved, not just local typing.
            val savedMinute = remember(now) { SubscriptionAlarms.dailyCloseMinute(context) }
            var closeTime by rememberSaveable(savedMinute) { mutableStateOf(if (savedMinute < 0) "" else String.format(Locale.ROOT, "%02d:%02d", savedMinute / 60, savedMinute % 60)) }
            Field("وقت الإغلاق · HH:mm · اتركه فارغًا للإلغاء", closeTime, { closeTime = it })
            val parsedMinute = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(Money.normalize(closeTime))
                ?.let { m -> m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
            val validClose = closeTime.isBlank() || parsedMinute != null
            Button(enabled = !busy && validClose, onClick = { vm.setDailyClose(if (closeTime.isBlank()) -1 else parsedMinute!!) }) { Text("حفظ وقت الإغلاق") }
            if (!validClose) Text("اكتب الوقت بصيغة HH:mm، مثل 18:00", color = MaterialTheme.colorScheme.error)
            Text("عند هذا الوقت تُنهى كل الاشتراكات النشطة والمتوقفة مؤقتًا فورًا، ويُحتسب إيرادها كاملًا حتى لو لم تكتمل مهلة التثبيت (${Rules.RECOGNITION_MINUTES} دقائق). أرقام اليوم لا تتاح لغيرها حتى بعد الإغلاق.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.DevicesOther, "تنبيهات الأجهزة", "تنبيه عند ظهور جهاز غير مرتبط، وملخص يومي")
            val delay = remember(now) { DeviceAlertsCoordinator.knownDelayMinutes(context) }
            val summaryMin = remember(now) { DeviceAlertsCoordinator.knownSummaryMinute(context) }
            Text("تنبيه جهاز غير معروف: بعد $delay دقيقة من ظهوره")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5).forEach { option ->
                    Choice("$option دقائق", delay == option) { if (delay != option) vm.setDeviceAlertDelay(option) }
                }
            }
            Text("ملخص تأكيد الأجهزة اليومي: ${String.format(Locale.ROOT, "%02d:%02d", summaryMin / 60, summaryMin % 60)}")
            var summaryTime by rememberSaveable(summaryMin) { mutableStateOf(String.format(Locale.ROOT, "%02d:%02d", summaryMin / 60, summaryMin % 60)) }
            Field("وقت الملخص اليومي · HH:mm", summaryTime, { summaryTime = it })
            val parsedSummary = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(Money.normalize(summaryTime))
                ?.let { m -> m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
            Button(enabled = !busy && parsedSummary != null, onClick = { vm.setDeviceSummaryTime(parsedSummary!!) }) { Text("حفظ وقت الملخص") }
            if (parsedSummary == null) Text("اكتب الوقت بصيغة HH:mm، مثل 22:00", color = MaterialTheme.colorScheme.error)
            Text("التنبيهات محلية داخل الهاتف: لا يُرسل أي بيانات جهاز إلى أي خدمة خارجية.", style = MaterialTheme.typography.bodySmall)
            val recoveryKeyword by vm.recoveryKeyword.collectAsStateWithLifecycle()
            var recoveryField by remember(recoveryKeyword) { mutableStateOf(recoveryKeyword) }
            Text("اختصار استعادة الاشتراكات (بعد تغيير كلمة المرور): $recoveryKeyword")
            Field("كلمة استعادة الاشتراكات", recoveryField, { recoveryField = it })
            Button(enabled = !busy && recoveryField.trim() != recoveryKeyword && recoveryField.isNotBlank(), onClick = { vm.setRecoveryKeyword(recoveryField) }) { Text("حفظ كلمة الاستعادة") }
            Text("اكتبها ثم مسافة في أي تطبيق مسموح لفتح شاشة الاستعادة. الاستعادة تعيد ربط اشتراك قائم بجهازه ولا تنشئ اشتراكًا جديدًا.", style = MaterialTheme.typography.bodySmall)
            val signing = remember(now) { vm.debugSigningInfo() }
            signing?.let { info ->
                Text("بصمة توقيع النسخة الحالية: ${info.take(16)}…", style = MaterialTheme.typography.bodySmall)
                Text("تثبيت نسخة أعلى القديمة يتطلب نفس البصمة؛ التغيير يفرض حذف التطبيق وفقدان البيانات.", style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { Panel {
            SectionHeading(Icons.Default.CloudDone, "النسخ الاحتياطي والاستعادة")
            Text("${devices.size} من سجلات الأجهزة القديمة محفوظة. التصدير يشمل المشتركين والباقات والإعدادات والاختصارات والأجهزة.")
            TextButton(enabled = !busy, onClick = { export.launch("slotra-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.json") }) { Text("حفظ نسخة · Google Drive أو ملف") }
            OutlinedButton(enabled = !busy, onClick = { importing.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("استعادة نسخة محفوظة") }
            Text(backupStatus, style = MaterialTheme.typography.bodySmall)
            Text("اختر Google Drive من قائمة مواقع الحفظ إن كان مثبّتًا. احفظ نسخة بعد عملك؛ نقلها لهاتف آخر يتم باختيار الملف نفسه. النسخة ملف خاص بك، ولا تشاركه علنًا.", style = MaterialTheme.typography.bodySmall)
            Text("نسخ Android التلقائي مفعّل ويعتمد على إعدادات حساب Google والنظام. لا يغني عن حفظ ملف يمكنك استعادته بنفسك.", style = MaterialTheme.typography.bodySmall)
        } }
    }
    pendingRestore?.let { preview -> AlertDialog(onDismissRequest = vm::dismissRestore,
        title = { Text("استعادة هذه النسخة؟") }, text = { Text("${preview.summary}\nحُفظت: ${stamp(preview.exportedAt)}\nستستبدل السجل الحالي بالكامل. احفظ نسخة منه أولًا إن أردت الاحتفاظ به.") },
        confirmButton = { Button(enabled = !busy, onClick = vm::confirmRestore) { Text("استبدال واستعادة") } },
        dismissButton = { TextButton(onClick = vm::dismissRestore) { Text("إلغاء") } }) }
    if (balanceForm) finance?.let { snapshot ->
        BalanceForm(snapshot, { balanceForm = false }) { cash, bank, reason -> vm.updateBalance(cash, bank, reason); balanceForm = false }
    }
    if (edit && config != null) AccountingForm(config, { edit = false }) { vm.saveSettings(it); edit = false }
    if (appsDialog) AppsForm({ appsDialog = false; vm.refresh() })
}

@Composable private fun AppsForm(dismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(ExpanderHealth.allowed(context)) }
    var search by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<Pair<String, String>>>?>(null, context, retry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                    .distinctBy { it.first }.sortedBy { it.second })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    val apps = remember(result, search) { result?.getOrNull().orEmpty().filter { it.second.contains(search, true) || it.first.contains(search, true) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("تطبيقات الاختصارات") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("ابحث عن تطبيق", search, { search = it })
            Text("محدد: ${selected.size} · تعمل الاختصارات داخل التطبيقات المحددة فقط.", style = MaterialTheme.typography.bodySmall)
            if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result?.isFailure == true) TextButton(onClick = { retry++ }) { Text("تعذر تحميل التطبيقات · إعادة المحاولة") }
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(apps, key = { it.first }) { (pkg, name) ->
                Row(Modifier.fillMaxWidth().toggleable(pkg in selected, role = Role.Checkbox, onValueChange = { selected = if (it) selected + pkg else selected - pkg }), verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in selected, null); Text(name) }
            } }
        }
    }, confirmButton = { Button(onClick = { ExpanderHealth.preferences(context).edit().putStringSet("apps", selected).apply(); dismiss() }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable private fun AccountingForm(s: BusinessSettings, dismiss: () -> Unit, save: (BusinessSettings) -> Unit) {
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false } }
    var maximum by rememberSaveable { mutableStateOf(s.maxSubscribers.toString()) }
    val maxNumber = Money.normalize(maximum).toIntOrNull()
    var premium by rememberSaveable { mutableStateOf(Money.show(s.premiumBps.toLong())) }
    var usd by rememberSaveable { mutableStateOf(Money.show(s.usdCents)) }
    var rate by rememberSaveable { mutableStateOf(Money.show(s.bankRate)) }
    var expenses by rememberSaveable { mutableStateOf(Money.show(s.expenses)) }
    var start by rememberSaveable { mutableStateOf(if (s.cycleStart == 0L) "" else format.format(Date(s.cycleStart))) }
    var end by rememberSaveable { mutableStateOf(if (s.cycleEnd == 0L) "" else format.format(Date(s.cycleEnd - 1))) }
    fun date(value: String): Long? = try { val v = Money.normalize(value); if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(v)) null else format.parse(v)?.time } catch (_: Exception) { null }
    val begin = date(start); val last = date(end)
    val endExclusive = last?.let { Calendar.getInstance().apply { timeInMillis = it; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    val p = Money.parse(premium)
    val u = Money.parse(usd); val r = Money.parse(rate); val e = Money.parse(expenses)
    val valid = maxNumber != null && maxNumber in 1..10000 && p != null && p in 0..100000 && u != null && r != null && e != null &&
        (start.isBlank() && end.isBlank() || begin != null && begin > 0 && endExclusive != null && endExclusive > begin)
    Form("إعدادات الحساب", dismiss, {
        save(s.copy(maxSubscribers = maxNumber!!, graceMinutes = Rules.RECOGNITION_MINUTES, premiumBps = p!!.toInt(), usdCents = u!!, bankRate = r!!, expenses = e!!, cycleStart = begin ?: 0, cycleEnd = endExclusive ?: 0))
    }, valid) {
        Field("أرقام المشتركين من 1 إلى", maximum, { maximum = it })
        Text("الافتراضي 50. يُضاف [الرقم] تلقائيًا بعد نص اختصار الاشتراك، ولا يتكرر بين الاشتراكات النشطة أو المتوقفة مؤقتًا.")
        Text("استخدام اختصار الاشتراك يؤكد الدفع. يُعتمد الإيراد مرة واحدة بعد 5 دقائق محتسبة، ويُنسب ليوم اكتمالها.")
        Field("نسبة تحويل تكلفة الفاتورة ٪", premium, { premium = it })
        Field("اشتراك Starlink بالدولار", usd, { usd = it })
        Field("سعر دولار الفاتورة قبل التحويل", rate, { rate = it })
        Field("مصروفات الدورة بقيمة الكاش", expenses, { expenses = it })
        Field("أول يوم · yyyy-MM-dd", start, { start = it })
        Field("آخر يوم شاملًا · yyyy-MM-dd", end, { end = it })
        Text("تكلفة الفاتورة بالجنيه = الدولار × سعر الصرف ÷ (1 + نسبة التحويل)، ثم تضاف المصروفات. إعداداتك السابقة محفوظة؛ النسبة لا تضيف سعرًا ثانيًا للاشتراكات.")
        Text("اترك التاريخين فارغين إن لم تبدأ دورة. الاشتراكات السابقة تحتفظ بشروطها. تعديل الدورة يعيد حساب هدف اليوم والسجل المالي.")
        if (!valid) Text("راجع المبالغ والتواريخ. النسبة 0–1000٪.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun RenameSessionForm(session: Session, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.client) }
    Form("تسمية المشترك", dismiss, { save(name.trim()) }, name.isNotBlank() && name.trim().length <= 80) {
        Text("رقم الاشتراك #${session.reference.ifBlank { session.id.take(8) }} يبقى ثابتًا. الاسم الجديد سيظهر في تنبيه الانتهاء.")
        Field("الاسم أو وصف الجهاز", name, { name = it })
    }
}
