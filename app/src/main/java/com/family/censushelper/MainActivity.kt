package com.family.censushelper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var prefs: SharedPreferences
    private val targetUrl = "https://se.census.gov.in/"
    private var isDesktopMode = false
    private var currentTextZoom = 110

    // Handles the 1-tap "Allow" OTP SMS dialog
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("census_helper_prefs", MODE_PRIVATE)

        // Root Vertical Layout: Parent Helper Toolbar + WebView
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D47A1.toInt())
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener { onClick() }
            }
        }

        toolbar.addView(makeBtn("A-") {
            currentTextZoom = (currentTextZoom - 10).coerceAtLeast(80)
            webView.settings.textZoom = currentTextZoom
        })
        toolbar.addView(makeBtn("A+") {
            currentTextZoom = (currentTextZoom + 10).coerceAtMost(160)
            webView.settings.textZoom = currentTextZoom
        })
        toolbar.addView(makeBtn("Restore") {
            val saved = prefs.getString("draft_json", "") ?: ""
            val escaped = saved.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.__restoreCensusDraft && window.__restoreCensusDraft('$escaped');", null)
        })
        toolbar.addView(makeBtn("Map/PC") {
            isDesktopMode = !isDesktopMode
            webView.settings.userAgentString = if (isDesktopMode) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            } else {
                WebSettings.getDefaultUserAgent(this)
            }
            Toast.makeText(this, if (isDesktopMode) "Desktop Mode ON (Better for Map)" else "Mobile Mode ON", Toast.LENGTH_SHORT).show()
            webView.reload()
        })
        toolbar.addView(makeBtn("ID/PDF") {
            showSeIdOrPrintDialog()
        })

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(toolbar)
        root.addView(webView)
        setContentView(root)

        setupWebView()
        checkMaintenanceWindow()
        startOtpListener()
        requestLocationIfNeeded()
        setupBackGuard()

        webView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = currentTextZoom
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()
                val jsCode = assets.open("enhancer.js").bufferedReader().use { it.readText() }
                webView.evaluateJavascript(jsCode, null)
            }
        }

        // Enable GPS Geolocation for the Census Residence Map Pinning step
        webView.webChromeClient = object : WebChromeClient() {
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

    private fun checkMaintenanceWindow() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 0..5) {
            AlertDialog.Builder(this)
                .setTitle("Portal Maintenance Hours (12 AM – 6 AM)")
                .setMessage("se.census.gov.in is usually offline between 12 AM and 6 AM. Best times to fill the form are 6–8 AM or after 10 PM.")
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
