package com.shantanu.shield.webfilter

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shantanu.shield.ui.theme.AppShieldTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The filtered browser. With web filtering on, every other browser is blocked, so this is where the
 * child browses — and where the filter actually runs.
 *
 * Why a WebView rather than DNS or a VPN: [WebViewClient.shouldOverrideUrlLoading] hands over the FULL
 * URL before anything loads. That is strictly more than a network-layer filter can ever see (HTTPS
 * hides everything past the hostname), so this can block one page of a site, force SafeSearch, and
 * count what it refused — none of which DNS filtering can do.
 *
 * The filtering is the easy half. The hard half is not leaking out of the WebView; see
 * [FilteringWebViewClient] and [configureSafely].
 */
@AndroidEntryPoint
class ShieldBrowserActivity : ComponentActivity() {

    /**
     * The URL the browser should be showing.
     *
     * Held as state rather than read once in [onCreate] because `singleTop` means a second launch —
     * another link tapped in WhatsApp while this is already open — arrives at [onNewIntent] and never
     * re-runs onCreate. Reading the intent only at creation would show the previous page and silently
     * drop the link the child just tapped.
     */
    private val targetUrl = mutableStateOf(UrlNormalizer.HOME_URL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched either from our own UI or by Android handing us an http(s) link from another app.
        targetUrl.value = urlFrom(intent)
        setContent {
            AppShieldTheme { BrowserScreen(targetUrl = targetUrl, onExit = { finish() }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetUrl.value = urlFrom(intent)
    }

    private fun urlFrom(intent: Intent?): String =
        intent?.data?.toString()?.takeIf { it.isNotBlank() } ?: UrlNormalizer.HOME_URL
}

@Composable
private fun BrowserScreen(
    targetUrl: androidx.compose.runtime.State<String>,
    onExit: () -> Unit,
    viewModel: ShieldBrowserViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var blocked by remember { mutableStateOf<FilterDecision?>(null) }
    var blockedUrl by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(targetUrl.value) }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Handles links that arrive LATER, via onNewIntent — the first page is loaded by the factory
    // below, deliberately.
    //
    // The tempting version keys this on the WebView as well and drops the factory load, but then the
    // first page depends on `webView = this` (a state write during composition) provoking a
    // recomposition. If that timing ever fails the browser opens blank, so the initial load is kept
    // somewhere it cannot race: the factory, which runs exactly once with the view in hand.
    //
    // `lastRequested` is seeded with the initial URL so this never re-loads the page the factory just
    // loaded.
    var lastRequested by remember { mutableStateOf(targetUrl.value) }
    LaunchedEffect(targetUrl.value) {
        val url = targetUrl.value
        if (url == lastRequested) return@LaunchedEffect
        lastRequested = url
        webView?.loadUrl(UrlNormalizer.toUrlOrSearch(url))
    }

    // Phone back button behaves like a browser's back, not like "quit".
    //
    // Order matters: a block page is dismissed first (otherwise back appears to do nothing while the
    // notice covers the screen), then page history, and only with nowhere left to go does the browser
    // close. Without this the default finished the Activity on the first press, which loses the
    // child's place on the very first tap.
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            blocked != null -> {
                blocked = null
                if (webView?.canGoBack() == true) webView?.goBack() else onExit()
            }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onExit()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            // Address bar. No tabs, no incognito, no history menu — every one of those is a place a
            // filter has to be re-applied, and an omission becomes a hole.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search or type a web address") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        webView?.loadUrl(UrlNormalizer.toUrlOrSearch(address))
                    }),
                    // Clears the text, nothing else. Previously the only X on screen closed the whole
                    // browser, which is a destructive action sitting exactly where every other app puts
                    // "clear what I typed".
                    trailingIcon = {
                        if (address.isNotEmpty()) {
                            IconButton(onClick = { address = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                )
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            configureSafely()
                            webViewClient = FilteringWebViewClient(
                                configProvider = { viewModel.config.value },
                                onBlocked = { decision, url ->
                                    blocked = decision
                                    blockedUrl = url
                                    viewModel.recordBlock(decision)
                                },
                                onPageStarted = { url ->
                                    loading = true
                                    address = url
                                    blocked = null
                                },
                                onPageFinished = { loading = false },
                            )
                            webView = this
                            // The first load lives here, where the view is guaranteed to exist and
                            // nothing can race it. Later links are handled by the effect above.
                            loadUrl(UrlNormalizer.toUrlOrSearch(targetUrl.value))
                        }
                    },
                )

                blocked?.let { decision ->
                    BlockPage(
                        decision = decision,
                        url = blockedUrl,
                        onBack = {
                            blocked = null
                            if (webView?.canGoBack() == true) webView?.goBack() else onExit()
                        },
                    )
                }
            }
        }
    }
}

/** Full-screen block notice. Opaque, because a translucent one leaves the page readable underneath. */
@Composable
private fun BlockPage(decision: FilterDecision, url: String, onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "This site is blocked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Naming the reason matters: "blocked" alone reads as a bug, and a child who thinks the
                // app is broken asks for it to be removed rather than accepting the rule.
                when (decision) {
                    is FilterDecision.BlockedByCategory ->
                        "${decision.category.label} is switched off on this phone."
                    is FilterDecision.BlockedByParent -> "Your parent blocked this site."
                    is FilterDecision.BlockedScheme ->
                        "This link tried to open another app, which isn't allowed here."
                    is FilterDecision.Allowed -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (url.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    DomainBlocklist.normalizeHost(Uri.parse(url).host) ?: url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Text("Go back") }
            Spacer(Modifier.height(14.dp))
            Text(
                "If you think this is a mistake, ask your parent to allow it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The WebView settings that keep content inside the filter.
 *
 * Each line closes a way out, not a feature request:
 *  - multiple windows OFF → a page can't open an unfiltered popup window
 *  - file access OFF → no reading the device's files through `file://`
 *  - no cache of form data / passwords → nothing for the next user to find
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureSafely() {
    settings.apply {
        // Required — almost nothing renders without it. The filter runs on navigation, not on JS.
        javaScriptEnabled = true
        domStorageEnabled = true
        // Escape hatches, closed.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        // Blocks the device's filesystem. `file:///android_asset` is explicitly exempt from this
        // setting, so the start page still loads while the child's photos and downloads do not.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        saveFormData = false
        // Sensible defaults for a phone.
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
    }
    isLongClickable = false
    // Long-press opens a context menu offering "Open in browser" on some OEM WebViews — a one-tap
    // route out of the filtered browser into an unfiltered one.
    setOnLongClickListener { true }
    // A download would let a child fetch an APK (or the blocked content itself) around the filter.
    setDownloadListener { url, _, _, _, _ ->
        Log.i(TAG, "download refused: ${DomainBlocklist.normalizeHost(Uri.parse(url).host)}")
    }
}

/**
 * Applies the filter to every navigation.
 *
 * Checks each navigation rather than only the first: a blocked site reached through a redirect, a
 * meta-refresh or a JS `location` assignment is still a blocked site, and only per-navigation checking
 * catches all three.
 */
private class FilteringWebViewClient(
    private val configProvider: () -> WebFilterConfig,
    private val onBlocked: (FilterDecision, String) -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url ?: return true

        // THE most important check here, and it isn't about content: a page can hand Android
        // `intent://…#Intent;package=com.android.chrome;end` and walk the child straight out of this
        // browser into an unfiltered one. Refuse every non-web scheme, and never pass it to the system.
        if (!DomainBlocklist.isWebScheme(url.scheme)) {
            onBlocked(FilterDecision.BlockedScheme, url.toString())
            return true
        }

        val config = configProvider()
        val decision = DomainBlocklist.decide(
            host = url.host,
            enabledCategories = config.enabledCategories,
            categoryDomains = WebFilterLists.BY_CATEGORY,
            parentAllowed = config.parentAllowed,
            parentBlocked = config.parentBlocked,
        )
        if (decision.isBlocked) {
            onBlocked(decision, url.toString())
            return true
        }

        // SafeSearch is enforced by rewriting the URL — possible only because we own it. A DNS or VPN
        // filter cannot do this without breaking TLS.
        val safe = UrlNormalizer.enforceSafeSearch(url)
        if (safe != null) {
            view.loadUrl(safe)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        onPageStarted(url ?: "")
    }

    override fun onPageFinished(view: WebView, url: String?) = onPageFinished()
}

private const val TAG = "ShieldWebFilter"
