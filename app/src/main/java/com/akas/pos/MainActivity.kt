package com.akas.pos

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.akas.pos.databinding.ActivityMainBinding
import com.akas.pos.config.PosConfig
import com.akas.pos.config.PosConfigLoader
import com.akas.pos.printer.EscPosPrinterManager
import com.akas.pos.web.PosJavascriptBridge
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var printerManager: EscPosPrinterManager
    private lateinit var posConfig: PosConfig
    private val navigationPreferences by lazy { getSharedPreferences("navigation", MODE_PRIVATE) }
    private val printerExecutor = Executors.newSingleThreadExecutor()
    private var pendingPrinterSelection = false
    private var activeInvoiceId: String? = null
    private var lastPersistedUrl: String? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var popupWebView: WebView? = null
    private val urlMonitorHandler = Handler(Looper.getMainLooper())
    private val urlMonitor = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) updateNativePrintButton(binding.webView.url)
            urlMonitorHandler.postDelayed(this, URL_MONITOR_INTERVAL_MS)
        }
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingPrinterSelection) showPrinterDialog()
            else if (!granted) notifyWeb("onPrinterError", "Izin Bluetooth ditolak")
            pendingPrinterSelection = false
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileUploadCallback ?: return@registerForActivityResult
            val data = result.data
            val selectedFiles = if (result.resultCode == Activity.RESULT_OK) {
                when {
                    data?.clipData != null -> {
                        val clipData = data.clipData!!
                        Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else {
                null
            }
            callback.onReceiveValue(selectedFiles)
            fileUploadCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        posConfig = PosConfigLoader.load(this)
        printerManager = EscPosPrinterManager(this)

        configureWebView()
        binding.nativePrintButton.setOnClickListener {
            val invoiceId = activeInvoiceId ?: return@setOnClickListener
            if (printerManager.savedPrinterAddress() == null) {
                openPrinterSettings()
            } else {
                requestReceiptFromWeb(invoiceId)
            }
        }
        binding.connectPrinterButton.setOnClickListener { openPrinterSettings() }
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) binding.webView.loadUrl(restorableStartUrl())
        else binding.webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        updatePrinterStatus()
        urlMonitorHandler.removeCallbacks(urlMonitor)
        urlMonitorHandler.post(urlMonitor)
    }

    override fun onPause() {
        urlMonitorHandler.removeCallbacks(urlMonitor)
        persistLastUrl(binding.webView.url)
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.setSupportZoom(false)
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.userAgentString = "${settings.userAgentString} AkasPOS/1.0 Android"
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@with, false)
        }

        val javascriptBridge = PosJavascriptBridge(this@MainActivity)
        addJavascriptInterface(javascriptBridge, JS_BRIDGE_NAME)
        addJavascriptInterface(javascriptBridge, LEGACY_JS_BRIDGE_NAME)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                binding.progressBar.progress = progress
                binding.progressBar.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false

                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val pickerIntent = runCatching { fileChooserParams?.createIntent() }
                    .getOrNull()
                    ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                return try {
                    fileChooserLauncher.launch(pickerIntent)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "Aplikasi pemilih file tidak tersedia",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                popupWebView?.destroy()

                val popup = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var handled = false

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            if (!handled) {
                                handled = true
                                openPopupInMainWebView(view, request.url)
                            }
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            val uri = url
                                ?.takeUnless { it == "about:blank" }
                                ?.let(Uri::parse)
                                ?: return
                            if (handled) return
                            handled = true
                            openPopupInMainWebView(view, uri)
                        }
                    }
                }
                popupWebView = popup
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (isTrustedUri(uri)) false else {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.errorView.visibility = View.GONE
                updateNativePrintButton(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                updateNativePrintButton(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) binding.errorView.visibility = View.VISIBLE
            }
        }
    }

    private fun isTrustedUri(uri: Uri): Boolean =
        uri.scheme == "https" && posConfig.isAllowedHost(uri.host)

    private fun openPopupInMainWebView(popup: WebView, uri: Uri) {
        popup.stopLoading()
        if (isTrustedUri(uri)) {
            binding.webView.loadUrl(uri.toString())
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure {
                    Toast.makeText(this, "Tautan tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                }
        }
        popup.post {
            if (popupWebView === popup) popupWebView = null
            popup.destroy()
        }
    }

    private fun restorableStartUrl(): String {
        val savedUrl = navigationPreferences.getString(KEY_LAST_URL, null)
        return savedUrl?.takeIf { url ->
            runCatching { isTrustedUri(Uri.parse(url)) }.getOrDefault(false)
        } ?: posConfig.startUrl
    }

    private fun persistLastUrl(url: String?) {
        if (url.isNullOrBlank() || url == lastPersistedUrl) return
        val trusted = runCatching { isTrustedUri(Uri.parse(url)) }.getOrDefault(false)
        if (!trusted) return
        lastPersistedUrl = url
        navigationPreferences.edit().putString(KEY_LAST_URL, url).apply()
    }

    private fun updateNativePrintButton(url: String?) {
        persistLastUrl(url)
        val invoiceId = url?.let(::extractInvoiceId)
        binding.nativePrintPanel.visibility = if (invoiceId != null) View.VISIBLE else View.GONE

        if (invoiceId != null && invoiceId != activeInvoiceId) {
            activeInvoiceId = invoiceId
        } else if (invoiceId == null) {
            activeInvoiceId = null
        }
    }

    private fun openPrinterSettings() {
        startActivity(Intent(this, PrinterSettingsActivity::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun updatePrinterStatus() {
        val address = printerManager.savedPrinterAddress()
        val name = printerManager.savedPrinterName()
        if (address == null) {
            binding.printerStatus.text = "Printer belum disambungkan"
            binding.connectPrinterButton.text = "Sambungkan"
            binding.nativePrintButton.isEnabled = false
        } else {
            binding.printerStatus.text = "● Siap: ${name ?: address} • ${printerManager.paperWidthMm()} mm"
            binding.connectPrinterButton.text = "Ganti Printer"
            binding.nativePrintButton.isEnabled = true
        }
    }

    private fun requestReceiptFromWeb(invoiceId: String) {
        binding.printerStatus.text = "Meminta data struk dari halaman…"
        val safeInvoiceId = JSONObject.quote(invoiceId)
        binding.webView.evaluateJavascript(
            """
            (function() {
              window.dispatchEvent(new CustomEvent('akas:native-print', {
                detail: { invoiceId: $safeInvoiceId }
              }));
              return true;
            })();
            """.trimIndent(),
            null
        )
    }

    private fun extractInvoiceId(url: String): String? {
        val segments = runCatching { Uri.parse(url).pathSegments }.getOrNull() ?: return null
        val route = listOf("admin", "sales", "print-invoice")
        val routeStart = segments.windowed(route.size).indexOf(route)
        if (routeStart < 0) return null
        return segments.getOrNull(routeStart + route.size)?.takeIf { it.isNotBlank() }
    }

    fun openScanner() {
        IntentIntegrator(this).apply {
            setPrompt("Arahkan kamera ke QR code atau barcode")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
            initiateScan()
        }
    }

    @Deprecated("ZXing compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) notifyWeb("onBarcodeScanned", result.contents)
            else notifyWeb("onBarcodeScanCancelled", "")
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun selectPrinter() {
        openPrinterSettings()
    }

    @SuppressLint("MissingPermission")
    private fun showPrinterDialog() {
        val devices = printerManager.pairedPrinters()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Printer tidak ditemukan")
                .setMessage("Pairing printer ESC/POS melalui Pengaturan Bluetooth Android, lalu coba lagi.")
                .setPositiveButton("Buka Bluetooth") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("Tutup", null)
                .show()
            return
        }

        val labels = devices.map { "${it.name ?: "Printer"}\n${it.address}" }
        AlertDialog.Builder(this)
            .setTitle("Pilih printer 58 mm")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { _, index ->
                saveSelectedPrinter(devices[index])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun saveSelectedPrinter(device: BluetoothDevice) {
        printerManager.savePrinter(device.address)
        Toast.makeText(this, "Printer ${device.name ?: device.address} dipilih", Toast.LENGTH_SHORT).show()
        notifyWeb("onPrinterSelected", device.address)
    }

    fun printReceipt(json: String) {
        runOnUiThread {
            binding.nativePrintButton.isEnabled = false
            binding.nativePrintButton.text = "Mencetak…"
            binding.printerStatus.text = "Mengirim struk ke printer…"
        }
        printerExecutor.execute {
            runCatching {
                printerManager.printReceipt(json)
            }
                .onSuccess {
                    runOnUiThread {
                        binding.nativePrintButton.text = "Print Struk"
                        binding.nativePrintButton.isEnabled = true
                        binding.printerStatus.text = "Struk berhasil dicetak"
                        notifyWeb("onPrintSuccess", "ok")
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.nativePrintButton.text = "Print Struk"
                        binding.nativePrintButton.isEnabled = true
                        binding.printerStatus.text = "Gagal print: ${error.message}"
                        notifyWeb("onPrinterError", error.message ?: "Gagal mencetak")
                    }
                }
        }
    }

    fun getPrinterAddress(): String? = printerManager.savedPrinterAddress()

    private fun notifyWeb(callback: String, value: String) {
        val safeValue = JSONObject.quote(value)
        binding.webView.evaluateJavascript(
            "if (typeof window.$callback === 'function') window.$callback($safeValue);",
            null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        urlMonitorHandler.removeCallbacks(urlMonitor)
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        popupWebView?.destroy()
        popupWebView = null
        printerExecutor.shutdownNow()
        binding.webView.apply {
            removeJavascriptInterface(JS_BRIDGE_NAME)
            removeJavascriptInterface(LEGACY_JS_BRIDGE_NAME)
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val JS_BRIDGE_NAME = "AkasPOS"
        private const val LEGACY_JS_BRIDGE_NAME = "AkasPOS"
        private const val URL_MONITOR_INTERVAL_MS = 300L
        private const val KEY_LAST_URL = "last_trusted_url"
    }
}
