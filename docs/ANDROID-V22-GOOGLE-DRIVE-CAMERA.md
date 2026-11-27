# OrtoGestión Pro Android v2.2 — Drive y cámara

## Qué incorpora este overlay

- Autorización de Google en Android mediante Google Identity Services `AuthorizationClient`, solicitada cuando el usuario toca la función de nube.
- Alcance mínimo `drive.file`; no solicita acceso general a todo el Drive.
- No incorpora Client Secret, refresh token ni flujo OAuth dentro de WebView.
- La aplicación transfiere el archivo `.ogbackup` cifrado generado por el HTML. El puente nativo no descifra ni inspecciona datos clínicos.
- Subida de Drive con sesión resumible.
- Permiso Android CAMERA solicitado solo cuando el lector QR intenta abrir la cámara. WebView recibe exclusivamente `RESOURCE_VIDEO_CAPTURE` y solo desde `https://appassets.androidplatform.net`.
- El selector de imágenes/archivos existente permanece disponible como alternativa.

## Configuración necesaria en Google Cloud

La integración de código no crea ni configura un proyecto Google Cloud. Para habilitar Drive en una distribución real:

1. En Google Cloud Console, crea o selecciona un proyecto y habilita Google Drive API.
2. Configura la pantalla de consentimiento y los datos de la aplicación.
3. Crea una credencial OAuth de tipo **Android** para el identificador de paquete correspondiente y la huella SHA-1 del certificado con el que se firma la APK.
4. La variante de pruebas v2.2 usa el paquete `com.ortogestion.pro.v22` para instalarse al lado de la v2.1 sin sobrescribirla; la variante release usa `com.ortogestion.pro`. Son registros Android distintos.
5. La clave debug que GitHub Actions crea de forma efímera no es una identidad estable. Para pruebas iniciales se puede registrar su SHA-1 y usar el APK de esa misma ejecución. Para distribución se necesita una clave de firma release estable, protegida como GitHub Actions Secrets; nunca se debe subir al repositorio ni compartir por chat.
6. La aplicación solicita el permiso `drive.file` en el momento de usar Drive. La autorización puede necesitar consentimiento del usuario.

La app conserva el funcionamiento local si el usuario no autoriza Drive. El acceso de Drive se mantiene en memoria con un token de corta duración; al reiniciar la app el usuario puede tener que volver a tocar «Conectar». El botón «Desconectar» elimina el token de memoria de esta ejecución, pero no revoca el consentimiento concedido a Google; para revocar ese consentimiento, el usuario debe quitar el acceso de OrtoGestión Pro desde la configuración de seguridad de su cuenta Google.

## Límites de validación

Una compilación verde demuestra que el código se integra y compila, no que la autorización de Google, el acceso a una cuenta Drive o la cámara funcionen en todos los teléfonos. Esas funciones requieren instalar la APK en un dispositivo real, configurar la credencial Android y probar conceder/denegar permisos, guardar/listar/restaurar/eliminar una copia de prueba y escanear QR.

## Seguridad

- No hay credenciales ni claves privadas en el código.
- El alcance Drive es `drive.file`.
- No se guarda refresh token en SharedPreferences.
- El puente solo acepta el origen de la app para permisos de cámara.
- La copia en Drive permanece cifrada con la contraseña que el usuario define en el HTML.
