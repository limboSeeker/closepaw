# ChatGPT OAuth model discovery fix

ClosePaw no longer sends a static ChatGPT OAuth model ID. The sign-in model list is fetched with
fresh OAuth headers from `GET https://chatgpt.com/backend-api/codex/models?client_version=0.165.0`.
`0.165.0` is an explicitly maintained compatibility version matching upstream Codex release
`rust-v0.165.0`; it is intentionally independent of ClosePaw's app version. Account models
advertised with `visibility: "list"` are shown. `supported_in_api` is not an eligibility filter for
ChatGPT OAuth.
The server-provided display name is shown and its `slug` is used for inference.

The in-memory catalog is keyed by hashed account identity and access-token fingerprint and has a
five-minute TTL. Account or token changes invalidate it; sign-out clears it. Redirects are rejected so authorization headers are
not forwarded. A missing, malformed, empty, or unavailable catalog is an actionable error and never
falls back to API keys, third parties, a retired seed row, or a guessed model ID. A saved model that
is no longer advertised resolves deterministically to the first eligible model in the account's
priority-ordered catalog before both streaming and nonstreaming request bodies are built. Discovery
and inference share the `originator: closepaw` and `version: 0.165.0` headers. Returned image-input
and parallel-tool-call capabilities are honored; image content is rejected before transport when a
selected model is text-only. An explicit unsupported-model HTTP 400 triggers one forced refresh and
one re-resolution before any output, with no retry loop for unrelated errors.

Protocol evidence was checked against OpenAI Codex upstream commit
`b75d1e89665d256bf2c674b9590776f440c9bae7` (2026-10-29), notably
`codex-rs/codex-api/src/endpoint/models.rs`, `codex-rs/model-provider/src/models_endpoint.rs`, and
the shared `openai_models` schema. Upstream uses the native ChatGPT Codex backend route requested by
this integration, appends a whole semantic `client_version`, supplies bearer and
`ChatGPT-Account-ID` headers, and rejects explicit-catalog redirects.

The current public [Sign in with ChatGPT models and inference documentation](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
describes the newer public `api.openai.com` integration and explicitly differs from this existing
native Codex backend integration. This change intentionally preserves ClosePaw's native backend,
SSE, function calling, image input, and accessibility behavior while adopting upstream catalog
semantics.

JVM tests cover parser filtering and malformed lists, headers and client version, account/token
switches, network failure, redirect rejection, retired-model resolution, and the final model field
in streaming and nonstreaming payloads. They use injected MockWebServer URLs only from test code.
These tests were added but not executed locally because this workspace has no usable Java/Android SDK.
