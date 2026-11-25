#!/usr/bin/env python3
"""Apply the reviewed Android security hardening to the extracted wrapper."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
target = root / "app/src/main/java/com/ortogestion/pro/MainActivity.kt"
source = target.read_text(encoding="utf-8")

def once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count == 0:
        if new in source:
            return
        raise SystemExit(f"No se encontró el bloque esperado: {label}")
    if count != 1:
        raise SystemExit(f"Se esperaba una sola coincidencia para {label}; encontradas: {count}")
    source = source.replace(old, new, 1)

once("import android.webkit.JavascriptInterface\n", "import org.json.JSONObject\n", "import del puente antiguo")
once("import androidx.webkit.WebViewAssetLoader\n", "import androidx.webkit.WebViewAssetLoader\nimport androidx.webkit.WebViewCompat\n", "import WebViewCompat")
once('        webView.addJavascriptInterface(DownloadBridge(), "AndroidFileBridge")\n', """        // Puente limitado al origen local de la aplicación y a su documento principal.
        WebViewCompat.addWebMessageListener(
            webView,
            "AndroidFileBridge",
            setOf("https://appassets.androidplatform.net")
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            handleBridgeMessage(message.data ?: "", sourceOrigin, isMainFrame)
        }
""", "registro del puente restringido por origen")
once("if(window.AndroidFileBridge)window.AndroidFileBridge.saveBlob(data,a.download||'ortogestion-export.bin',blob.type||'application/octet-stream');", "if(window.AndroidFileBridge)window.AndroidFileBridge.postMessage(JSON.stringify({type:'saveBlob',base64:data,filename:a.download||'ortogestion-export.bin',mimeType:blob.type||'application/octet-stream'}));", "envío de exportación por WebMessage")
start = source.find("    inner class DownloadBridge {")
end = source.find("    override fun onPause()", start)
if start < 0 or end < 0:
    if "private fun handleBridgeMessage(" not in source:
        raise SystemExit("No se encontró el bloque DownloadBridge/onPause esperado")
else:
    replacement = """    private fun handleBridgeMessage(message: String, sourceOrigin: Uri, isMainFrame: Boolean) {
        // Defensa en profundidad: WebMessageListener ya filtra el origen; aquí se valida de nuevo.
        if (!isMainFrame ||
            sourceOrigin.scheme != "https" ||
            sourceOrigin.host != "appassets.androidplatform.net" ||
            sourceOrigin.port != -1
        ) return
        if (message.length > 70_000_000) return

        val payload = try { JSONObject(message) } catch (_: Exception) { return }
        if (payload.optString("type") != "saveBlob") return
        val base64 = payload.optString("base64")
        if (base64.isBlank() || base64.length > 70_000_000) return

        val filename = payload.optString("filename", "ortogestion-export.bin")
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\\\')
            .filter { it.isLetterOrDigit() || it in "._-" }
            .take(100)
            .ifBlank { "ortogestion-export.bin" }
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { return }
        if (bytes.isEmpty() || bytes.size > 50 * 1024 * 1024) return

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            pendingExportBytes = bytes
            try {
                savePicker.launch(safeName)
            } catch (_: Exception) {
                pendingExportBytes = null
                Toast.makeText(this, "No se pudo iniciar la exportación", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        // No cerrar sesión mientras Android muestra el selector autorizado de archivos.
        if (fileChooserCallback == null && pendingExportBytes == null && ::webView.isInitialized) {
            webView.evaluateJavascript(
                "(function(){if(typeof currentUser!=='undefined'&&currentUser&&typeof cerrarSesion==='function'){cerrarSesion();}})();",
                null
            )
        }
        super.onStop()
    }

"""
    source = source[:start] + replacement + source[end:]

target.write_text(source, encoding="utf-8")
print(f"Android hardening aplicado: {target}")
