package ai.closepaw.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogCodexFallbackTest {
    private val catalog = ModelCatalog.fromJson(
        """{
          "codex-auto": {"display_name":"ChatGPT automatic","provider":"OPENAI_CODEX","api":"response","model_id":"codex-auto"},
          "gpt-5.2": {"display_name":"GPT","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}
        }"""
    )

    @Test
    fun `cold start resolves saved account and retired OAuth keys to automatic seed`() {
        for (name in listOf("codex-account:retired-slug", "gpt-5.2-codex", "gpt-5.4-codex", "gpt-5.5-codex")) {
            assertEquals(catalog.resolve("codex-auto"), catalog.resolveOrNull(name))
            assertEquals(catalog.resolve("codex-auto"), catalog.resolve(name))
        }
    }

    @Test
    fun `fallback never adds unavailable picker entries`() {
        catalog.resolveOrNull("codex-account:retired-slug")
        catalog.resolveOrNull("gpt-5.2-codex")
        assertEquals(setOf("codex-auto", "gpt-5.2"), catalog.names())
        assertEquals(listOf("codex-auto"), catalog.modelsFor(LLMProvider.OPENAI_CODEX).map { it.name })
        assertEquals(2, catalog.size)
        assertFalse("codex-account:retired-slug" in catalog)
    }

    @Test
    fun `live discovered entries take precedence over automatic fallback`() {
        val live = catalog.resolve("codex-auto").copy(name = "codex-account:live", modelId = "live")
        val refreshed = catalog.withExtraEntries(listOf(live))
        assertEquals(live, refreshed.resolveOrNull(live.name))
        assertEquals(live, refreshed.resolve(live.name))
    }

    @Test
    fun `unrelated names and API models are not normalized`() {
        for (name in listOf("unknown", "codex-account:", "gpt-5.2-chat", "gpt-5.2-codex-extra", " gpt-5.2-codex", "Gpt-5.2-codex", "other:codex-account:slug")) {
            assertNull(catalog.resolveOrNull(name))
        }
        assertEquals(LLMProvider.OPENAI_API, catalog.resolve("gpt-5.2").provider)
    }

    @Test
    fun `fallback requires an existing OAuth automatic seed`() {
        val noSeed = ModelCatalog.fromJson(
            """{"gpt-5.2":{"display_name":"GPT","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
        )
        val wrongProvider = noSeed.withExtraEntries(listOf(noSeed.resolve("gpt-5.2").copy(name = "codex-auto")))
        for (candidate in listOf(noSeed, wrongProvider)) {
            assertNull(candidate.resolveOrNull("codex-account:retired"))
            assertNull(candidate.resolveOrNull("gpt-5.2-codex"))
        }
    }
}
