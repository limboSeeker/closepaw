package ai.closepaw.llm

import ai.closepaw.auth.CodexHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CodexAccountModel(
    val slug: String,
    val displayName: String,
    val contextWindow: Int,
    val priority: Int,
    val supportsVision: Boolean,
    val supportsParallelToolCalls: Boolean,
)

internal object CodexProtocolIdentity {
    const val COMPATIBILITY_VERSION = "0.165.0"
    const val ORIGINATOR = "closepaw"
}

class CodexModelDiscoveryException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Account-scoped, memory-only catalog used by both the picker and inference. */
class CodexModelResolver internal constructor(
    private val clientVersion: String = CodexProtocolIdentity.COMPATIBILITY_VERSION,
    private val httpClient: OkHttpClient = productionClient(),
    private val modelsUrl: String = MODELS_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class CacheKey(val accountFingerprint: String, val tokenFingerprint: String)
    private data class CacheEntry(val models: List<CodexAccountModel>, val fetchedAt: Long)
    private val cacheMutex = Mutex()
    private val memoryCache = mutableMapOf<CacheKey, CacheEntry>()

    suspend fun listModels(headers: CodexHeaders, forceRefresh: Boolean = false): List<CodexAccountModel> {
        val accountId = headers.chatgptAccountId
            ?: throw CodexModelDiscoveryException("ChatGPT account identity is missing. Sign out, then sign in again.")
        val key = CacheKey(tokenFingerprint(accountId), tokenFingerprint(headers.accessToken))
        return cacheMutex.withLock {
            if (!forceRefresh) memoryCache[key]
                ?.takeIf { clock() - it.fetchedAt < ttlMs }
                ?.let { return@withLock it.models }
            // A token rotation or account switch invalidates all prior entries. Keeping only
            // the active identity also prevents one account's model names appearing for another.
            memoryCache.keys.removeAll { it != key }
            if (forceRefresh) memoryCache.remove(key)
            fetch(headers).also { memoryCache[key] = CacheEntry(it, clock()) }
        }
    }

    suspend fun resolve(
        savedModelId: String,
        headers: CodexHeaders,
        forceRefresh: Boolean = false,
    ): CodexAccountModel {
        val models = listModels(headers, forceRefresh)
        val requested = savedModelId.removePrefix(CATALOG_PREFIX)
        return models.firstOrNull { it.slug == requested } ?: models.first()
    }

    suspend fun clearMemoryCache() = cacheMutex.withLock { memoryCache.clear() }

    private suspend fun fetch(headers: CodexHeaders): List<CodexAccountModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$modelsUrl?client_version=${java.net.URLEncoder.encode(wholeVersion(clientVersion), "UTF-8")}")
            .get()
            .header("Authorization", "Bearer ${headers.accessToken}")
            .header("chatgpt-account-id", headers.chatgptAccountId!!)
            .header("Accept", "application/json")
            .header("originator", CodexProtocolIdentity.ORIGINATOR)
            .header("version", clientVersion)
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    throw CodexModelDiscoveryException("ChatGPT model discovery redirected unexpectedly. Please refresh or sign in again.")
                }
                if (!response.isSuccessful) {
                    throw CodexModelDiscoveryException("Could not refresh ChatGPT models (HTTP ${response.code}). Try again; if it persists, sign in again.")
                }
                parse(response.body.string())
            }
        } catch (e: CodexModelDiscoveryException) {
            throw e
        } catch (e: Exception) {
            throw CodexModelDiscoveryException("Could not refresh ChatGPT models. Check your connection and try again.", e)
        }
    }

    internal fun parse(body: String): List<CodexAccountModel> {
        val array = try { JSONObject(body).getJSONArray("models") } catch (e: Exception) {
            throw CodexModelDiscoveryException("ChatGPT returned an invalid model list. Refresh and try again.", e)
        }
        val result = buildList {
            for (i in 0 until array.length()) {
                val model = array.optJSONObject(i) ?: continue
                val slug = model.optString("slug").trim()
                if (slug.isEmpty() || model.optString("visibility", "list") != "list") continue
                val modalities = model.optJSONArray("input_modalities")
                val supportsVision = modalities?.let { values ->
                    (0 until values.length()).any { values.optString(it) == "image" }
                } ?: true
                add(CodexAccountModel(
                    slug = slug,
                    displayName = model.optString("display_name", slug).ifBlank { slug },
                    contextWindow = model.optInt("context_window", 128_000).coerceAtLeast(1),
                    priority = model.optInt("priority", Int.MAX_VALUE),
                    supportsVision = supportsVision,
                    supportsParallelToolCalls = model.optBoolean("supports_parallel_tool_calls", false),
                ))
            }
        }.sortedBy { it.priority }
        if (result.map { it.slug }.toSet().size != result.size) {
            throw CodexModelDiscoveryException("ChatGPT returned a model list with duplicate IDs. Refresh and try again.")
        }
        if (result.isEmpty()) {
            throw CodexModelDiscoveryException("No usable ChatGPT models were returned for this account. Refresh or sign in again.")
        }
        return result
    }

    companion object {
        const val AUTO_MODEL = "codex-auto"
        const val CATALOG_PREFIX = "codex-account:"
        private const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        private fun tokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun wholeVersion(version: String): String {
            val parts = version.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
            return listOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
                .joinToString(".")
        }
        private fun productionClient() = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
