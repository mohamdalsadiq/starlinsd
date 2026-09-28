package com.example.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayInputStream

@Composable internal fun StarlinkAccountPanel(blocked: Boolean, onBusy: (Boolean) -> Unit, onLinked: (Boolean) -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { CloudSessionVault(context.applicationContext) }
    var linked by remember { mutableStateOf(vault.exists()) }
    var login by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(linked) { onLinked(linked) }
    Panel {
        Text("ربط حساب Starlink", style = MaterialTheme.typography.titleLarge)
        Text(if (linked) "جلسة محفوظة على هذا الهاتف؛ نتحقق من صلاحيتها ومن الراوتر عند كل تجربة."
            else "تجربة التحكم الجديدة تحتاج حساب Starlink الذي يدير هذا الراوتر. القراءة المحلية تعمل بدون حساب.")
        Text("تدخل بياناتك في صفحة Starlink الأصلية. نحتفظ بجلسة مشفّرة على الهاتف ونرسلها إلى Starlink فقط. هذه واجهة تجريبية غير رسمية، وقد يرفض الموقع تسجيل الدخول من داخل التطبيق.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !blocked, modifier = Modifier.fillMaxWidth().testTag("starlink-account-connect"), onClick = { login = true }) {
            Text(if (linked) "إعادة تسجيل الدخول" else "ربط الحساب")
        }
        if (linked) TextButton(enabled = !blocked, onClick = {
            try {
                vault.clear(); CookieManager.getInstance().removeAllCookies(null)
                linked = false; message = "فُصل الحساب من Slotra. فصل الحساب لا يلغي إيقاف جهاز؛ استخدم الاسترجاع أو تطبيق Starlink أولًا."
            } catch (_: Exception) { message = "تعذر فصل الجلسة المحفوظة." }
        }) { Text("فصل الحساب من Slotra") }
        if (message.isNotBlank()) Text(message)
    }
    if (login) StarlinkLoginDialog(onClose = { login = false; onBusy(false) }, onBusy = onBusy, onComplete = {
        linked = true; login = false; onBusy(false); message = "نجحت قراءة الراوتر عبر الحساب وطابق الراوتر المحلي. يمكنك الآن فحص جهاز للتجربة."
    }, copyDiagnostic = { value -> clipboard.setText(AnnotatedString("Slotra ${com.example.BuildConfig.VERSION_NAME} · ربط Starlink\n$value")) })
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable private fun StarlinkLoginDialog(onClose: () -> Unit, onBusy: (Boolean) -> Unit, onComplete: () -> Unit, copyDiagnostic: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("سجّل الدخول، ثم اضغط التحقق من الربط. ابقَ متصلًا براوتر Starlink.") }
    var diagnostic by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onBusy(true); onDispose { onBusy(false) } }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f).imePadding(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text("تسجيل الدخول إلى Starlink", style = MaterialTheme.typography.titleMedium)
                Text("https://www.starlink.com", style = MaterialTheme.typography.bodySmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                    WebView(ctx).apply {
                        view = this
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.saveFormData = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val reject = !CloudPolicy.loginUrlAllowed(request.url.toString())
                                if (reject) { message = "أوقفنا انتقالًا خارج نطاق Starlink. لم تُرسل بيانات الربط لأي موقع آخر."; diagnostic = "LOGIN: navigation_blocked" }
                                return reject
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.scheme != "https" || (request.isForMainFrame && !CloudPolicy.loginUrlAllowed(request.url.toString())))
                                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                                return null
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel(); message = "تعذر التحقق من شهادة الاتصال بـStarlink."; diagnostic = "LOGIN: tls_rejected"
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { message = "تعذر تحميل صفحة الدخول. تحقق من الإنترنت أو أعد فتح الربط."; diagnostic = "LOGIN: web_error=${error.errorCode}" }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        }
                        val web = this
                        CookieManager.getInstance().removeAllCookies { web.post { if (view === web) web.loadUrl(CloudPolicy.LOGIN) } }
                    }
                })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    val manager = CookieManager.getInstance()
                    val candidate = runCatching { CloudPolicy.cookies(manager.getCookie(CloudPolicy.LOGIN), manager.getCookie(CloudPolicy.HANDLE), manager.getCookie(CloudPolicy.AUTH)) }.getOrNull()
                    if (candidate == null || !CloudPolicy.hasLogin(candidate)) {
                        message = "أكمل تسجيل الدخول أولًا، بما فيه رمز التحقق إن طُلب."; diagnostic = "LOGIN: session_not_available"
                    } else {
                        busy = true; message = "جاري التحقق من الجلسة ومطابقة الراوتر…"
                        scope.launch {
                            try {
                                withTimeout(60000) { StarlinkCloud(CloudSessionVault(context)).connect(candidate, AndroidRouterLink(context)) }
                                onComplete()
                            } catch (_: TimeoutCancellationException) { message = "انتهت مهلة التحقق من الحساب. لم نرسل أمر حظر."; diagnostic = "LOGIN: timeout" }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = controlError(e); diagnostic = "LOGIN: ${errorCode(e)}" }
                            finally { busy = false }
                        }
                    }
                }) { Text("التحقق من الربط") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy, onClick = onClose) { Text("إغلاق") }
                    if (diagnostic.isNotBlank()) TextButton(onClick = { copyDiagnostic(diagnostic) }) { Text("نسخ تشخيص الربط") }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            view?.let { it.stopLoading(); it.clearCache(true); it.clearHistory(); it.destroy() }; view = null
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
}
