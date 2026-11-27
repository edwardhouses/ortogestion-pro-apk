# Integración Android v2.2 — ejecución segura por etapas

## Rama de trabajo
`feature/android-drive-oauth-qr-v22`

Esta rama parte de `main`. No modifica `main`, el HTML clínico ni los ZIP originales. El primer paso es obtener y revisar los archivos nativos reales del wrapper antes de aplicar parches.

## Por qué se añadió una inspección previa
El proyecto Android está almacenado en el repositorio como un ZIP binario. La documentación de integración describe los cambios deseados, pero no sustituye la inspección de `MainActivity.kt`, el manifiesto y Gradle reales. No es seguro aplicar reemplazos de texto sobre archivos que todavía no se han inspeccionado.

El workflow `Inspect Android wrapper sources (read-only)` extrae el ZIP y publica durante 7 días un artefacto con:
- `MainActivity.kt`
- `AndroidManifest.xml`
- `app/build.gradle.kts`
- archivos Gradle relevantes
- inventario de recursos Android y pruebas existentes

El workflow es estrictamente de lectura: no altera ni vuelve a empaquetar la aplicación.

## Implementación prevista tras revisar el artefacto

1. Integrar el puente `OrtoNube.kt` con llamadas JavaScript seguras, validación del estado OAuth, PKCE, gestión de refresh token y llamadas a Drive.
2. Añadir callback OAuth mediante navegador del sistema y App Link/deep link validado; no usar WebView embebido para iniciar sesión.
3. Añadir permiso de cámara en ejecución y conceder a WebView únicamente `RESOURCE_VIDEO_CAPTURE`; denegar cualquier otro recurso.
4. Mantener selección de imagen como alternativa sin cámara.
5. Actualizar versionCode/versionName a 22/2.2.0 y nombres de artefactos.
6. Añadir pruebas estáticas y compilación APK en esta rama.
7. Revisar el diff y los resultados antes de solicitar aprobación. No fusionar el PR.

## Requisitos externos para que Google Drive funcione realmente

- Un OAuth client ID correctamente configurado en Google Cloud.
- Proyecto Google Cloud con Drive API habilitada y pantalla de consentimiento configurada.
- Una clave de firma Android de release estable. El SHA-1 del keystore de debug efímero de GitHub Actions cambia entre ejecuciones y no sirve para una integración OAuth estable.
- El keystore y sus contraseñas deben añadirse como GitHub Actions Secrets; nunca deben guardarse en el repositorio ni en el ZIP.
- No solicitar acceso completo a Drive. Usar el alcance mínimo `drive.file` cuando sea suficiente.

El Client ID y la firma de producción no se inventarán ni se incluirán como valores ficticios. Hasta configurar esos requisitos, la nube debe mostrar claramente que no está configurada y la app debe seguir funcionando sin conexión.

## Fuentes técnicas
- Android recomienda separar autenticación y autorización y solicitar acceso a Drive solo cuando el usuario inicia esa acción.
- Google no permite iniciar sesión mediante WebView embebido.
- El alcance `drive.file` es preferible a permisos amplios de Drive.
- CameraX/Android requiere permiso CAMERA en tiempo de ejecución para el acceso a cámara; el puente WebView debe limitar el recurso concedido.
