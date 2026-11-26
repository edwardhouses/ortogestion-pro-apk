# Auditoría técnica y clínica preliminar — OrtoGestión Pro

**Fecha:** 2026-11-24 (revisión ampliada de wrappers: 2026-11-25 UTC)  
**Rama:** `audit/ortogestion-pro-safe-work`  
**Fuente examinada:** `app/index.html`  
**Versión declarada:** `2.1.0-android`  
**Esquema declarado:** `orto_v2`  
**SHA del HTML en la evidencia de CI:** `379eec44590d6d802fecc0f6c5cd6817ba8be511f4ed70f7c5afae279e1bd046`

## Alcance y límites

Revisión estática del HTML, workflows y contenido de los ZIP Android/Windows. El control automatizado terminó correctamente en GitHub Actions, run `45533256770`: sintaxis JavaScript, pruebas incluidas en ambos wrappers e inspección de fuentes nativas. No se ejecutó la aplicación en un dispositivo Android ni en Windows, no se realizó pentest y no se hizo validación clínica/regulatoria. Este documento no es certificación clínica, de ciberseguridad, SaMD, HIPAA, GDPR ni de interoperabilidad.

## Arquitectura y compilación

- La fuente canónica está en `app/index.html`.
- Los wrappers examinados incluyen su propia copia de `index.html`; el workflow de build debe inyectar/comparar la fuente canónica para mantener paridad.
- Android y Windows siguen siendo wrappers/paquetes distintos; lo centralizado es el HTML, no el ejecutable final.
- La evidencia CI registra SHA-256 del HTML y ejecuta las pruebas estructurales disponibles. No prueba el comportamiento completo de la aplicación en runtime.

## Hallazgos confirmados

### 1. Seguridad web y ejecución

- La CSP del HTML permite `'unsafe-inline'` en `script-src` y `style-src`. Es comprensible en una aplicación monolítica con JS/CSS embebidos, pero reduce la protección frente a inyección. No se debe retirar hasta extraer scripts/estilos o diseñar una política con hashes/nonces y probar todos los flujos.
- La capa criptográfica del HTML usa Web Crypto, AES-GCM de 256 bits y PBKDF2-SHA256 de 200.000 iteraciones para derivación asociada a la contraseña. Los respaldos usan PBKDF2-SHA256 con 310.000 iteraciones y AES-GCM. Esto demuestra cifrado en la capa web, no protección de claves mediante Android Keystore ni seguridad del dispositivo completo.
- El puente `MiTiendaNative` se usa si expone los métodos esperados; en su ausencia hay fallback a almacenamiento web. La seguridad efectiva depende de la implementación y configuración del wrapper.
- Hay temporizador de cierre por 15 minutos de inactividad basado en eventos de interacción. No se encontró un manejador `visibilitychange`; el cierre al pasar a segundo plano y el comportamiento ante suspensión/muerte del proceso no están demostrados.
- Las imágenes se cifran como blobs AES-GCM, pero la ruta de descifrado las convierte a datos utilizables por la interfaz. Debe probarse el consumo máximo de memoria con historiales radiográficos grandes y dispositivos Android modestos.

### 2. Wrapper Android — revisión estática del ZIP

**Controles positivos observados:**
- Manifest con `android:allowBackup="false"` y `android:usesCleartextTraffic="false"`; solo declara permiso `INTERNET`.
- WebView con `allowFileAccess=false`, contenido mixto bloqueado, Safe Browsing activado, depuración WebView desactivada y cookies de terceros rechazadas.
- Carga local mediante `WebViewAssetLoader` en origen `https://appassets.androidplatform.net`.
- Intercepción de recursos externos que permite el host Supabase configurado y bloquea otros hosts.
- Navegación externa HTTPS/mailto se deriva a una aplicación externa; el resto de esquemas no se carga dentro de la WebView.
- El puente JavaScript expone una única función `saveBlob` para exportación, limita el tamaño del contenido y sanea el nombre del archivo. No se observó un puente genérico de lectura/escritura de datos.
- Las solicitudes de permisos web (cámara/micrófono/geolocalización) se deniegan.

**Riesgos y verificaciones pendientes:**
- `addJavascriptInterface` expone el objeto a los marcos de la WebView. Aunque la navegación y los hosts están restringidos, la CSP con `unsafe-inline` hace que la prevención de XSS sea especialmente importante. Recomendación: evaluar migración a `WebViewCompat.addWebMessageListener` con allowlist explícita de origen, o demostrar mediante pruebas que no puede existir contenido/iframe no confiable con acceso al puente.
- El callback de ciclo de vida solo pausa/reanuda WebView y temporizadores. No bloquea la sesión ni oculta datos al pasar a segundo plano. Debe añadirse un bloqueo de privacidad al perder foco/entrar en background, con reautenticación y pruebas de retorno, sin romper exportaciones pendientes.
- `allowContentAccess=true` está habilitado para el selector de archivos. Confirmar mediante pruebas que la selección solo concede los URI elegidos y que no permite acceso arbitrario a contenido local.
- El puente mantiene bytes de exportación en memoria hasta que el selector guarda/cancela; probar cancelación, rotación, salida de actividad y archivos grandes para evitar retención o pérdida de datos.
- No se encontró evidencia de Android Keystore para proteger una clave de cifrado de base de datos. El cifrado Web Crypto no sustituye ese control.
- Debe probarse el manejo de errores TLS, navegación a enlaces maliciosos, redirecciones y disponibilidad offline en dispositivos reales.

### 3. Wrapper Windows/Electron — revisión estática del ZIP

**Controles positivos observados:**
- `nodeIntegration=false`, `contextIsolation=true`, `sandbox=true`, `webSecurity=true` y `allowRunningInsecureContent=false`.
- No se declara preload ni un puente Node general para la página.
- El contenido se sirve mediante protocolo local `ortogestion://`; se valida la ruta resuelta antes de leer archivos.
- Las ventanas emergentes se deniegan; solo enlaces HTTPS se abren externamente.
- La navegación dentro de la ventana se limita al protocolo interno de la aplicación.

**Riesgos y verificaciones pendientes:**
- La ventana usa partición persistente `persist:ortogestion`. Debe documentarse qué datos/cookies almacena Electron y comprobarse que el cierre de sesión y el cierre de aplicación no dejan información sensible accesible a otro usuario del equipo.
- No se observó bloqueo por inactividad o al minimizar/cambiar de usuario en el wrapper Electron; verificar la cobertura real del bloqueo HTML y añadir bloqueo de privacidad nativo si procede.
- La ruta del protocolo local debe someterse a pruebas de traversal con codificación URL, separadores mixtos, rutas absolutas y enlaces simbólicos.
- El paquete declara arquitectura Windows x64. No se ha validado compatibilidad ARM64 ni firma/certificado de editor del EXE.
- La seguridad de Electron debe volver a comprobarse al actualizar Electron y dependencias; las pruebas actuales son smoke tests estructurales, no pruebas de explotación.

### 4. Consentimiento y trazabilidad

- El HTML guarda estados de consentimiento, marcas temporales, usuario y versión de texto, y marca `supportDeclaredByOperator`.
- Esa marca no equivale por sí sola a firma electrónica, documento firmado, identidad verificada ni evidencia independiente de que se entregó/aceptó el texto. No se debe presentar el consentimiento como legalmente validado hasta implementar y probar el flujo documental requerido en cada jurisdicción.
- La cadena de auditoría se calcula con SHA-256 y referencias al hash anterior, pero su almacenamiento local no equivale a un registro inmutable externo. Un atacante con control del almacenamiento del dispositivo podría alterar tanto registros como cadena local.

### 5. Roles y permisos

- Las funciones de autorización reconocen explícitamente los roles `ortodoncista`, `auxiliar` y `admin` según la operación.
- El rol `administracion` existe en la interfaz, pero no se incluye en las funciones centrales `esAdmin`, `canWriteClinical`, `canDeleteClinical` y `canManageUsers`. Puede ser una restricción intencional; hace falta aprobar una matriz de permisos antes de cambiarla. No se amplían privilegios por inferencia.

### 6. Referencias y cálculo clínico

- El código contiene conjuntos normativos, cálculo de Z, Moyers, Bolton y otras funciones cefalométricas/índices.
- El cálculo considera algunos datos de edad/dentición, pero la selección principal de normas se realiza por escuela/analizador; no se demuestra una estratificación validada completa por edad, sexo, biotipo y población.
- `AJUSTE_BIOTIPO` está vacío deliberadamente. Es correcto no inventar coeficientes sin fuente primaria y validación.
- Los umbrales y resultados deben presentarse como apoyo estadístico/cribado, no como diagnóstico automático ni sustituto del juicio clínico. Requieren validación por población, método de medición, calibración interobservador y bibliografía verificable.

### 7. Migración y compatibilidad

- El esquema actual es `orto_v2`; el código contiene una ruta condicional para intentar migrar datos de un almacén anterior `ortogestion_pro_v5`.
- No debe cambiarse el identificador de esquema ni activarse una migración masiva sin fixtures anonimizados/sintéticos, pruebas de restauración, copia previa y verificación de integridad.
- No se debe importar una base antigua en producción hasta validar apertura de pacientes, imágenes, visitas, mediciones y restauración completa.

## Cambios aplicados en esta rama

- Se añadió un control de calidad automatizado para comprobar sintaxis de scripts embebidos, ejecutar las pruebas incluidas en los wrappers e inspeccionar configuración/código nativo Android y Windows.
- Se añadió una corrección reproducible del wrapper Android en `native/android/harden_android.py`: sustituye `addJavascriptInterface` por `WebViewCompat.addWebMessageListener`, restringe el origen a `https://appassets.androidplatform.net`, valida de nuevo el origen y rechaza mensajes que no procedan del marco principal.
- Se añadió cierre de sesión nativo al entrar en estado detenido (`onStop`), invocando la función de cierre de sesión existente en el HTML. Se conserva una excepción mientras están activos el selector de archivos de importación o el diálogo de destino de exportación para no interrumpir esas operaciones.
- Se añadieron pruebas de regresión en `native/android/test_hardening.py` para verificar origen, marco principal, límites de tamaño, eliminación del puente antiguo y comportamiento de ciclo de vida.
- Para Windows/Electron se sustituyó el controlador de protocolo por validación estricta del esquema y host `ortogestion://app`, aceptación exclusiva de GET/HEAD, decodificación controlada de rutas y comprobación de `realpath` para bloquear traversal y escapes mediante enlaces simbólicos. Se mantienen las opciones `nodeIntegration=false`, `contextIsolation=true`, `sandbox=true` y la partición persistente para no perder IndexedDB/localStorage.
- Se activó `setContentProtection(true)`, una pantalla de privacidad al perder el foco (retirada al recuperar el foco) y cierre de sesión al minimizar o suspender el equipo. El cambio de foco no fuerza logout para no interrumpir selectores de archivos o diálogos nativos.
- Se añadieron pruebas Node para origen permitido, rutas válidas, traversal codificado, barras inversas y codificación URL malformada, además de comprobaciones estáticas de controles de Electron. El control de calidad `45534445092` terminó en verde en Linux; el job Windows también pasó las pruebas estructurales tras corregir la comparación de rutas canónicas propia de Windows.
- La ejecución GitHub Actions `45533964544` terminó en verde: pruebas estáticas y compilación real de APK debug correctiva. Artefacto de prueba: `OrtoGestion-Pro-2.1-security-test-apk` (ID `14986305660`, ~2,57 MB). No se instaló ni ejecutó en un teléfono durante esta validación.
- La ejecución `45533256770` terminó con éxito y publicó el artefacto `OrtoGestion-Audit-Quality-Evidence` (SHA-256 `a03180d73f9d315beffd0205f0b0d24d398cbeb70669ff396e7a0b827b6439f5`).
- No se cambian fórmulas clínicas, permisos de usuario, esquema de base de datos, cifrado ni datos. Esas modificaciones requieren especificación y pruebas de regresión que aún no están disponibles.

## Bloqueos antes de uso con pacientes reales

1. Pruebas funcionales manuales en Android y Windows: alta/edición/apertura/borrado de paciente, mediciones, imágenes, radiografías, visitas, inventario, backup y restore.
2. Pruebas de migración con bases sintéticas de cada esquema anterior, incluyendo archivos de imagen asociados.
3. Ejecutar pruebas manuales en Android y Windows del bloqueo de privacidad al pasar a segundo plano/minimizar/suspender, reautenticación y exposición de datos en vistas recientes.
4. Validar en dispositivo real el `WebMessageListener`, el origen/iframes y los flujos de exportación/importación, incluidos cancelación y archivos grandes.
5. Ejecutar pruebas de runtime del protocolo Electron (incluidos traversal y navegación) y comprobar la persistencia de IndexedDB/localStorage entre reinicios y sesiones de usuario.
6. Pruebas de seguridad de los wrappers: XSS, navegación, traversal, almacenamiento persistente, recuperación de cuenta, fuerza bruta, sesiones, manipulación local y cadena de auditoría.
7. Revisión clínica por ortodoncistas de cada fórmula, unidades, referencias, población, rangos y redondeos; pruebas con casos de referencia conocidos.
8. Definir jurisdicción y requisitos de privacidad, consentimiento, retención, exportación y respuesta a incidentes.
9. Confirmar que APK/EXE resultantes se instalan y ejecutan en dispositivos reales y que una actualización no sobrescribe ni pierde datos.

**Conclusión:** el repositorio tiene una base automatizada útil, una fuente HTML central y wrappers con varias defensas relevantes. Sin embargo, la evidencia actual no permite declarar la aplicación clínicamente validada ni lista para manejar historias clínicas reales. Mantener esta rama como preparación y validación; no integrar a `main` ni distribuir una nueva versión clínica hasta cerrar los bloqueos.
