package com.ortogestion.pro

// OrtoNube — puente nativo de la copia en la nube (v2.2, OrtoGestión Pro).
//
// La app HTML (app/index.html) habla con este puente a través de
// window.OrtoNube. El puente hace OAuth 2.0 con PKCE contra Google,
// guarda el refresh token en SharedPreferences (privado de la app) y
// usa la API de Google Drive (scope drive.file) con HttpURLConnection.
// NO necesita librerías nuevas: solo SDK de Android + org.json.
//
// CÓMO SE INSTALA (ver docs/NOTA-INTEGRACION-NUBE-Y-CAMARA.md):
//   1. Este archivo va en OrtoGestion-Pro-Android/app/src/main/java/
//      com/ortogestion/pro/OrtoNube.kt (dentro del ZIP del wrapper).
//   2. En MainActivity: crear OrtoNube(this, webView) y registrarlo con
//      webView.addJavascriptInterface(ortoNube, "OrtoNube").
//   3. En MainActivity: reenviar el intent de vuelta de OAuth a
//      ortoNube.onAuthIntent(intent) desde onCreate y onNewIntent.
//   4. Poner el client ID real en GOOGLE_CLIENT_ID (abajo).
//   5. Manifest: intent-filter com.ortogestion.pro:/oauth2callback,
//      permiso CAMERA (solo para el QR) y launchMode singleTask.
//
// CONTRATO CON EL HTML (no cambiar los nombres):
//   JS -> Kotlin (síncronos, devuelven String):
//     estadoNube()   -> JSON {"configurado":bool,"conectado":bool,"correo":str|null}
//   JS -> Kotlin (asíncronos, responden por callback JS):
//     conectar()          -> window.__ortoNubeConectado(ok, msg)
//     listarNube()        -> window.__ortoNubeLista(jsonTexto)
//                            jsonTexto: [{"id","nombre","fecha","tamano"}]
//                            o {"error":"..."} si falla
//     guardarNube(nombre, contenido) -> window.__ortoNubeGuardar(ok, msg)
//     descargarNube(id)   -> window.__ortoNubeDescarga(ok, contenido)
//                            contenido: texto del respaldo, o mensaje de error
//     borrarNube(id)      -> window.__ortoNubeBorrado(ok, msg)
//     desconectar()       -> window.__ortoNubeDesconectado()
//   El contenido que sube/baja es el respaldo CIFRADO (.ogbackup) tal
//   como lo genera el HTML; el puente no lo descifra ni lo lee.

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

class OrtoNube(private val activity: Activity, private val webView: WebView) {

    companion object {
        // *** PONER AQUÍ EL CLIENT ID DE GOOGLE (cliente OAuth Android) ***
        // Se crea en Google Cloud Console (ver la nota de integración).
        // Mientras siga este marcador, el HTML muestra «aún no está
        // configurada» y no intenta conectar.
        const val GOOGLE_CLIENT_ID = "PONER_CLIENT_ID_AQUI"

        const val REDIRECT_URI = "com.ortogestion.pro:/oauth2callback"
        const val SCOPE = "openid email https://www.googleapis.com/auth/drive.file"
        const val FOLDER_NAME = "OrtoGestión Pro — Copias de seguridad"
        const val PREFS = "orto_nube"
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        const val DRIVE_URL = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    }

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpira: Long = 0L
    @Volatile private var folderIdCache: String? = null

    fun isConfigured(): Boolean =
        GOOGLE_CLIENT_ID.isNotBlank() && GOOGLE_CLIENT_ID != "PONER_CLIENT_ID_AQUI"

    // ---- Estado (síncrono, sin red: el HTML lo llama en cada pintado) ----
    @JavascriptInterface
    fun estadoNube(): String {
        val o = JSONObject()
        o.put("configurado", isConfigured())
        o.put("conectado", isConfigured() && prefs.getString("refresh_token", null) != null)
        o.put("correo", prefs.getString("correo", null) ?: JSONObject.NULL)
        return o.toString()
    }

    // ---- OAuth: abrir el navegador del sistema ----
    @JavascriptInterface
    fun conectar() {
        if (!isConfigured()) {
            js("window.__ortoNubeConectado(false,'La copia en la nube aún no está configurada en esta APK.')")
            return
        }
        val verifier = nuevoVerifier()
        val state = nuevoVerifier()
        prefs.edit()
            .putString("pkce_verifier", verifier)
            .putString("oauth_state", state)
            .apply()
        val challenge = codeChallenge(verifier)
        val url = AUTH_URL +
            "?client_id=" + URLEncoder.encode(GOOGLE_CLIENT_ID, "UTF-8") +
            "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&response_type=code" +
            "&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
            "&access_type=offline" +
            "&prompt=consent" +
            "&state=" + URLEncoder.encode(state, "UTF-8") +
            "&code_challenge=" + challenge +
            "&code_challenge_method=S256"
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                js("window.__ortoNubeConectado(false,'No se pudo abrir el navegador.')")
            }
        }
    }

    // ---- Vuelta de OAuth: MainActivity lo llama desde onCreate/onNewIntent ----
    // Devuelve true si el intent era la vuelta de Google (lo haya gestionado o no).
    fun onAuthIntent(intent: Intent?): Boolean {
        val data: Uri = intent?.data ?: return false
        if (data.scheme != "com.ortogestion.pro") return false
        val error = data.getQueryParameter("error")
        if (error != null) {
            js("window.__ortoNubeConectado(false,'Acceso denegado en Google.')")
            return true
        }
        val code = data.getQueryParameter("code") ?: run {
            js("window.__ortoNubeConectado(false,'Google no devolvió el código.')")
            return true
        }
        val state = data.getQueryParameter("state")
        if (state == null || state != prefs.getString("oauth_state", null)) {
            js("window.__ortoNubeConectado(false,'La respuesta de Google no coincide (state).')")
            return true
        }
        val verifier = prefs.getString("pkce_verifier", null) ?: run {
            js("window.__ortoNubeConectado(false,'Se perdió el verificador de la sesión.')")
            return true
        }
        Thread {
            try {
                val body = "code=" + URLEncoder.encode(code, "UTF-8") +
                    "&client_id=" + URLEncoder.encode(GOOGLE_CLIENT_ID, "UTF-8") +
                    "&code_verifier=" + URLEncoder.encode(verifier, "UTF-8") +
                    "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
                    "&grant_type=authorization_code"
                val (codeHttp, resp) = http("POST", TOKEN_URL, null, body,
                    "application/x-www-form-urlencoded")
                if (codeHttp != 200) throw IllegalStateException("token HTTP $codeHttp")
                val json = JSONObject(resp)
                val refresh = json.optString("refresh_token", "")
                val access = json.optString("access_token", "")
                val expiraEn = json.optLong("expires_in", 3600)
                if (access.isBlank()) throw IllegalStateException("sin access_token")
                if (refresh.isNotBlank()) {
                    prefs.edit().putString("refresh_token", refresh).apply()
                }
                accessToken = access
                accessTokenExpira = System.currentTimeMillis() + (expiraEn - 60) * 1000
                // Correo de la cuenta (para mostrar «Conectada como …»).
                try {
                    val (_, info) = http("GET", USERINFO_URL, access, null, null)
                    val correo = JSONObject(info).optString("email", "")
                    if (correo.isNotBlank()) {
                        prefs.edit().putString("correo", correo).apply()
                    }
                } catch (_: Exception) { /* el correo es cosmético */ }
                prefs.edit().remove("pkce_verifier").remove("oauth_state").apply()
                js("window.__ortoNubeConectado(true,'')")
            } catch (e: Exception) {
                js("window.__ortoNubeConectado(false,'" + escJs(e.message ?: "error") + "')")
            }
        }.start()
        return true
    }

    // ---- Listar copias ----
    @JavascriptInterface
    fun listarNube() {
        Thread {
            try {
                val token = tokenValido() ?: throw IllegalStateException("Sin sesión de Google.")
                val folder = carpetaId(token)
                val q = "'" + folder + "' in parents and trashed=false"
                val url = DRIVE_URL + "/files?q=" + URLEncoder.encode(q, "UTF-8") +
                    "&fields=" + URLEncoder.encode("files(id,name,createdTime,size)", "UTF-8") +
                    "&orderBy=createdTime%20desc&pageSize=100&spaces=drive"
                val (codeHttp, resp) = http("GET", url, token, null, null)
                if (codeHttp != 200) throw IllegalStateException("Drive HTTP $codeHttp")
                val arr = JSONObject(resp).optJSONArray("files") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val o = JSONObject()
                    o.put("id", f.optString("id"))
                    o.put("nombre", f.optString("name"))
                    o.put("fecha", f.optString("createdTime"))
                    o.put("tamano", f.optLong("size", 0))
                    out.put(o)
                }
                js("window.__ortoNubeLista(" + JSONObject.quote(out.toString()) + ")")
            } catch (e: Exception) {
                val err = JSONObject().put("error", e.message ?: "error").toString()
                js("window.__ortoNubeLista(" + JSONObject.quote(err) + ")")
            }
        }.start()
    }

    // ---- Guardar copia (contenido = respaldo cifrado .ogbackup, texto) ----
    @JavascriptInterface
    fun guardarNube(nombre: String, contenido: String) {
        Thread {
            try {
                val token = tokenValido() ?: throw IllegalStateException("Sin sesión de Google.")
                val folder = carpetaId(token)
                val meta = JSONObject()
                meta.put("name", nombre.ifBlank { "OrtoGestion.ogbackup" })
                meta.put("parents", JSONArray().put(folder))
                val boundary = "orto-" + System.currentTimeMillis()
                val out = ByteArrayOutputStream()
                fun parte(cabecera: String, bytes: ByteArray) {
                    out.write(("--" + boundary + "\r\n").toByteArray(Charsets.UTF_8))
                    out.write((cabecera + "\r\n\r\n").toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                parte("Content-Type: application/json; charset=UTF-8",
                    meta.toString().toByteArray(Charsets.UTF_8))
                parte("Content-Type: application/octet-stream",
                    contenido.toByteArray(Charsets.UTF_8))
                out.write(("--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8))
                val (codeHttp, resp) = httpBytes("POST", UPLOAD_URL, token,
                    out.toByteArray(), "multipart/related; boundary=$boundary")
                if (codeHttp !in 200..201) throw IllegalStateException("subida HTTP $codeHttp")
                js("window.__ortoNubeGuardar(true,'')")
            } catch (e: Exception) {
                js("window.__ortoNubeGuardar(false,'" + escJs(e.message ?: "error") + "')")
            }
        }.start()
    }

    // ---- Descargar copia (devuelve el texto del respaldo) ----
    @JavascriptInterface
    fun descargarNube(id: String) {
        Thread {
            try {
                val token = tokenValido() ?: throw IllegalStateException("Sin sesión de Google.")
                val url = DRIVE_URL + "/files/" + URLEncoder.encode(id, "UTF-8") + "?alt=media"
                val (codeHttp, resp) = http("GET", url, token, null, null)
                if (codeHttp != 200) throw IllegalStateException("descarga HTTP $codeHttp")
                js("window.__ortoNubeDescarga(true," + JSONObject.quote(resp) + ")")
            } catch (e: Exception) {
                js("window.__ortoNubeDescarga(false," + JSONObject.quote(e.message ?: "error") + ")")
            }
        }.start()
    }

    // ---- Borrar copia: a la papelera de Drive (recuperable 30 días) ----
    @JavascriptInterface
    fun borrarNube(id: String) {
        Thread {
            try {
                val token = tokenValido() ?: throw IllegalStateException("Sin sesión de Google.")
                val url = DRIVE_URL + "/files/" + URLEncoder.encode(id, "UTF-8")
                val (codeHttp, _) = http("PATCH", url, token, "{\"trashed\":true}",
                    "application/json; charset=UTF-8")
                if (codeHttp !in 200..299) throw IllegalStateException("borrado HTTP $codeHttp")
                js("window.__ortoNubeBorrado(true,'')")
            } catch (e: Exception) {
                js("window.__ortoNubeBorrado(false,'" + escJs(e.message ?: "error") + "')")
            }
        }.start()
    }

    // ---- Desconectar: revoca y borra los tokens; las copias quedan en Drive ----
    @JavascriptInterface
    fun desconectar() {
        val refresh = prefs.getString("refresh_token", null)
        prefs.edit().clear().apply()
        accessToken = null
        accessTokenExpira = 0L
        folderIdCache = null
        Thread {
            try {
                if (!refresh.isNullOrBlank()) {
                    http("POST", REVOKE_URL, null,
                        "token=" + URLEncoder.encode(refresh, "UTF-8"),
                        "application/x-www-form-urlencoded")
                }
            } catch (_: Exception) { }
            js("window.__ortoNubeDesconectado()")
        }.start()
    }

    // ======================= Internos =======================

    private fun tokenValido(): String? {
        val ahora = System.currentTimeMillis()
        val cache = accessToken
        if (cache != null && ahora < accessTokenExpira) return cache
        val refresh = prefs.getString("refresh_token", null) ?: return null
        return try {
            val body = "client_id=" + URLEncoder.encode(GOOGLE_CLIENT_ID, "UTF-8") +
                "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8") +
                "&grant_type=refresh_token"
            val (codeHttp, resp) = http("POST", TOKEN_URL, null, body,
                "application/x-www-form-urlencoded")
            if (codeHttp != 200) {
                if (codeHttp == 400 || codeHttp == 401) {
                    prefs.edit().remove("refresh_token").remove("correo").apply()
                    accessToken = null
                }
                return null
            }
            val json = JSONObject(resp)
            val access = json.optString("access_token", "")
            if (access.isBlank()) return null
            accessToken = access
            accessTokenExpira = ahora + (json.optLong("expires_in", 3600) - 60) * 1000
            access
        } catch (_: Exception) { null }
    }

    private fun carpetaId(token: String): String {
        folderIdCache?.let { return it }
        val q = "name='" + FOLDER_NAME.replace("'", "\\'") + "' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val url = DRIVE_URL + "/files?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fields=" + URLEncoder.encode("files(id)", "UTF-8") + "&spaces=drive"
        val (codeHttp, resp) = http("GET", url, token, null, null)
        if (codeHttp != 200) throw IllegalStateException("Drive HTTP $codeHttp")
        val files = JSONObject(resp).optJSONArray("files") ?: JSONArray()
        if (files.length() > 0) {
            val id = files.getJSONObject(0).optString("id")
            folderIdCache = id
            return id
        }
        val meta = JSONObject()
        meta.put("name", FOLDER_NAME)
        meta.put("mimeType", "application/vnd.google-apps.folder")
        val (codeCrear, respCrear) = http("POST", DRIVE_URL + "/files", token,
            meta.toString(), "application/json; charset=UTF-8")
        if (codeCrear !in 200..201) throw IllegalStateException("crear carpeta HTTP $codeCrear")
        val id = JSONObject(respCrear).optString("id")
        if (id.isBlank()) throw IllegalStateException("Drive no devolvió la carpeta")
        folderIdCache = id
        return id
    }

    private fun http(method: String, url: String, token: String?, body: String?, contentType: String?): Pair<Int, String> {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val (code, respBytes) = httpBytes(method, url, token, bytes, contentType)
        return code to String(respBytes, Charsets.UTF_8)
    }

    private fun httpBytes(method: String, url: String, token: String?, body: ByteArray?, contentType: String?): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun js(code: String) {
        webView.post { webView.evaluateJavascript(code, null) }
    }

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")

    private fun nuevoVerifier(): String {
        val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        val sb = StringBuilder()
        repeat(64) { sb.append(alfabeto[rnd.nextInt(alfabeto.length)]) }
        return sb.toString()
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
