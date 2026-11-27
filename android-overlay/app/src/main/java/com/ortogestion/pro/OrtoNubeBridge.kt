package com.ortogestion.pro

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Drive bridge: transfers encrypted .ogbackup payloads only; it never decrypts patient data. */
class OrtoNubeBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val launchAuthorization: (IntentSenderRequest) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt: Long = 0L
    @Volatile private var pendingAction: ((String) -> Unit)? = null
    @Volatile private var pendingFailure: ((String) -> Unit)? = null
    @Volatile private var folderId: String? = null

    @JavascriptInterface
    fun estadoNube(): String {
        val valid = !accessToken.isNullOrBlank() && System.currentTimeMillis() < expiresAt
        return JSONObject().put("configurado", true).put("conectado", valid)
            .put("correo", JSONObject.NULL).toString()
    }

    @JavascriptInterface
    fun conectar() {
        authorize({ token ->
            accessToken = token
            expiresAt = System.currentTimeMillis() + 50 * 60 * 1000L
            js("window.__ortoNubeConectado(true,'')")
        }, { msg -> js("window.__ortoNubeConectado(false,${JSONObject.quote(msg)})") })
    }

    fun onAuthorizationResult(data: Intent?, resultOk: Boolean) {
        try {
            if (!resultOk) throw IllegalStateException("Autorización cancelada por el usuario.")
            val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            acceptAuthorization(result)
        } catch (e: Exception) {
            failPending(e.message ?: "No se pudo completar la autorización de Google.")
        }
    }

    private fun authorize(action: (String) -> Unit, onError: (String) -> Unit) {
        pendingAction = action
        pendingFailure = onError
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) failPending("Google no devolvió la solicitud de autorización.")
                    else try {
                        launchAuthorization(IntentSenderRequest.Builder(pending.intentSender).build())
                    } catch (e: Exception) { failPending(e.message ?: "No se pudo abrir la autorización.") }
                } else acceptAuthorization(result)
            }
            .addOnFailureListener { e -> failPending(e.message ?: "Google rechazó la autorización.") }
    }

    private fun acceptAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) { failPending("Google no entregó un token de acceso."); return }
        accessToken = token
        expiresAt = System.currentTimeMillis() + 50 * 60 * 1000L
        val action = pendingAction
        pendingAction = null
        pendingFailure = null
        action?.invoke(token)
    }

    private fun failPending(message: String) {
        pendingAction = null
        val failure = pendingFailure
        pendingFailure = null
        failure?.invoke(message)
    }

    private fun withToken(onReady: (String) -> Unit, onError: (String) -> Unit) {
        val cached = accessToken
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < expiresAt) onReady(cached)
        else authorize(onReady, onError)
    }

    @JavascriptInterface
    fun listarNube() = withToken({ token -> executor.execute {
        try {
            val folder = getFolder(token)
            val q = "'${folder}' in parents and trashed=false"
            val url = "$DRIVE/files?q=${enc(q)}&fields=${enc("files(id,name,createdTime,size)")}&orderBy=createdTime%20desc&pageSize=100&spaces=drive"
            val (code, body) = http("GET", url, token, null, null)
            if (code !in 200..299) throw IllegalStateException("No se pudieron consultar las copias (HTTP $code).")
            val obj = JSONObject(String(body, StandardCharsets.UTF_8))
            val files = obj.optJSONArray("files") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out.put(JSONObject().put("id", f.optString("id")).put("nombre", f.optString("name"))
                    .put("fecha", f.optString("createdTime")).put("tamano", f.optLong("size", 0)))
            }
            js("window.__ortoNubeLista(${JSONObject.quote(out.toString())})")
        } catch (e: Exception) { js("window.__ortoNubeLista(${JSONObject.quote(JSONObject().put("error", safeMessage(e)).toString())})") }
    } }, { msg -> js("window.__ortoNubeLista(${JSONObject.quote(JSONObject().put("error", msg).toString())})") })

    @JavascriptInterface
    fun guardarNube(nombre: String, contenido: String) = withToken({ token -> executor.execute {
        try {
            val folder = getFolder(token)
            val bytes = contenido.toByteArray(StandardCharsets.UTF_8)
            val metadata = JSONObject().put("name", safeFilename(nombre)).put("parents", JSONArray().put(folder)).toString().toByteArray(StandardCharsets.UTF_8)
            val session = URL("$UPLOAD/files?uploadType=resumable&fields=id,name,createdTime,size").openConnection() as HttpURLConnection
            session.requestMethod = "POST"; session.connectTimeout = 30000; session.readTimeout = 60000
            session.setRequestProperty("Authorization", "Bearer $token")
            session.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            session.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            session.setRequestProperty("X-Upload-Content-Length", bytes.size.toString())
            session.doOutput = true
            session.outputStream.use { it.write(metadata) }
            val code = session.responseCode
            val uploadUrl = session.getHeaderField("Location")
            val err = if (code !in 200..299) readResponse(session) else ""
            session.disconnect()
            if (code !in 200..299 || uploadUrl.isNullOrBlank()) throw IllegalStateException("No se pudo iniciar la subida (HTTP $code). $err")
            val (putCode, _) = http("PUT", uploadUrl, null, bytes, "application/octet-stream")
            if (putCode !in 200..299) throw IllegalStateException("La subida de la copia falló (HTTP $putCode).")
            js("window.__ortoNubeGuardar(true,'')")
        } catch (e: Exception) { js("window.__ortoNubeGuardar(false,${JSONObject.quote(safeMessage(e))})") }
    } }, { msg -> js("window.__ortoNubeGuardar(false,${JSONObject.quote(msg)})") })

    @JavascriptInterface
    fun descargarNube(id: String) = withToken({ token -> executor.execute {
        try {
            require(id.matches(Regex("[A-Za-z0-9_-]{5,200}"))) { "Identificador de copia no válido." }
            val (code, bytes) = http("GET", "$DRIVE/files/${enc(id)}?alt=media", token, null, null)
            if (code !in 200..299) throw IllegalStateException("No se pudo descargar la copia (HTTP $code).")
            js("window.__ortoNubeDescarga(true,${JSONObject.quote(String(bytes, StandardCharsets.UTF_8))})")
        } catch (e: Exception) { js("window.__ortoNubeDescarga(false,${JSONObject.quote(safeMessage(e))})") }
    } }, { msg -> js("window.__ortoNubeDescarga(false,${JSONObject.quote(msg)})") })

    @JavascriptInterface
    fun borrarNube(id: String) = withToken({ token -> executor.execute {
        try {
            require(id.matches(Regex("[A-Za-z0-9_-]{5,200}"))) { "Identificador de copia no válido." }
            val body = "{\"trashed\":true}".toByteArray(StandardCharsets.UTF_8)
            val (code, _) = http("PATCH", "$DRIVE/files/${enc(id)}", token, body, "application/json; charset=UTF-8")
            if (code !in 200..299) throw IllegalStateException("Drive no pudo mover la copia a la papelera (HTTP $code).")
            js("window.__ortoNubeBorrado(true,'')")
        } catch (e: Exception) { js("window.__ortoNubeBorrado(false,${JSONObject.quote(safeMessage(e))})") }
    } }, { msg -> js("window.__ortoNubeBorrado(false,${JSONObject.quote(msg)})") })

    @JavascriptInterface
    fun desconectar() {
        accessToken = null; expiresAt = 0L; folderId = null
        js("window.__ortoNubeDesconectado()")
    }

    private fun getFolder(token: String): String {
        folderId?.let { return it }
        val q = "name='${FOLDER.replace("'", "\\'")}' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val url = "$DRIVE/files?q=${enc(q)}&fields=${enc("files(id)")}&spaces=drive&pageSize=10"
        val (listCode, listBytes) = http("GET", url, token, null, null)
        if (listCode !in 200..299) throw IllegalStateException("No se pudo consultar la carpeta de copias (HTTP $listCode).")
        val response = JSONObject(String(listBytes, StandardCharsets.UTF_8))
        val files = response.optJSONArray("files") ?: JSONArray()
        if (files.length() > 0) return files.getJSONObject(0).optString("id").also { folderId = it }
        val body = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray(StandardCharsets.UTF_8)
        val (code, bytes) = http("POST", "$DRIVE/files?fields=id", token, body, "application/json; charset=UTF-8")
        if (code !in 200..201) throw IllegalStateException("No se pudo crear la carpeta de copias (HTTP $code).")
        return JSONObject(String(bytes, StandardCharsets.UTF_8)).optString("id").takeIf { it.isNotBlank() }
            ?.also { folderId = it } ?: throw IllegalStateException("Drive no devolvió el identificador de la carpeta.")
    }

    private fun http(method: String, address: String, token: String?, body: ByteArray?, type: String?): Pair<Int, ByteArray> {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 30000; conn.readTimeout = 120000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                if (type != null) conn.setRequestProperty("Content-Type", type)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { input -> input.copyTo(out) }
            return code to out.toByteArray()
        } finally { conn.disconnect() }
    }

    private fun readResponse(conn: HttpURLConnection): String = try {
        (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText().take(500) }
    } catch (_: Exception) { "" }

    private fun js(code: String) { webView.post { webView.evaluateJavascript(code, null) } }
    private fun safeFilename(value: String): String = value.substringAfterLast('/').substringAfterLast(92.toChar())
        .filter { it.isLetterOrDigit() || it in "._-" }.take(100).ifBlank { "OrtoGestion.ogbackup" }
    private fun safeMessage(e: Exception): String = (e.message ?: "Error inesperado").take(300)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val DRIVE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "OrtoGestión Pro — Copias de seguridad"
    }
}
