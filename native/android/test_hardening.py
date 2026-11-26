#!/usr/bin/env python3
"""Regression checks for Android lifecycle privacy and origin-scoped bridge."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
p = root / "app/src/main/java/com/ortogestion/pro/MainActivity.kt"
s = p.read_text(encoding="utf-8")
checks = {
    "legacy JavascriptInterface removed": "addJavascriptInterface" not in s and "addJavascriptInterface(" not in s and "@JavascriptInterface" not in s,
    "WebMessageListener used": "WebViewCompat.addWebMessageListener" in s,
    "exact app origin allowlist": 'setOf("https://appassets.androidplatform.net")' in s,
    "main-frame restriction": "if (!isMainFrame" in s,
    "origin scheme and host validation": 'sourceOrigin.scheme != "https"' in s and 'sourceOrigin.host != "appassets.androidplatform.net"' in s,
    "export bridge uses postMessage": ".postMessage(JSON.stringify({type:'saveBlob'" in s,
    "export payload bounded": "bytes.size > 50 * 1024 * 1024" in s,
    "background lifecycle closes session": "override fun onStop()" in s and "cerrarSesion()" in s,
    "file chooser exempt from background logout": "fileChooserCallback == null && pendingExportBytes == null" in s,
    "webview debug remains disabled": "WebView.setWebContentsDebuggingEnabled(false)" in s,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Fallaron controles: " + ", ".join(failed))
