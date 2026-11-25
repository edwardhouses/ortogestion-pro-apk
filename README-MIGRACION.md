# OrtoGestión Pro — transición a fuente HTML única

## Fuente canónica
- Ruta: `app/index.html`
- Tamaño: 290,182 bytes
- SHA-256 inicial: `379eec44590d6d802fecc0f6c5cd6817ba8be511f4ed70f7c5afae279e1bd046`

Esta fuente es una copia byte por byte del HTML auditado en el artefacto de solo lectura del repositorio. No se ha modificado su contenido.

## Qué conserva
Los workflows extraen los ZIP actuales de Android y Windows y sustituyen únicamente el HTML incluido en cada wrapper antes de ejecutar las pruebas y compilar.

Android conserva:
- applicationId `com.ortogestion.pro`
- namespace `com.ortogestion.pro`
- versionCode `21`, versionName `2.1.0`
- compileSdk/targetSdk `35`, minSdk `26`
- Kotlin `2.0.21`, Android Gradle Plugin `8.7.3`, Gradle `8.9`, Java `17`
- MainActivity, Manifest, WebView, permisos y recursos del ZIP original

Windows conserva:
- Electron `^39.0.0`, electron-builder `^26.0.12`
- appId `com.ortogestion.pro`, producto `OrtoGestion-Pro`
- empaquetado `portable` x64, ASAR y `main.js`
- configuración de seguridad de BrowserWindow del ZIP original

## Automatización
`.github/workflows/build-all.yml`:
- se ejecuta manualmente (`workflow_dispatch`)
- se ejecuta al cambiar `app/index.html`, cualquiera de los dos ZIP de wrappers o los workflows
- compila APK y EXE en paralelo
- carga ambos artefactos en la misma ejecución de GitHub Actions

Los workflows individuales `android-apk.yml` y `windows-portable.yml` quedan disponibles para ejecuciones manuales de un solo formato y también inyectan la fuente canónica.

## Instalación en el repositorio
1. No borres los ZIP originales.
2. Añade `app/index.html` al repositorio.
3. Reemplaza los tres workflows de `.github/workflows/` por los incluidos aquí.
4. Confirma los cambios en la rama principal.
5. Ejecuta `Build OrtoGestion Pro APK and Windows Portable` desde Actions o modifica `app/index.html`.
6. Comprueba que ambos trabajos terminen en verde y descarga los dos artefactos.

## Seguridad de la migración
- No modifica los ZIP originales.
- No altera el HTML durante la migración.
- No cambia identificadores, versiones ni configuraciones de plataforma.
- No instala ni migra bases de datos de pacientes.
- Los wrappers siguen existiendo como proyectos de plataforma; lo único que se centraliza es el HTML.
- No reemplaza automáticamente los binarios ya instalados. La APK y el EXE nuevos deben probarse antes de distribuirlos.
