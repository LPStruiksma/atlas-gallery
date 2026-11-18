# AI Edit architecture — local-first gallery, explicitly user-started inference

**Status:** proposed product/architecture boundary, not a shipped network feature or permission to spend. Parent [#183](https://github.com/LPStruiksma/atlas-gallery/issues/183); documentation unit [#186](https://github.com/LPStruiksma/atlas-gallery/issues/186). See the [provider research](PROVIDERS.md) for candidates and their *unverified* constraints. The [ATLAS vision](../product/ATLAS_VISION.md) and Samsung parity requirements still govern the product.

## Boundary: local intelligence versus a scoped editing request

**Automatic and background behavior is always local-first and offline.** Media scanning, indexing, thumbnail analysis, face detection/matching, People, body/attire/context inference, embeddings, visual and natural-language search, Photo DNA, Stories, and future Life Graph inference operate on-device. They must not automatically upload photos or derived intelligence, contact an inference provider, or turn an AI Edit configuration into a scanner/cloud-search integration. Models used for those background features ship or execute locally.

**AI Edit is different because the person explicitly starts it.** A user chooses **AI Edit** from a selected photo in grid/viewer (separate from the existing ordinary system/Samsung Edit action), supplies an instruction, chooses/configures a provider and presses Generate. For that request alone, ATLAS may use a hosted or user-configured self-hosted provider. A future local editing adapter can avoid remote transfer. The selected provider must not become a backdoor for bulk ingestion, background indexing or People enrichment.

| May leave device for a remote edit | Must stay on device / must not be attached implicitly |
|---|---|
| Explicitly selected source photo **content** (prefer an image payload stripped of unrelated metadata), instruction/prompt, user-chosen region/mask and *individually selected* reference photos, if the provider supports them | Every other library photo/video, full-library enumeration, People names/groups/face crops, identity evidence, embeddings, scanner/index database, search history, albums, Life Graph and usage export |
| Request options needed for the chosen edit (e.g. size/quality, model ID); transport/account metadata necessarily visible to the endpoint (e.g. client IP on direct connections) | GPS coordinates and source EXIF/location metadata **by default**; file paths, MediaStore URIs, unnecessary identifiers and private local lineage/metadata |

Remove embedded GPS/EXIF and unnecessary identifying metadata from outbound copies by default; do not rely on a provider to ignore it. A user who explicitly elects to send relevant metadata needs a distinct intentional action, not an incidental upload side effect. References and masks require user selection. Outputs and temporary files are app-private until the user accepts a **new copy**; keep original unchanged and local edit lineage local (save-copy flow: [#189](https://github.com/LPStruiksma/atlas-gallery/issues/189)). Provider-side retention/deletion depends on that provider's terms; cancelling locally cannot guarantee deletion of a request already received remotely.

## Provider-neutral interface and capability negotiation

Conceptual request (not a committed Kotlin API):

```text
ImageEditRequest {
  source, instruction, optionalMask, optionalReferences,
  outputSpec, options
}
ImageEditProvider {
  capabilities() -> EditCapabilities
  submit(request) -> EditJob | EditResult
  poll(job) -> EditState           // for queued endpoints
  cancel(job) -> CancelResult      // best effort after submission
}
EditState = queued | running | completed | refused | failed | cancelled
```

Negotiate **before** offering controls or dispatching a request: input MIME/size, region masks, number of references, output resolution/aspect, seed determinism, multiple candidates, iterative/multi-turn editing, synchronous versus async jobs, cancellation, provenance, rate/region limits and cost estimate (if available). A mask accepted by an endpoint is not a promise of pixel-exact unchanged areas. Unsupported fields should be disabled or explained; the adapter must not silently discard a requested mask/reference. Validate size/limits *locally* before upload and show available price information without implying an unknown quote is guaranteed. Use versioned provider-specific adapters rather than assuming all hosted/self-hosted models support the same schema. Generic self-hosted endpoints need an explicit, authenticated ATLAS protocol (see [#193](https://github.com/LPStruiksma/atlas-gallery/issues/193)).

**Provider choice is sticky per request.** If the chosen provider is offline, refuses, times out, lacks a capability or errors, explain that outcome and offer Retry, Change prompt, or **Choose another provider**. Never silently fail over or resend a photo to a different provider; each new target requires the user to choose it. No automatic background retries or surprise billable requests. Local-only editing, when available, must also be selected rather than assumed as an invisible fallback.

## User journey and disclosure

On **first use of remote AI Edit**, show a short, understandable explanation: the selected photo, text instruction and any explicitly added masks/references will be sent to the chosen provider, which processes data under its own privacy/retention terms; include the configured provider's identity and a path to its terms/settings. Keep the explanation in Settings/help afterward. This is a **one-time, brief disclosure**, not a consent dialog for every edit. Each later Generate remains an affirmative user action, and there is no upload merely from opening the editor, selecting a photo, scanning or previewing. Surface estimated costs where known and avoid claiming guaranteed confidentiality, deletion or content acceptance.

The editor previews a temporary candidate and offers **Accept / Retry / Change prompt / Back**, plus before/after inspection. Accept saves a new MediaStore copy only; leaving without accepting discards local working candidates according to a documented retention policy. Do not replace the system Edit affordance or add persistent grid/viewer chrome merely to expose AI Edit.

## Service kinds, registry and key check (AIE-19, [#430](https://github.com/LPStruiksma/atlas-gallery/issues/430))

A stored service (`ProviderProfile`) has a `kind`: `ATLAS_PROTOCOL` (custom server, [PROTOCOL.md](PROTOCOL.md)), `GEMINI` or `FAL`, plus an optional model id and the last key-check status. Profiles stored before #430 have no kind and read as `ATLAS_PROTOCOL`. A stored profile whose kind this build does not know is kept, listed disabled as "Not available in this version" with Remove only, and never used; saving other profiles preserves it.

`ProviderRegistry` maps a kind to its runtime: `ATLAS_PROTOCOL` runs through `EditJobRunner`; hosted kinds register an `ImageEditProvider` factory when their adapter merges (Gemini [#192](https://github.com/LPStruiksma/atlas-gallery/issues/192), fal [#429](https://github.com/LPStruiksma/atlas-gallery/issues/429)). "Add service" offers only kinds with a runtime, in fixed order: Google Gemini, fal.ai, then Custom server under Advanced. Hosted presets carry their base URL, so nobody types one, and validate host and scheme exactly as custom profiles do (HTTPS, no user info, query or fragment, path ending in `/`).

**Key check.** User-initiated only (Save, or "Check key" on a service), one authenticated GET through `EditHttpTransport` (host-pinned HTTPS, no redirects), never an image, prompt or body. Results: "Key works"; "This key was not accepted" (nothing is saved for a new service); "Couldn't check. No connection." or "Couldn't check. {Service} didn't respond." (the key is kept, status "Not checked yet"). There is no "out of credit" result: credit problems are reported when an edit is attempted (#431).

| Kind | Call | Not accepted | Source |
|---|---|---|---|
| Gemini | `GET https://generativelanguage.googleapis.com/v1beta/models`, header `x-goog-api-key` | 400 (`INVALID_ARGUMENT`, reason `API_KEY_INVALID`), 401, 403 | models.list (official API reference); base URL and header as used by the official `googleapis/python-genai` client. A made-up key observed 2026-11-18 returns 400 `API_KEY_INVALID`; no key returns 403 `PERMISSION_DENIED`. |
| Custom server | `GET {base}capabilities`, the profile's own auth | 401, 403 | [PROTOCOL.md](PROTOCOL.md): every request, including capabilities, is authenticated. 404 and others report "didn't respond", since a wrong path is not proof of a wrong key. |
| fal | none yet: "Saved. Checked on your first edit." | n/a | fal's platform API documents `GET https://api.fal.ai/v1/models` with authentication optional (it cannot prove a key) and `GET /v1/models/pricing` as key-required, but its header text says an *admin* key, so an ordinary key could be refused. #429/#432 can add a check once verified with a real key. |

429, 5xx, timeouts and redirects map to "didn't respond". Pasted keys are trimmed; a key with inner whitespace or control characters is refused locally and never sent. Keys live only in the Keystore vault; Remove deletes the key at once, and removing the default makes the next usable service in list order the default.

## Errors, refusals and provider constraints

### Bounded HTTP errors and documented hosted refusal shapes (AIE-24, #456)

`EditHttpTransport` retains at most **64 KiB** from a 4xx/5xx response solely for an adapter to
inspect a documented structured error. An advertised or streamed body over that limit is
discarded; it is never placed in a failure's `toString()`, logs, telemetry, or user-visible text.
The HTTP status keeps its existing auth/quota/client/server mapping and retryability. Reading an
error does not change deadlines, cancellation, the rule that POST is never replayed, or the
existing bounded retry of idempotent calls.

Adapters match only allowlisted machine fields, never a provider's free-text `message`/`msg`:

| Provider shape | ATLAS outcome | Reviewed source and date |
|---|---|---|
| Gemini `google.rpc.Status.error.details[].reason`, when the reason is one of Gemini's documented prompt/finish policy reasons (for example `IMAGE_SAFETY`) | `Refused("error:<reason>")`; other HTTP errors retain their technical mapping | [Google RPC error model](https://cloud.google.com/apis/design/errors#error_model) and [Gemini `FinishReason` / `BlockedReason` reference](https://ai.google.dev/api/generate-content#FinishReason), reviewed **2026-11-18** |
| fal validation `detail[].type == "content_policy_violation"` | `Refused("fal:content_policy_violation")`; any other 422 remains an invalid-request failure | [fal queue/API error handling](https://docs.fal.ai/model-apis/model-endpoints/queue), reviewed **2026-11-18** |

Successful Gemini `promptFeedback.blockReason`/policy `finishReason` and fal
`has_nsfw_concepts=true` keep their existing refusal mappings. Unknown codes and prose are not
guessed into policy refusals.

| Outcome | Meaning | UI/telemetry treatment |
|---|---|---|
| `refused_policy` | Provider explicitly declines a prompt/image under its policy or moderation | State a **provider refusal**, with a sanitized category/code when available; don't imply a network defect or promise a different provider will comply |
| `refused_capability` | A requested operation is unsupported or rejected by provider constraints | Explain the unsupported option; offer user-directed change |
| `transport_error`, `auth_error`, `rate_limited`, `server_error`, `timeout` | Technical or account failure, not known content-policy refusal | Offer actionable fix or manual retry; report unknown decisions as unknown, not invented policy refusals |
| `cancelled` | User cancels; remote cancellation may be best-effort | Remove local temporary state; be transparent if remote processing might continue |
| `completed` | A candidate was returned, not yet accepted | Keep source intact and show preview/save-copy controls |

Providers differ in moderation and legal/licence constraints. ATLAS does not impose an additional content-category filter on the local gallery or promise that any external provider accepts all lawful requests. Benchmark refusal rate independently of output quality using synthetic/licensed inputs; do not put free-text prompts or media in logs, crash reports or analytics.

## Credentials and network options

- **Direct BYOK on phone:** user configures a provider credential, used only for their chosen edit endpoint. Protect the credential with an Android Keystore-backed per-install key and encrypted app-private storage. **Exclude both stored ciphertext and key material from all Android backups/restore transfers**; reconfiguration after restore is preferable to leaked credentials. Never embed keys in the APK, repository, URL/query strings, usage export ([#173](https://github.com/LPStruiksma/atlas-gallery/issues/173)), logs, crash reports or telemetry; redact Authorization headers and endpoint secrets from diagnostics. A compromised phone/app process can still misuse a locally stored key; Keystore does not turn BYOK into a server-held secret.
- **Optional relay:** a user-controlled or trusted backend can hold provider keys and enforce quotas, but adds a server trust boundary, operational cost, uptime dependency, and potentially another processor that can observe images/prompts. Its privacy, retention, region, authentication and key custody require separate review. Do not silently switch between direct and relay paths.
- **Transport:** only connect to the explicitly configured HTTPS host (or a deliberately configured trusted self-hosted endpoint with an evaluated secure transport). Restrict redirects to prevent credential and image forwarding to unexpected hosts; reject cleartext HTTP by default. No generic unrestricted proxy or SDK-driven analytics/uploads.
- **Transport limits and auth variants (`EditHttpTransport`, #441, 2026-11-18):**
  - *Reply size.* Default caps are unchanged: 1 MB for a JSON reply held in memory, 32 MB for a `downloadTo` file. A caller may set `maxReplyBytes` for one request up to `MAX_REPLY_CEILING` = 40 MB (the largest documented hosted output is a 4096 x 4096 Gemini 4K image, about 33 MB once base64-encoded in JSON). Above 8 MB (`MAX_IN_MEMORY_REPLY`) the reply must be spooled to an app-private `downloadTo` file, which now works for GET, POST and PUT and costs an 8 KB copy buffer of heap; an in-memory reply peaks near three times its size. A reply above any cap, default or caller-chosen, fails as `RESPONSE_TOO_LARGE` and is never retried (hosted adapters report it as `SERVER`, message `HTTP_RESPONSE_TOO_LARGE`).
  - *Auth.* `None`, `Bearer` (`Authorization: Bearer <secret>`), `CustomHeader` (any safe name except reserved ones such as `Authorization`) and `Scheme` (`Authorization: <scheme> <secret>`, for example fal.ai's `Key`). The scheme word is fixed in adapter code, letters only, at most 16, never `Basic`; secrets reject CR, LF, NUL and spaces. Every variant's `toString()` is redacted.
  - *Methods.* GET, POST, DELETE, PUT. POST is never replayed; PUT is replayed only when the caller passes `idempotent = true`; GET and DELETE keep the bounded retry. POST needs a body, PUT may have one, GET and DELETE may not.

**Network permission gate:** ATLAS currently has no `INTERNET` permission. Adding it requires a **separate protected manifest change** in `app/src/main/AndroidManifest.xml`, [AIE-6/#191](https://github.com/LPStruiksma/atlas-gallery/issues/191), after reviewed AI Edit transport code. Lukas must merge that protected-path PR himself. This documentation does **not** enable network access. When permission is added, enforce a narrow AI Edit-only transport and static tests showing scanner, People and search cannot open network connections. Offline gallery behavior must remain fully functional.

## Threat model and controls

| Threat / failure mode | Required control |
|---|---|
| Accidental bulk upload through scanner, search, provider SDK or auto-fallback | No transport from background pipelines; user-action-only call site; deny implicit provider fallback; contract/static tests |
| GPS, hidden EXIF, paths, people embeddings or unintended reference images leave device | Build an explicit allowlisted payload from selected input; strip metadata by default; synthetic negative tests of outbound fields |
| Credential theft, diagnostic leakage or backup restoration onto another device | Keystore-backed encryption, backup exclusions, strict redaction, no keys in exports; re-enter credentials after restore |
| Host spoofing, redirect exfiltration, rogue self-hosted URL | HTTPS, explicit endpoint configuration, no cross-origin auth redirect, host validation and clear ownership disclosure |
| Unexpected provider retention, policy refusal, spend or overbilling | One-time processor disclosure, terms link, provider/refusal taxonomy, visible cost if known, user-initiated retry, no silent cross-provider transmission |
| Misleading progress, cancellation or destruction of original | Distinguish queued/running/refused/failed, best-effort remote cancel disclaimer, temp-file cleanup, save new copy only |
| Public-repo or usage-telemetry exposure | Synthetic/licensed fixtures only; avoid storing real media, keys, prompts, file identifiers or People data in CI, GitHub or usage export |

Implementation, paid API calls, external image uploads during development, credentials, manifest edits and backend deployment each require their own scoped unit and any applicable owner gate. The existing on-device intelligence architecture is **not** changed by this AI Edit proposal.
