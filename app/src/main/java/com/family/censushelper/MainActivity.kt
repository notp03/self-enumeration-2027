package com.family.censushelper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.http.SslError
import android.os.*
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences

    private val targetUrl = "https://se.census.gov.in/"
    private val mobileChromeUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private val desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private var isDesktopMode = false
    private var currentTextZoom = 110
    private val debugLogs = mutableListOf<String>()

    private val smsConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return@registerForActivityResult
            val otpMatch = Regex("\\b\\d{6}\\b").find(message)
            if (otpMatch != null) {
                val otp = otpMatch.value
                webView.evaluateJavascript("window.__fillCensusOtp && window.__fillCensusOtp('$otp');", null)
            }
        }
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent.action) {
                val extras = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                    if (consentIntent != null) {
                        try {
                            smsConsentLauncher.launch(consentIntent)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun logEvent(msg: String) {
        if (debugLogs.size > 40) debugLogs.removeAt(0)
        debugLogs.add(msg)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)
        prefs = getSharedPreferences("census_helper_prefs", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D47A1.toInt())
            setPadding(6, 8, 6, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeBtn(label: String, weight: Float = 1f, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setPadding(4, 4, 4, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener { onClick() }
            }
        }

        toolbar.addView(makeBtn("↻", 0.6f) {
            logEvent("Manual reload")
            loadTarget(webView.url ?: targetUrl)
        })
        toolbar.addView(makeBtn("A-", 0.6f) {
            currentTextZoom = (currentTextZoom - 10).coerceAtLeast(80)
            webView.settings.textZoom = currentTextZoom
        })
        toolbar.addView(makeBtn("A+", 0.6f) {
            currentTextZoom = (currentTextZoom + 10).coerceAtMost(160)
            webView.settings.textZoom = currentTextZoom
        })
        toolbar.addView(makeBtn("Restore", 1.0f) {
            val saved = prefs.getString("draft_json", "") ?: ""
            val escaped = saved.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.__restoreCensusDraft && window.__restoreCensusDraft('$escaped');", null)
        })
        toolbar.addView(makeBtn("PC", 0.7f) {
            isDesktopMode = !isDesktopMode
            webView.settings.userAgentString = if (isDesktopMode) desktopChromeUA else mobileChromeUA
            Toast.makeText(this, if (isDesktopMode) "Desktop Mode ON" else "Mobile Mode ON", Toast.LENGTH_SHORT).show()
            loadTarget(webView.url ?: targetUrl)
        })
        toolbar.addView(makeBtn("ID", 0.6f) {
            showSeIdOrPrintDialog()
        })
        toolbar.addView(makeBtn("Log", 0.7f) {
            showDebugDialog()
        })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 10)
            visibility = View.VISIBLE
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(toolbar)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        setupWebView()
        checkMaintenanceWindow()
        startOtpListener()
        requestLocationIfNeeded()
        setupBackGuard()

        loadTarget(targetUrl)
    }

    private fun loadTarget(url: String) {
        val headers = mapOf("X-Requested-With" to "")
        logEvent("Loading: $url")
        webView.loadUrl(url, headers)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = mobileChromeUA
            setGeolocationEnabled(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = currentTextZoom
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                logEvent("Finished: $url")
                cookieManager.flush()
                try {
                    val jsCode = assets.open("enhancer.js").bufferedReader().use { it.readText() }
                    webView.evaluateJavascript(jsCode, null)
                } catch (e: Exception) {
                    logEvent("JS inject err: ${e.message}")
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                logEvent("SSL err: ${error?.primaryError} on ${error?.url}")
                if (error?.url?.contains("census.gov.in") == true) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                logEvent("HTTP ${errorResponse?.statusCode}: ${request?.url}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                logEvent("Net err ${error?.errorCode}: ${error?.description} (${request?.url})")
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@MainActivity,
                        "Site error: ${error?.description} (${error?.errorCode})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    logEvent("JS [${consoleMessage.messageLevel()}]: ${consoleMessage.message()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                createWebPrintJob(webView)
            }
        }
    }

    private fun showDebugDialog() {
        webView.evaluateJavascript("document.documentElement ? document.documentElement.innerText.slice(0, 200) : 'EMPTY'") { snippet ->
            val report = buildString {
                append("URL: ${webView.url}\n")
                append("Text: $snippet\n\n")
                append("--- LOGS ---\n")
                append(debugLogs.joinToString("\n"))
            }
            AlertDialog.Builder(this)
                .setTitle("Debug Log")
                .setMessage(report)
                .setPositiveButton("Copy Log") { _, _ ->
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("CensusLog", report))
                    Toast.makeText(this, "Copied log!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun checkMaintenanceWindow() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 0..5) {
            AlertDialog.Builder(this)
                .setTitle("Portal Maintenance Hours (12 AM – 6 AM)")
                .setMessage("se.census.gov.in is usually offline between 12 AM and 6 AM.")
                .setPositiveButton("Try Anyway", null)
                .show()
        }
    }

    private fun startOtpListener() {
        SmsRetriever.getClient(this).startSmsUserConsent(null)
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun requestLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        }
    }

    private fun showSeIdOrPrintDialog() {
        val savedId = prefs.getString("saved_se_id", null)
        val msg = if (savedId != null) {
            "Saved Self-Enumeration ID:\n\n$savedId\n\nKeep this ready for the Census Enumerator."
        } else {
            "No SE ID detected yet. Once you submit the form, your 11-digit SE ID will be auto-saved here."
        }
        AlertDialog.Builder(this)
            .setTitle("SE ID Vault & Receipt")
            .setMessage(msg)
            .setPositiveButton("Save Page as PDF") { _, _ -> createWebPrintJob(webView) }
            .setNeutralButton("Copy SE ID") { _, _ ->
                if (savedId != null) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("SE ID", savedId))
                    Toast.makeText(this, "Copied $savedId", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupBackGuard() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Go Back?")
                    .setMessage("Going back may clear unsaved Census details on this page. Continue?")
                    .setPositiveButton("Yes, Go Back") { _, _ ->
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                    .setNegativeButton("Stay on Form", null)
                    .show()
            }
        })
    }

    private fun createWebPrintJob(webView: WebView) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter = webView.createPrintDocumentAdapter("Census_2027_Receipt")
        printManager.print("Census_2027_Receipt", printAdapter, PrintAttributes.Builder().build())
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(smsReceiver) } catch (_: Exception) {}
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun saveDraft(json: String) {
            prefs.edit().putString("draft_json", json).apply()
        }

        @JavascriptInterface
        fun saveSeId(seId: String) {
            val existing = prefs.getString("saved_se_id", null)
            if (existing != seId) {
                prefs.edit().putString("saved_se_id", seId).apply()
                runOnUiThread {
                    Toast.makeText(context, "SE ID Auto-Saved: $seId", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun vibrateAlert() {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(180)
            }
        }
    }
}
