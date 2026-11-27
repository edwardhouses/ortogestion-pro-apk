# OrtoGestión Pro Android v2.2 — integración en rama aislada

## Rama de trabajo
`feature/android-v22-drive-camera-gis`

Esta implementación se mantiene separada de `main`. El wrapper ZIP original y el HTML clínico canónico no se modifican. El workflow extrae el ZIP y aplica un overlay de fuentes Android versionadas en el repositorio.

## Cambios incluidos en el overlay

1. Puente nativo `OrtoNubeBridge` conectado al contrato JavaScript que ya existe en `app/index.html`.
2. Google Identity Services `AuthorizationClient` para solicitar el permiso Drive solo cuando el usuario lo necesita.
3. Alcance mínimo `drive.file`; no se usa OAuth dentro de WebView, Client Secret ni refresh token almacenado por la app.
4. Listar, crear carpeta, subir mediante sesión resumible, descargar y enviar a papelera respaldos `.ogbackup` cifrados.
5. Permiso CAMERA en manifiesto y solicitud en tiempo de ejecución. El WebView solo recibe el recurso de vídeo y únicamente desde el origen local de la app.
6. Se conserva la elección de fotografía/archivo como alternativa al escaneo por cámara.
7. Versión Android 2.2.0 / versionCode 22 y artefacto APK v2.2.
8. Pruebas estáticas adicionales y compilación en GitHub Actions.

## Archivos principales

- `android-overlay/app/src/main/java/com/ortogestion/pro/MainActivity.kt`
- `android-overlay/app/src/main/java/com/ortogestion/pro/OrtoNubeBridge.kt`
- `android-overlay/app/src/main/AndroidManifest.xml`
- `android-overlay/app/build.gradle.kts`
- `.github/workflows/android-apk.yml`

## Configuración que todavía depende del propietario del proyecto

Google Drive no se puede habilitar para una cuenta real hasta configurar Google Cloud:

- Habilitar Google Drive API y completar la pantalla de consentimiento.
- Crear una credencial OAuth tipo Android con el paquete y SHA-1 correctos.
- La variante de pruebas v2.2 usa `com.ortogestion.pro.v22` para instalarse junto a la v2.1 sin sobrescribir sus datos; release usa `com.ortogestion.pro`.
- La firma debug de los runners hospedados es efímera. Para uso continuado se necesita una clave release estable, guardada como GitHub Actions Secrets. Nunca se debe subir una clave privada al repositorio ni enviarla por chat.

La guía detallada está en `docs/ANDROID-V22-GOOGLE-DRIVE-CAMERA.md`.

## Validación pendiente

Una compilación verde y las pruebas estáticas no sustituyen una prueba física. Tras instalar la APK en un teléfono Android, habrá que verificar:
- consentimiento/cancelación de Google;
- crear carpeta y guardar, listar, descargar/restaurar y eliminar una copia cifrada;
- denegar y conceder el permiso de cámara;
- escanear un QR de licencia válido y comprobar la alternativa por fotografía.

No se debe considerar activado Drive ni validado el escáner hasta completar esas pruebas en dispositivo real. No se fusiona a `main` sin aprobación expresa.
