# Auditoría técnica y clínica preliminar — OrtoGestión Pro

**Fecha:** 2026-11-24  
**Rama:** `audit/ortogestion-pro-safe-work`  
**Fuente examinada:** `app/index.html`  
**Versión declarada:** `2.1.0-android`  
**Esquema declarado:** `orto_v2`  
**SHA del blob examinado:** `5c8700f0d27ebfa62712d2773b999173892fed8e`

## Alcance y límites

Revisión estática del HTML y de los workflows del repositorio. No se ejecutó la aplicación en Android ni Windows, no se accedió a una base de pacientes, no se realizó pentest y no se verificó el código nativo dentro de los ZIP. Este documento no es certificación clínica, de ciberseguridad, SaMD, HIPAA, GDPR ni de interoperabilidad.

## Arquitectura y compilación

- La fuente canónica está en `app/index.html`.
- `build-all.yml` inyecta esa fuente en los wrappers Android y Windows, compara el archivo copiado y ejecuta las pruebas declaradas por ambos paquetes antes de compilar.
- Los wrappers siguen siendo dos paquetes separados; la arquitectura no es literalmente un único archivo ejecutable. Lo centralizado es el HTML.
- Los workflows de build existentes conceden `contents: read`, usan Actions para generar artefactos y no requieren un token personal para compilar.

## Hallazgos confirmados

### 1. Seguridad web y ejecución

- La CSP permite `'unsafe-inline'` en `script-src` y `style-src`. Es comprensible en una aplicación monolítica con JS/CSS embebidos, pero reduce la protección frente a inyección. No se debe retirar hasta extraer scripts/estilos o diseñar una política con hashes/nonces y probar todos los flujos.
- La capa criptográfica del HTML usa Web Crypto, AES-GCM de 256 bits y PBKDF2-SHA256 con 200.000 iteraciones para derivación asociada a la contraseña. Los respaldos usan PBKDF2-SHA256 con 310.000 iteraciones y AES-GCM. Esto demuestra cifrado en la capa web, no protección de claves mediante Android Keystore ni seguridad del dispositivo completo.
- El puente `MiTiendaNative` se usa si expone los métodos esperados; en su ausencia hay fallback a almacenamiento web. La seguridad efectiva depende de la implementación y configuración del wrapper.
- Hay temporizador de cierre por 15 minutos de inactividad basado en eventos de interacción. No se encontró un manejador `visibilitychange`; el cierre al pasar a segundo plano y el comportamiento ante suspensión/muerte del proceso no están demostrados.
- Las imágenes se cifran como blobs AES-GCM, pero la ruta de descifrado las convierte a datos utilizables por la interfaz. Debe probarse el consumo máximo de memoria con historiales radiográficos grandes y dispositivos Android modestos.

### 2. Consentimiento y trazabilidad

- El HTML guarda estados de consentimiento, marcas temporales, usuario y versión de texto, y marca `supportDeclaredByOperator`.
- Esa marca no equivale por sí sola a firma electrónica, documento firmado, identidad verificada ni evidencia independiente de que se entregó/aceptó el texto. No se debe presentar el consentimiento como legalmente validado hasta implementar y probar el flujo documental requerido en cada jurisdicción.
- La cadena de auditoría se calcula con SHA-256 y referencias al hash anterior, pero su almacenamiento local no equivale a un registro inmutable externo. Un atacante con control del almacenamiento del dispositivo podría alterar tanto registros como cadena local.

### 3. Roles y permisos

- Las funciones de autorización reconocen explícitamente los roles `ortodoncista`, `auxiliar` y `admin` según la operación.
- El rol `administracion` existe en la interfaz, pero no se incluye en las funciones centrales `esAdmin`, `canWriteClinical`, `canDeleteClinical` y `canManageUsers`. Puede ser una restricción intencional; hace falta aprobar una matriz de permisos antes de cambiarla. No se amplían privilegios por inferencia.

### 4. Referencias y cálculo clínico

- El código contiene conjuntos normativos, cálculo de Z, Moyers, Bolton y otras funciones cefalométricas/índices.
- El cálculo considera algunos datos de edad/dentición, pero la selección principal de normas se realiza por escuela/analizador; no se demuestra una estratificación validada completa por edad, sexo, biotipo y población.
- `AJUSTE_BIOTIPO` está vacío deliberadamente. Es correcto no inventar coeficientes sin fuente primaria y validación.
- Los umbrales y resultados deben presentarse como apoyo estadístico/cribado, no como diagnóstico automático ni sustituto del juicio clínico. Requieren validación por población, método de medición, calibración interobservador y bibliografía verificable.

### 5. Migración y compatibilidad

- El esquema actual es `orto_v2`; el código contiene una ruta condicional para intentar migrar datos de un almacén anterior `ortogestion_pro_v5`.
- No debe cambiarse el identificador de esquema ni activarse una migración masiva sin fixtures anonimizados/sintéticos, pruebas de restauración, copia previa y verificación de integridad.
- No se debe importar una base antigua en producción hasta validar apertura de pacientes, imágenes, visitas, mediciones y restauración completa.

## Cambios aplicados en esta rama

- Se añade un control de calidad automatizado para comprobar sintaxis de los scripts embebidos, ejecutar las pruebas que ya vienen con los wrappers y generar un informe de auditoría en GitHub Actions.
- No se cambian fórmulas clínicas, permisos de usuario, esquema de base de datos, cifrado ni datos. Esas modificaciones requieren especificación y pruebas de regresión que aún no están disponibles.

## Bloqueos antes de uso con pacientes reales

1. Pruebas funcionales manuales en Android y Windows: alta/edición/apertura/borrado de paciente, mediciones, imágenes, radiografías, visitas, inventario, backup y restore.
2. Pruebas de migración con bases sintéticas de cada esquema anterior, incluyendo archivos de imagen asociados.
3. Revisión de los wrappers nativos: permisos, depuración WebView, navegación externa, acceso a archivos, backup del sistema, cleartext traffic, almacenamiento de claves, lifecycle/background y exposición del puente JavaScript.
4. Revisión de seguridad independiente: XSS, almacenamiento, recuperación de cuenta, fuerza bruta, sesiones, manipulación local y cadena de auditoría.
5. Revisión clínica por ortodoncistas de cada fórmula, unidades, referencias, población, rangos y redondeos; pruebas con casos de referencia conocidos.
6. Definir jurisdicción y requisitos de privacidad, consentimiento, retención, exportación y respuesta a incidentes.
7. Confirmar que los APK/EXE resultantes se instalan y ejecutan en dispositivos reales y que una actualización no sobrescribe ni pierde datos.

**Conclusión:** el repositorio tiene una base automatizada útil y una fuente HTML central, pero no hay evidencia suficiente para declarar la aplicación clínicamente validada o lista para manejar historias clínicas reales. Mantener esta rama como preparación y validación; no integrar a `main` ni distribuir una nueva versión clínica hasta cerrar los bloqueos.
