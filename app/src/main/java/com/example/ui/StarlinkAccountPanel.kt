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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

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

/**
 * One-off diagnostic probe: issues the same authenticated GET the native client sends, but from
 * inside the WebView's own JS engine via fetch({credentials:'include'}) - the real cookie jar and
 * the real browser TLS fingerprint, neither of which this function ever touches or exposes as
 * text. Used only to tell whether a native HTTP client's fingerprint is what a later native-call
 * failure is about, without extracting or logging any raw cookie value. Times out on its own;
 * never blocks the real flow.
 */
private suspend fun probeAuthViaWebView(webView: WebView): String = withTimeoutOrNull(15000) {
    suspendCancellableCoroutine { continuation ->
        val probeName = "SlotraAuthProbe"
        val bridge = object {
            @JavascriptInterface
            fun onResult(status: String) {
                webView.post {
                    runCatching { webView.removeJavascriptInterface(probeName) }
                    if (continuation.isActive) continuation.resume(status)
                }
            }
        }
        webView.addJavascriptInterface(bridge, probeName)
        webView.evaluateJavascript(
            "fetch('${CloudPolicy.AUTH}', {credentials:'include'})" +
                ".then(function(r){window.$probeName.onResult(String(r.status));})" +
                ".catch(function(e){window.$probeName.onResult('error:'+(e&&e.message?e.message:'x'));});",
            null
        )
        continuation.invokeOnCancellation { webView.post { runCatching { webView.removeJavascriptInterface(probeName) } } }
    }
} ?: "timeout"

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Suppress("DEPRECATION")
@Composable private fun StarlinkLoginDialog(onClose: () -> Unit, onBusy: (Boolean) -> Unit, onComplete: () -> Unit, copyDiagnostic: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("سجّل الدخول، ثم اضغط التحقق من الربط. ابقَ متصلًا براوتر Starlink.") }
    var diagnostic by remember { mutableStateOf("") }
    val capturedCookies = remember { ConcurrentHashMap<String, String>() }
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
                        // Confirmed on-device (2026-09-25): the user's regular Chrome logs into
                        // starlink.com fine; this WebView (both page navigation and the JS-fetch
                        // probe below) times out on the authenticated request. Android WebView's
                        // default UA carries "; wv)" and a leading "Version/X.Y " marker that real
                        // Chrome's UA never has - a standard signal bot-detection uses to flag
                        // in-app/embedded browsers. Stripped here so this WebView presents the
                        // same UA shape as the Chrome that's already proven to work, while keeping
                        // the device's real Chrome build number (no fixed/fake version string).
                        settings.userAgentString = WebSettings.getDefaultUserAgent(ctx)
                            .replace("; wv)", ")")
                            .replace(Regex("Version/[0-9.]+\\s+"), "")
                        @SuppressLint("WrongConstant")
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) {
                            WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(settings, true)
                        }
                        run {
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            // Starlink login may set session cookies across Starlink subdomains.
                            // Android 12+ WebView defaults third-party cookies to disabled for modern apps.
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val reject = !CloudPolicy.loginUrlAllowed(request.url.toString())
                                if (reject) { message = "أوقفنا انتقالًا خارج نطاق Starlink. لم تُرسل بيانات الربط لأي موقع آخر."; diagnostic = "LOGIN: navigation_blocked" }
                                return reject
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val host = request.url.host?.lowercase().orEmpty()
                                val cookieHeader = request.requestHeaders["Cookie"]
                                // Includes the apex host `starlink.com`, which carries the real
                                // account session; endsWith(".starlink.com") alone excluded it.
                                if (CloudPolicy.sessionHost(host) && !cookieHeader.isNullOrBlank()) {
                                    capturedCookies[host] = cookieHeader
                                }
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
                    busy = true
                    message = "جاري قراءة جلسة WebView والتحقق من الربط…"
                    scope.launch {
                        // Set once, right before the native connect() call below, and read from
                        // both catch branches - never touched concurrently since this is all one
                        // sequential coroutine.
                        var webAuthProbe = ""
                        try {
                            val session = withContext(Dispatchers.IO) {
                                val manager = CookieManager.getInstance()
                                manager.flush()
                                val cookieHeaders = CloudPolicy.SESSION_COOKIE_URLS.map { url ->
                                    url to runCatching { manager.getCookie(url) }.getOrNull()
                                }
                                val interceptedHeaders = capturedCookies.entries.map { (host, header) ->
                                    "https://$host/" to header
                                }
                                val allHeaders = cookieHeaders + interceptedHeaders
                                val candidate = runCatching {
                                    CloudPolicy.cookies(*allHeaders.map { it.second }.toTypedArray())
                                }.getOrNull()
                                Triple(
                                    manager.hasCookies(),
                                    manager.acceptCookie(),
                                    allHeaders to candidate
                                )
                            }
                            val hasCookies = session.first
                            val cookiesAccepted = session.second
                            val (cookieHeaders, candidate) = session.third
                            if (candidate == null || !CloudPolicy.hasLogin(candidate)) {
                                message = "اكتمل تسجيل الدخول في الصفحة، لكن Slotra لم يجد Cookie جلسة قابلة للاستخدام. لا نرسل أي طلب Cloud حتى تتوفر الجلسة."
                                val interceptedForDiagnostics = capturedCookies.entries.map { (host, header) -> "https://$host/" to header }
                                diagnostic = "LOGIN: session_not_available; WEBVIEW_COOKIES: HAS_COOKIES=${if (hasCookies) "YES" else "NO"} ACCEPT=${if (cookiesAccepted) "YES" else "NO"} INTERCEPTED=${capturedCookies.size}; COOKIES: ${CloudPolicy.sessionDiagnostics(cookieHeaders)}; INTERCEPTED: ${CloudPolicy.sessionDiagnostics(interceptedForDiagnostics)}"
                            } else {
                                message = "جاري التحقق من الجلسة ومطابقة الراوتر…"
                                // Diagnostic only: confirms whether the very same authenticated
                                // request succeeds through the WebView's genuine browser fetch
                                // right before the native client attempts it. Isolates a native
                                // TLS/client-fingerprint block from anything session- or
                                // account-specific, without ever handling the raw cookie value.
                                webAuthProbe = view?.let { probeAuthViaWebView(it) } ?: "no_webview"
                                withTimeout(60000) {
                                    StarlinkCloud(CloudSessionVault(context)).connect(candidate, AndroidRouterLink(context))
                                }
                                onComplete()
                            }
                        } catch (_: TimeoutCancellationException) {
                            message = "انتهت مهلة التحقق من الحساب. لم نرسل أمر حظر."
                            diagnostic = "LOGIN: timeout${if (webAuthProbe.isNotBlank()) "; WEBVIEW_AUTH_PROBE=$webAuthProbe" else ""}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = controlError(e)
                            // e.cause carries the real network exception class for cloud_network_failed
                            // (see AccountHttp.onFailure); errorCode(e) itself is left untouched so
                            // controlError()'s dispatch and other call sites stay exact-match safe.
                            diagnostic = "LOGIN: ${errorCode(e)}${e.cause?.let { ":${it.javaClass.simpleName}" }.orEmpty()}${if (webAuthProbe.isNotBlank()) "; WEBVIEW_AUTH_PROBE=$webAuthProbe" else ""}"
                        } finally {
                            busy = false
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
            capturedCookies.clear()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
}
