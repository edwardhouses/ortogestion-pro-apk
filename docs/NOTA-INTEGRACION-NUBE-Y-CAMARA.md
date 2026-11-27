# Integración de la nube y la cámara (v2.2, OrtoGestión Pro)

El HTML de la v2.2 ya trae todo el lado web: icono de nube en el header,
tarjeta en Configuración, panel de nube, escáner QR (cámara + foto) y
validación diaria de licencia. Lo que falta vive en el **envoltorio
nativo (los ZIP)**, que es donde hay que hacer estos cambios. El HTML sin
puente se degrada solo: el panel dice «solo está disponible en la APK» y
el QR por foto sigue funcionando.

Archivos de este lote en el repo:

- `app/index.html` — v2.2 (el lote completo, ya en `main`).
- `docs/OrtoNube.kt` — puente nativo de Google Drive (este documento lo
  explica).
- `docs/NOTA-INTEGRACION-NUBE-Y-CAMARA.md` — este archivo.

## 1. El puente OrtoNube (Android)

Copiar `docs/OrtoNube.kt` a:

```
OrtoGestion-Pro-Android/app/src/main/java/com/ortogestion/pro/OrtoNube.kt
```

No necesita librerías nuevas: usa solo SDK de Android + `org.json` +
`HttpURLConnection`. No usa corrutinas ni OkHttp (no están en el proyecto).

### 1.1 Registrarlo en MainActivity.kt

Campo nuevo en `MainActivity`:

```kotlin
private lateinit var ortoNube: OrtoNube
```

Junto a la línea existente:

```kotlin
webView.addJavascriptInterface(DownloadBridge(), "AndroidFileBridge")
```

añadir:

```kotlin
ortoNube = OrtoNube(this, webView)
webView.addJavascriptInterface(ortoNube, "OrtoNube")
```

### 1.2 Recibir la vuelta de Google (OAuth)

En `onCreate`, después de `webView.loadUrl(...)`, añadir:

```kotlin
ortoNube.onAuthIntent(intent)
```

Y este override en la clase (hace falta porque la vuelta llega con la app
ya abierta):

```kotlin
override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (::ortoNube.isInitialized) ortoNube.onAuthIntent(intent)
}
```

(`Intent` ya está importado en MainActivity.kt.)

### 1.3 Cámara para el QR

Hoy `onPermissionRequest` niega todo (`request?.deny()`). La foto del QR
ya funciona (usa el selector de archivos, que existe); la cámara necesita:

**Manifest** — añadir el permiso:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

**MainActivity** — pedir el permiso en ejecución y conceder solo el vídeo:

```kotlin
private var pendingPermissionRequest: PermissionRequest? = null

private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    val req = pendingPermissionRequest
    pendingPermissionRequest = null
    if (granted) req?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
    else req?.deny()
}
```

Y sustituir el `onPermissionRequest` actual por:

```kotlin
override fun onPermissionRequest(request: PermissionRequest?) {
    if (request?.resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            pendingPermissionRequest = request
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    } else {
        request?.deny()
    }
}
```

(`checkSelfPermission` existe en Activity; `ActivityResultContracts` ya
está importado.)

## 2. Cambios en AndroidManifest.xml

Dentro de `<activity ...>` añadir `android:launchMode="singleTask"` (para
que la vuelta de Google no abra una segunda MainActivity encima) y este
intent-filter junto al del launcher:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="com.ortogestion.pro" android:path="/oauth2callback" />
</intent-filter>
```

Y el permiso de cámara del punto 1.3. No tocar nada más: `allowBackup`
se queda en `false`, `FLAG_SECURE` se queda (bloquearlo impediría las
capturas), y la lista blanca de `shouldInterceptRequest` **no** afecta a
la nube: el puente usa `HttpURLConnection` directo, no el WebView, y el
OAuth ocurre en el navegador del sistema.

## 3. Versión (en los ZIP, no en el HTML)

El HTML ya dice v2.2, pero la APK/EXE seguirán diciendo 2.1.0 hasta
cambiarlo aquí:

- `OrtoGestion-Pro-Android/app/build.gradle.kts`:
  `versionCode = 21` → `22`, `versionName = "2.1.0"` → `"2.2.0"`.
- `MainActivity.kt`, `userAgentString`: `OrtoGestionProAndroid/2.1` →
  `OrtoGestionProAndroid/2.2`.
- Opcional: `.github/workflows/build-all.yml` sube el artefacto como
  `OrtoGestion-Pro-2.1-debug-apk`; renombrarlo a `2.2` es 1 línea.

## 4. Google Cloud Console (para que la nube funcione)

Un proyecto por app (la pantalla de permiso muestra el nombre del
proyecto). Pasos:

1. **Proyecto nuevo** en https://console.cloud.google.com → nombre
   «OrtoGestión Pro».
2. **API y servicios → Biblioteca** → buscar y habilitar
   «Google Drive API».
3. **Pantalla de consentimiento OAuth** → tipo **Externo** → nombre de la
   app, correo de asistencia. Alcances (scopes): `drive.file`,
   `openid`, `email`. Estos tres **no son sensibles**: basta **publicar**
   la app — no exige verificación de Google, no sale la pantalla de
   «app no verificada» y no hay límite de 100 usuarios. En modo Prueba
   habría que agregarse como usuario de prueba o el inicio da Error 403.
4. **Credenciales → Crear credenciales → ID de cliente de OAuth** →
   tipo **Aplicación para Android** → nombre del paquete
   `com.ortogestion.pro` + huella digital **SHA-1** de la llave de firma.
   (El cliente Android no lleva URI de redirección que registrar: Google
   usa `com.ortogestion.pro:/oauth2callback`, que es justo el
   `REDIRECT_URI` del puente.)
5. Copiar el client ID (termina en `.apps.googleusercontent.com`) y
   pegarlo en `GOOGLE_CLIENT_ID` en `OrtoNube.kt`, en lugar de
   `PONER_CLIENT_ID_AQUI`. Sin eso el HTML muestra «aún no está
   configurada».

### ⚠️ El punto crítico: el SHA-1 del debug no es estable

El build de Actions es **DEBUG** y usa el keystore debug que genera el
runner en cada ejecución (las máquinas son efímeras): **el SHA-1 cambia
en cada build**, así que el cliente OAuth registrado con un SHA-1 no
coincidirá con la APK del siguiente build y Google rechazará el inicio
de sesión. Para que la nube funcione de verdad hace falta **build
release con un keystore propio y guardado** (como la llave de firma de
sus otras apps), y registrar el SHA-1 de **ese** keystore. Mientras la
APK salga de Actions en debug, la nube quedará en «aún no está
configurada» o fallará al conectar aunque el client ID esté puesto.

Cómo sacar el SHA-1 de un keystore:

```text
keytool -list -v -keystore <ruta-del-keystore> -alias <alias> -storepass <clave>
```

## 5. Windows (Electron)

El puente es solo Android. En Windows el panel de nube seguirá diciendo
«solo está disponible en la APK» (el HTML lo prevé). Equiparar Windows
exigiría su propio puente en `main.js`; queda fuera de este lote.

## 6. Banderas de este lote (cambios de conducta)

- **Validación diaria de licencia (NUEVO):** hasta hoy la app NO
  revalidaba al abrir (la función de 30 días existía pero nadie la
  llamaba). Desde la v2.2 valida 1 vez al día al abrir y reintenta cada
  5 min hasta lograrlo. Ante error transitorio la activación local queda
  intacta, sin fecha límite (como Mi Tienda); solo una respuesta clara
  del servidor (revocada/vencida/no encontrada) pasa la app a modo
  lectura. La licencia offline por token se re-verifica solo en local.
  Si no se quiere, se revierte quitando la llamada a
  `ortoArmarRevalidacion()` en `startApp` (1 línea en `app/index.html`).
- **Los 41 `<select>` nativos ahora son selector propio** (diseño grande,
  como SweetCostPro v2.0.39). Las 14 fechas se quedan nativas.
- **Los 72 campos numéricos ya no son `type="number"`**: son
  `type="text" inputmode="decimal"` con selección total al enfocar.
  El guardián de rango bloquea el envío con valores fuera de rango.
- **Versión en los ZIP:** ver punto 3.

## 7. Prueba en vivo (cuando la APK esté instalada)

1. Activación: botón «📷 Escanear código QR» junto a la licencia offline
   → por foto (sin permiso) y por cámara (pide el permiso una vez).
2. Nube: icono ☁️ en el header (gris tachado sin conectar, verde
   conectado) → Guardar (pide contraseña de 12+ caracteres, 2 veces) →
   la copia aparece en la lista → Restaurar (pide la misma contraseña)
   → Borrar (va a la papelera de Drive, 30 días) → Desconectar.
3. Pickers: abrir varios selects (Rol, Sexo, Categoría, Producto) y
   comprobar que se ven grandes y eligen bien.
4. Punto 10: tocar un campo de costo con valor y comprobar que queda
   todo seleccionado.
5. Ripple: tocar botones y ver la onda.
6. Licencia: cerrar y abrir la app al día siguiente y comprobar que no
   pide nada raro (la revalidación es silenciosa si todo va bien).
