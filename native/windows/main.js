"use strict";

const { app, BrowserWindow, protocol, shell, powerMonitor } = require("electron");
const fs = require("node:fs/promises");
const path = require("node:path");
const { isTrustedAppUrl, resolveAppAsset } = require("./security");

protocol.registerSchemesAsPrivileged([
  {
    scheme: "ortogestion",
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true
    }
  }
]);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ico": "image/x-icon"
};

async function createWindow() {
  const root = path.join(app.getAppPath(), "www");
  await protocol.handle("ortogestion", async (request) => {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405 });
    }
    let url;
    try {
      url = new URL(request.url);
    } catch {
      return new Response("Bad request", { status: 400 });
    }
    if (!isTrustedAppUrl(url)) return new Response("Forbidden", { status: 403 });

    try {
      const filePath = await resolveAppAsset(root, url.pathname);
      const data = request.method === "HEAD" ? null : await fs.readFile(filePath);
      return new Response(data, {
        headers: {
          "Content-Type": MIME[path.extname(filePath).toLowerCase()] || "application/octet-stream",
          "X-Content-Type-Options": "nosniff",
          "Cache-Control": "no-store"
        }
      });
    } catch {
      return new Response("Not found", { status: 404 });
    }
  });

  const win = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 960,
    minHeight: 640,
    show: true,
    autoHideMenuBar: true,
    backgroundColor: "#f1f5f9",
    webPreferences: {
      // Persistente porque IndexedDB/localStorage son el almacén local de la aplicación.
      partition: "persist:ortogestion",
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false
    }
  });

  // Evita que capturas del sistema operativo expongan datos clínicos de la ventana.
  win.setContentProtection(true);

  const privacyShield = `
    html::after {
      content: "OrtoGestión Pro — sesión protegida";
      position: fixed;
      inset: 0;
      z-index: 2147483647;
      display: grid;
      place-items: center;
      background: #101827;
      color: #ffffff;
      font: 600 20px system-ui, sans-serif;
      pointer-events: none;
    }
  `;
  let shieldKey = null;
  const showPrivacyShield = async () => {
    if (win.isDestroyed() || shieldKey !== null) return;
    try { shieldKey = await win.webContents.insertCSS(privacyShield); } catch { /* ventana cerrándose */ }
  };
  const hidePrivacyShield = () => {
    if (win.isDestroyed() || shieldKey === null) return;
    const key = shieldKey;
    shieldKey = null;
    win.webContents.removeInsertedCSS(key).catch(() => {});
  };
  const lockSession = () => {
    if (win.isDestroyed()) return;
    win.webContents.executeJavaScript(
      "(function(){if(typeof currentUser!=='undefined'&&currentUser&&typeof cerrarSesion==='function'){cerrarSesion();}})();",
      true
    ).catch(() => {});
  };

  // Alt-tab / pérdida de foco: ocultar temporalmente la interfaz sin interferir con diálogos nativos.
  win.on("blur", showPrivacyShield);
  win.on("focus", hidePrivacyShield);
  // Minimizar o suspender el equipo cierra la sesión; los datos locales permanecen almacenados.
  win.on("minimize", lockSession);
  powerMonitor.on("suspend", lockSession);

  win.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const target = new URL(url);
      if (target.protocol === "https:" && !target.username && !target.password) {
        shell.openExternal(target.toString()).catch(() => {});
      }
    } catch { /* URL inválida: bloquear */ }
    return { action: "deny" };
  });

  win.webContents.on("will-navigate", (event, url) => {
    if (!isTrustedAppUrl(url)) event.preventDefault();
  });

  win.on("closed", () => {
    powerMonitor.removeListener("suspend", lockSession);
  });

  await win.loadURL("ortogestion://app/index.html");
}

app.whenReady().then(createWindow).catch((error) => {
  console.error("No se pudo iniciar OrtoGestión Pro:", error);
  app.quit();
});
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
