package ai.closepaw.auth

import android.util.Log
import ai.closepaw.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** OpenAI OAuth constants — reuses official Codex CLI client_id and redirect URI. */
object OAuthConfig {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    const val CALLBACK_PORT = 1455
}

/** PKCE verifier + challenge pair. */
data class PkceChallenge(val verifier: String, val challenge: String)

/** Generate PKCE S256 challenge. */
fun generatePkce(): PkceChallenge {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val verifier = bytes.toBase64Url()
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    val challenge = digest.toBase64Url()
    return PkceChallenge(verifier, challenge)
}

/** Generate random hex state for CSRF protection. */
fun generateOAuthState(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Build the full authorization URL. */
fun buildAuthorizeUrl(challenge: String, state: String): String {
    val params = listOf(
        "response_type" to "code",
        "client_id" to OAuthConfig.CLIENT_ID,
        "redirect_uri" to OAuthConfig.REDIRECT_URI,
        "scope" to OAuthConfig.SCOPE,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "state" to state,
        "codex_cli_simplified_flow" to "true",
        "id_token_add_organizations" to "true",
        "originator" to "codex_cli_rs"
    ).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
    return "${OAuthConfig.AUTHORIZE_URL}?$params"
}

/** OAuth token response. */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val email: String?,
    val idToken: String? = null
)

/**
 * Local HTTP server that listens on localhost:1455 for the OAuth callback.
 * Same approach as the official Codex CLI.
 */
class OAuthCallbackServer(private val expectedState: String) {
    private val TAG = "OAuthCallbackServer"
    private var serverSocket: ServerSocket? = null
    @Volatile private var result: CallbackResult? = null

    sealed interface CallbackResult {
        data class Success(val code: String) : CallbackResult
        data class Error(val message: String) : CallbackResult
    }

    /** Start listening. Call from IO dispatcher. */
    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket().apply {
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), OAuthConfig.CALLBACK_PORT))
                soTimeout = 120_000 // 2 min timeout
            }
            Log.d(TAG, "Listening on localhost:${OAuthConfig.CALLBACK_PORT}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind port ${OAuthConfig.CALLBACK_PORT}", e)
            false
        }
    }

    /** Block until callback arrives or timeout. Call from IO dispatcher. */
    fun waitForCallback(): CallbackResult {
        val socket = serverSocket ?: return CallbackResult.Error("Server not started")
        return try {
            val client = socket.accept()
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            if (BuildConfig.DEBUG) Log.d(TAG, "Callback received (${requestLine.length} chars)")

            // Parse GET /auth/callback?code=xxx&state=yyy HTTP/1.1
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            val queryStart = path.indexOf('?')
            val params = if (queryStart >= 0) {
                path.substring(queryStart + 1).split("&").associate {
                    val (k, v) = it.split("=", limit = 2)
                    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
                }
            } else emptyMap()

            val code = params["code"]
            val state = params["state"]
            val error = params["error"]

            // Send response HTML
            val (statusCode, html) = when {
                error != null -> 400 to errorHtml("Sign-in was cancelled or denied.")
                code == null -> 400 to errorHtml("Missing authorization code.")
                state != expectedState -> 400 to errorHtml("State mismatch — possible CSRF attack.")
                else -> 200 to successHtml()
            }

            val response = "HTTP/1.1 $statusCode OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                html
            client.getOutputStream().write(response.toByteArray())
            client.close()

            when {
                error != null -> CallbackResult.Error("Sign-in was cancelled.")
                code == null -> CallbackResult.Error("No authorization code received.")
                state != expectedState -> CallbackResult.Error("Security check failed. Please try again.")
                else -> CallbackResult.Success(code)
            }
        } catch (e: java.net.SocketTimeoutException) {
            CallbackResult.Error("Sign-in timed out. Please try again.")
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for callback", e)
            CallbackResult.Error("Failed to receive sign-in callback: ${e.message}")
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun successHtml() = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="0;url=closepaw://oauth-complete">
        <title>Sign-in Complete</title>
        <style>body{font-family:system-ui;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f9f9f9}
        .card{text-align:center;padding:2rem;max-width:400px}h1{color:#10a37f;font-size:1.5rem}p{color:#666}a{color:#10a37f}</style>
        <script>setTimeout(function(){window.location.href='closepaw://oauth-complete'},50);</script>
        </head><body><div class="card"><h1>Sign-in complete</h1><p>Returning to ClosePaw… <a href="closepaw://oauth-complete">tap here</a> if it doesn't switch automatically.</p></div></body></html>
    """.trimIndent()

    private fun errorHtml(message: String) = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Sign-in Error</title>
        <style>body{font-family:system-ui;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f9f9f9}
        .card{text-align:center;padding:2rem;max-width:400px}h1{color:#e53e3e;font-size:1.5rem}p{color:#666}</style>
        </head><body><div class="card"><h1>Sign-in failed</h1><p>${message}</p></div></body></html>
    """.trimIndent()
}

/** Exchange authorization code for tokens, or refresh. */
object OAuthTokenExchange {
    private const val TAG = "OAuthTokenExchange"

    sealed interface Result {
        data class Success(val tokens: OAuthTokens) : Result
        data class Error(val message: String) : Result
    }

    /** Exchange authorization code for ChatGPT account tokens. */
    suspend fun exchange(code: String, verifier: String): Result = withContext(Dispatchers.IO) {
        try {
            val body = listOf(
                "grant_type" to "authorization_code",
                "client_id" to OAuthConfig.CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to OAuthConfig.REDIRECT_URI
            ).toFormBody()
            withDnsRetry { postTokenRequest(body) }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.Error("Sign-in failed: ${e.message}")
        }
    }

    /** Refresh access token. */
    suspend fun refresh(refreshToken: String): Result = withContext(Dispatchers.IO) {
        try {
            val body = listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to OAuthConfig.CLIENT_ID
            ).toFormBody()
            withDnsRetry { postTokenRequest(body) }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.Error("Token refresh failed: ${e.message}")
        }
    }

    private fun postTokenRequest(body: String): Result {
        val conn = (URL(OAuthConfig.TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "Token request failed: HTTP $code (${errorBody.length} chars)")
                return Result.Error("OpenAI returned HTTP $code")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", "")
            val expiresIn = json.optLong("expires_in", 3600)
            val idToken = json.optString("id_token", "").ifEmpty { null }
            val email = parseEmailFromJwt(accessToken)
            return Result.Success(
                OAuthTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                    email = email,
                    idToken = idToken
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun List<Pair<String, String>>.toFormBody(): String =
        joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }

    /**
     * Retry on transient DNS failure (UnknownHostException). The auth code / refresh
     * token is still valid because the request never reached the server. HTTP errors
     * and post-connect failures are NOT retried — they may have side effects.
     */
    private inline fun <T> withDnsRetry(block: () -> T): T {
        var delay = 1000L
        repeat(2) {
            try { return block() } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "DNS failed (${e.message}), retrying in ${delay}ms")
                Thread.sleep(delay)
                delay *= 2
            }
        }
        return block()
    }
}

/** Parse email from JWT access token (no signature verification — token came over TLS). */
fun parseEmailFromJwt(jwt: String): String? {
    val parts = jwt.split(".")
    if (parts.size != 3) return null
    return try {
        val payload = String(base64UrlDecode(parts[1]), Charsets.UTF_8)
        val json = JSONObject(payload)
        json.optString("https://api.openai.com/profile.email", "").ifEmpty { null }
            ?: json.optString("email", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

private fun ByteArray.toBase64Url(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun base64UrlDecode(s: String): ByteArray {
    val padded = s.replace('-', '+').replace('_', '/')
    val withPad = padded + "=".repeat((4 - padded.length % 4) % 4)
    return java.util.Base64.getDecoder().decode(withPad)
}
