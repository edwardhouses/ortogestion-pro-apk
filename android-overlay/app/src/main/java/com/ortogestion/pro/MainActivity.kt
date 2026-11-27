package com.ortogestion.pro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportBytes: ByteArray? = null
    private var pendingWebPermission: PermissionRequest? = null
    private lateinit var nubeBridge: OrtoNubeBridge

    private val driveAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (::nubeBridge.isInitialized) nubeBridge.onAuthorizationResult(result.data, result.resultCode == Activity.RESULT_OK)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request != null) {
            if (granted && request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else request.deny()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            callback?.onReceiveValue(uris)
        } else callback?.onReceiveValue(null)
    }

    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("No se pudo abrir el destino")
                Toast.makeText(this, "Respaldo guardado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo guardar el archivo", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadsImagesAutomatically = true
            blockNetworkImage = false
            safeBrowsingEnabled = true
            saveFormData = false
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString OrtoGestionProAndroid/2.2"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        WebView.setWebContentsDebuggingEnabled(false)
        webView.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Usa la opción Exportar de OrtoGestión para guardar el respaldo", Toast.LENGTH_LONG).show()
        }
        webView.addJavascriptInterface(DownloadBridge(), "AndroidFileBridge")
        nubeBridge = OrtoNubeBridge(this, webView) { request -> driveAuthorization.launch(request) }
        webView.addJavascriptInterface(nubeBridge, "OrtoNube")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    filePicker.launch(intent)
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val origin = request.origin
                val trustedOrigin = origin.scheme == "https" && origin.host == "appassets.androidplatform.net"
                val onlyCamera = request.resources.isNotEmpty() && request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (!trustedOrigin || !onlyCamera || pendingWebPermission != null) {
                    request.deny()
                    return
                }
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingWebPermission = request
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.confirm(); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                result?.cancel(); return true
            }
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url) ?: run {
                    val host = request.url.host?.lowercase(Locale.ROOT)
                    if (host == "cnxnytmpdtfrtkrznclc.supabase.co") null
                    else android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") return false
                if (uri.scheme == "https" || uri.scheme == "mailto") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "No hay una aplicación para abrir este enlace", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installBlobDownloadHandler()
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(function(){var d=document.getElementById('appDialogModal');if(d&&!d.hidden){(document.getElementById('appDialogCancel').hidden?document.getElementById('appDialogOk'):document.getElementById('appDialogCancel')).click();return 'dialog';}var m=document.querySelector('.modal:not(.hidden)');if(m){m.classList.add('hidden');return 'modal';}var v=document.querySelector('.view.active');if(v&&v.id!=='view-dashboard'){var b=document.querySelector('.nav-btn[data-view=\\\"dashboard\\\"]');if(b)b.click();return 'home';}return 'exit';})()") { result ->
                    if (result == "\"exit\"") finish()
                }
            }
        })
    }

    private fun installBlobDownloadHandler() {
        val js = """
            (function(){
              if(window.__ortoBlobBridgeInstalled)return;window.__ortoBlobBridgeInstalled=true;
              document.addEventListener('click',function(ev){
                var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;
                if(!a||!a.href||!a.href.startsWith('blob:'))return;
                ev.preventDefault();ev.stopImmediatePropagation();
                fetch(a.href).then(function(r){return r.blob();}).then(function(blob){
                  var reader=new FileReader();reader.onload=function(){
                    var data=String(reader.result||'').split(',')[1]||'';
                    if(window.AndroidFileBridge)window.AndroidFileBridge.saveBlob(data,a.download||'ortogestion-export.bin',blob.type||'application/octet-stream');
                  };reader.readAsDataURL(blob);
                }).catch(function(){ });
              },true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBlob(base64: String, filename: String, mimeType: String) {
            if (base64.length > 70_000_000) return
            val safeName = filename.substringAfterLast('/').substringAfterLast(92.toChar()).filter { c -> c.isLetterOrDigit() || c in "._-" }.take(100).ifBlank { "ortogestion-export.bin" }
                .ifBlank { "ortogestion-export.bin" }
            val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { return }
            if (bytes.size > 50 * 1024 * 1024) return
            runOnUiThread {
                pendingExportBytes = bytes
                savePicker.launch(safeName)
            }
        }
    }

    override fun onPause() { webView.onPause(); webView.pauseTimers(); super.onPause() }
    override fun onResume() { super.onResume(); webView.onResume(); webView.resumeTimers() }
    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null); fileChooserCallback = null
        pendingWebPermission?.deny(); pendingWebPermission = null
        webView.removeJavascriptInterface("OrtoNube")
        webView.removeJavascriptInterface("AndroidFileBridge")
        webView.destroy()
        super.onDestroy()
    }
}
