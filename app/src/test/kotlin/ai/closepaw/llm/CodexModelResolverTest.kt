package ai.closepaw.llm

import ai.closepaw.auth.CodexHeaders
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CodexModelResolverTest {
    private lateinit var server: MockWebServer
    private val headers = CodexHeaders("token-a", "account-a", null)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun `discovers list models with fresh account auth and client version`() = runBlocking {
        server.enqueue(MockResponse().setBody(models("old", "new")))
        val resolver = resolver()
        assertThat(resolver.listModels(headers, forceRefresh = true).map { it.slug })
            .containsExactly("old", "new").inOrder()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/backend-api/codex/models?client_version=1.2.3")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token-a")
        assertThat(request.getHeader("chatgpt-account-id")).isEqualTo("account-a")
        assertThat(request.getHeader("originator")).isEqualTo("closepaw")
        assertThat(request.getHeader("version")).isEqualTo("1.2.3")
    }

    @Test fun `retired saved model resolves to first account model`() = runBlocking {
        server.enqueue(MockResponse().setBody(models("current-a", "current-b")))
        assertThat(resolver().resolve("retired-model", headers).slug).isEqualTo("current-a")
    }

    @Test fun `account or token switch invalidates memory cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(models("a")))
        server.enqueue(MockResponse().setBody(models("b")))
        val resolver = resolver()
        assertThat(resolver.resolve("codex-auto", headers).slug).isEqualTo("a")
        assertThat(resolver.resolve("codex-auto", headers.copy(accessToken = "token-b", chatgptAccountId = "account-b")).slug).isEqualTo("b")
        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        val switched = server.takeRequest()
        assertThat(switched.getHeader("Authorization")).isEqualTo("Bearer token-b")
        assertThat(switched.getHeader("chatgpt-account-id")).isEqualTo("account-b")
    }

    @Test fun `network failure does not invent fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val error = runCatching { resolver().resolve("retired", headers) }.exceptionOrNull()
        assertThat(error).isInstanceOf(CodexModelDiscoveryException::class.java)
        assertThat(error!!.message).contains("refresh")
    }

    @Test fun `malformed or empty list is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"models\":[{\"display_name\":\"missing slug\"}]}"))
        val error = runCatching { resolver().listModels(headers, true) }.exceptionOrNull()
        assertThat(error).isInstanceOf(CodexModelDiscoveryException::class.java)
    }

    @Test fun `supported in api false remains eligible for ChatGPT OAuth`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"slug":"oauth-only","visibility":"list","supported_in_api":false}]}"""))
        assertThat(resolver().listModels(headers, true).map { it.slug }).containsExactly("oauth-only")
    }

    @Test fun `priority and capability metadata are preserved`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[
          {"slug":"later","visibility":"list","priority":20,"input_modalities":["text"],"supports_parallel_tool_calls":false},
          {"slug":"first","visibility":"list","priority":10,"input_modalities":["text","image"],"supports_parallel_tool_calls":true}
        ]}"""))
        val models = resolver().listModels(headers, true)
        assertThat(models.map { it.slug }).containsExactly("first", "later").inOrder()
        assertThat(models.first().supportsVision).isTrue()
        assertThat(models.first().supportsParallelToolCalls).isTrue()
        assertThat(models.last().supportsVision).isFalse()
    }

    @Test fun `cache expires after ttl`() = runBlocking {
        var now = 0L
        server.enqueue(MockResponse().setBody(models("a")))
        server.enqueue(MockResponse().setBody(models("b")))
        val resolver = CodexModelResolver(
            clientVersion = "1.2.3",
            httpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
            modelsUrl = server.url("/backend-api/codex/models").toString().removeSuffix("/"),
            clock = { now },
            ttlMs = 100,
        )
        assertThat(resolver.resolve("codex-auto", headers).slug).isEqualTo("a")
        now = 101
        assertThat(resolver.resolve("codex-auto", headers).slug).isEqualTo("b")
    }

    @Test fun `redirect is rejected without forwarding credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/steal"))
        val error = runCatching { resolver().listModels(headers, true) }.exceptionOrNull()
        assertThat(error).isInstanceOf(CodexModelDiscoveryException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun resolver() = CodexModelResolver(
        clientVersion = "1.2.3",
        httpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
        modelsUrl = server.url("/backend-api/codex/models").toString().removeSuffix("/"),
    )

    private fun models(vararg slugs: String): String = slugs.joinToString(",", "{\"models\":[", "]}") {
        "{\"slug\":\"$it\",\"display_name\":\"$it display\",\"visibility\":\"list\",\"supported_in_api\":true,\"context_window\":200000}"
    }
}
