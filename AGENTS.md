# Repository Guidelines

## Project Overview

Nexus Forge is a full-stack application skeleton: Java 26 + Spring Boot 4.1 backend, Vue 3.5 + Vite 8 frontend, PostgreSQL/Redis/S3-compatible storage locally via Docker.

The backend is a Gradle multi-module project split by capability. `nexus-forge-web` is the only bootable module and aggregates shared infrastructure (`core`), common contracts (`common`), auth, user, file, and ai modules. `nexus-forge-visual` exists as a planning stub.

The AI module (`nexus-forge-ai`) is a **full LLM gateway**, not a stub: it ships `ChatModel` SPI (`com.nexusforge.model.ChatModel`) with OpenAI (`OpenAiChatModel`) / OpenAI-compatible base (`OpenAiCompatibleChatModel`) / Qwen / DeepSeek / Ollama / Anthropic implementations, a fallback chain (`ChatModelRouter`), Micrometer-backed usage metering (`UsageRecorder`), Caffeine rate-limit (`RateLimitGuard`), daily-token quota (`QuotaService`), conversation persistence (`ConversationService`), **per-user / per-vendor / private-Key preference resolution** (`PreferenceResolver` + `VendorChatModelFactory`), function-calling (`ToolRegistry` / `ToolExecutor` / `EchoTool`), SSE stream parsing (`OpenAiStreamParser` / `AnthropicMessagesStreamParser`), REST endpoints under `/api/ai/{chat,chat/stream,conversations,preference,usage}` plus admin `/api/admin/ai/global-default`, and is wired via Spring Boot 4.x `@AutoConfiguration` in `bootstrap/AiAutoConfiguration`.

> **Package naming caveat**: the AI module has two coexisting package roots — newer code lives under `com.nexusforge.ai.X.*` (`ai/client/`, `ai/config/`, `ai/controller/`, `ai/entity/`, `ai/provider/`, `ai/repository/`, `ai/service/`, `ai/tools/`); older core code (`LlmClient`, `FunctionCallAggregator`, `RateLimitGuard`, `QuotaService`, `ConversationService`, `UsageRecorder`, `ChatModelRouter`, all controllers, `AiProperties`, all providers, the stream parsers, error mappers) lives under the bare `com.nexusforge.X.*` root. New AI-related code should go under `com.nexusforge.ai.X.*`; full migration to one prefix is a tracked refactor.

The frontend lives in `nexus-forge-ui/`, uses npm, PrimeVue 5 (currently pinned to `^5.0.0-rc.1`, **release candidate — pin strictly until stable**), Pinia, Vue Router, Axios, Zod, Tailwind CSS 4, and SCSS design tokens. Vite proxies `/api` to the backend on `localhost:8080`.

## Architecture & Data Flow

Backend module DAG:

```text
nexus-forge-web
├── nexus-forge-core  ──► nexus-forge-common
├── nexus-forge-auth  ──► nexus-forge-user ──► nexus-forge-common
├── nexus-forge-user  ──► nexus-forge-common
├── nexus-forge-file  ──► nexus-forge-common
├── nexus-forge-ai    ──► nexus-forge-core, nexus-forge-common   # LLM gateway
└── nexus-forge-visual──► nexus-forge-common                   # stub
```

Request flow:

1. `RequestIdFilter` creates or propagates `X-Trace-Id`, writes MDC `traceId`, and logs method/path/status/cost.
2. Spring Security in `SecurityConfig` runs stateless CORS/JWT auth. `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, Swagger/OpenAPI paths are public; other routes require auth.
3. `JwtAuthenticationFilter` validates access tokens, rejects refresh tokens on business paths, checks Redis blacklist, loads roles via `PermissionLoader`, and sets `UserPrincipal(userId, username)` as the authentication principal.
4. Controllers return `Result<T>`; service errors throw `BusinessException`/`AuthException` with `ResultCode`. The AI gateway additionally throws `LlmException` for upstream / quota / rate-limit / global-default-not-configured errors, mapped by the same handler into JSON `Result` responses.
5. `GlobalExceptionHandler` maps domain errors into JSON `Result` responses and special statuses such as idempotency conflict `409`, rate limit `429`, upload too large `413`.

Auth data flow:

- `AuthController` → `AuthenticationManager`/`AuthService` → `JwtUtil` issues `TokenBundle(access, refresh)`.
- Refresh rotates both tokens, stores the active refresh JTI at `auth:refresh:{userId}`, and blacklists consumed refresh tokens.
- Logout blacklists access/refresh JTIs in Redis using remaining TTL. `UserBannedEvent` from the user module is consumed by auth to revoke refresh state.

File data flow:

- Business code uses `FileClient` from `nexus-forge-common`; do not depend directly on storage internals from other modules.
- `FileClientImpl` → `FileService` → `StorageProvider` → `S3StorageProvider` using AWS SDK v2.
- Active vendor comes from `storage.vendor` and `StorageProperties`: `rustfs`, `minio`, `aliyun`, `tencent`, or `aws`.

AI gateway data flow:

- `AiAutoConfiguration` (`com.nexusforge.bootstrap.AiAutoConfiguration`, registered as a Spring Boot 4.x `@AutoConfiguration`) assembles all AI beans in a deterministic phase order: `AiProperties` → `ChatModelHttpSupport` → `ApiKeyCipher` → vendor `ChatModel`s → `ChatModelRouter` (fallback chain) → `QuotaService` / `RateLimitGuard` / `UsageRecorder` → `LlmClient` → controllers. Vendor registration happens in `AiVendorRegistry` (`com.nexusforge.ai.config`) — adding a new vendor means registering there, not in `LlmClient`.
- `AiController` / `AiStreamController` / `AiConversationController` / `AiUsageController` resolve per-request preferences via `PreferenceResolver.resolve(userId, dto.model)` (request model → user pref → global default → yaml). Result is `Resolved(vendor, model, apiKey, source)` with `source ∈ {SYSTEM, USER_OVERRIDE_SYSTEM_KEY, USER_PRIVATE_KEY}`. Domain errors raised here (`LlmException` + 9 specific `LLM_*` codes) are mapped to `Result` JSON via `error/LlmErrorMapper`.
- System Key path: `LlmClient.call(req, vendor, model)` writes `pref.model` into `req.options["model"]`; `OpenAiJsonMapper.toOpenAi` reads `options["model"]` first, falls back to the vendor's `cfg.defaultModel` (yaml); never reads `req.model` for the upstream payload (that field is the router channel only).
- Private Key path: `VendorChatModelFactory` dynamically constructs an OpenAI-compatible `ChatModel` cached by `sha256(apiKey)`. The key is decrypted from `user_ai_preference.encrypted_api_key` (AES-256-GCM, key derived from `spring.ai.preference.master-key` → falls back to `jwt.secret`). Private Key requests bypass platform quota, fallback chain, and IP rate-limit.
- `ConversationService.sendMessage` persists user/assistant messages, calls the LLM, records usage into `ai_message_usage` (windowed), updates the conversation model on first turn, and feeds Micrometer via `UsageRecorder`. `UsageService` / `ContextWindowBuilder` aggregate per-user usage and assemble the conversation context window.
- `ai_global_default` is the single-row global default seeded with sentinel `model='__UNSET__'`. Until an admin calls `PUT /api/admin/ai/global-default`, every system-mode request returns `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`. Vendor registration also requires `spring.ai.providers.<vendor>.default-model` in yaml — missing it fails-fast at boot.
- Streaming uses `StreamingResponseBody` (not `SseEmitter`) to avoid Spring 7 + Tomcat 11 chunked-transfer-encoding EOF issues. `AiStreamController.writeChunks` does one `Flux.subscribe` + `CountDownLatch.await` per request to prevent cold-Flux double-subscription (which would issue 2 LLM HTTP requests). Tool-call deltas are aggregated by `FunctionCallAggregator` (keyed by `index`) and a synthesized terminating frame with full `toolCalls[]` is emitted when `finishReason="tool_calls"`. The wire format is flat camelCase `ChatChunk` JSON frames — **not** OpenAI `chat.completion.chunk` (no `choices[]`, no `data: [DONE]`, no `object`/`created`/`model`); custom error frame is `{"error": "..."}`.

Flyway data flow:

- Spring Boot 4.1 removed the `spring-boot-flyway` auto-configuration module and Flyway publishes no Spring Boot starter. **`spring.flyway.*` keys are still present in `application.yaml`, `application-dev.yaml`, and `application-prod.yaml` and are NOT dead config** — they are consumed by hand-written `FlywayMigrationRunner` (`nexus-forge-user`, registered as `@Component`, runs in `@PostConstruct` before JPA). The runner reads `spring.flyway.{enabled, locations, baseline-on-migrate, validate-on-migrate, baseline-version}` via `@Value`. On `FlywayValidateException` (checksum mismatch after editing an applied SQL file) it auto-runs `flyway.repair()` and retries — already-applied migrations are kept idempotent via `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... $$` blocks in the SQL.
- All migrations live under `nexus-forge-user/src/main/resources/db/migration`. The newest two (`V20260801_001/002`) add `ai_global_default` (single-row with `id=1 CHECK (id=1)` and `model='__UNSET__'` sentinel) and `user_ai_preference` (per-user, AES-encrypted API key as `BYTEA`, plus `api_key_fingerprint VARCHAR(16)` for UI display).
- **⚠️ Production schema-coupling risk**: `application-prod.yaml` sets `spring.jpa.hibernate.ddl-auto: validate`. Hibernate will refuse to start if any JPA entity field/column drifts away from the Flyway-managed schema. Every entity change MUST be paired with a migration in the same commit; if you forget, prod boot will fail (no Hibernate auto-fix). Dev uses `ddl-auto: none` (Flyway is sole authority), so schema drift shows up first in prod — run `./gradlew :nexus-forge-web:bootRun --args='--spring.profiles.active=prod'` locally before merging entity changes.

Frontend flow:

- `src/main.ts` installs Pinia + persisted state, router, Toast/Confirm services, PrimeVue theme `MyPreset`, then calls `bootstrapAuth()` before mount.
- `stores/auth.ts` owns token state, AES-persisted auth data, single-flight refresh, and `ensureFreshAccess()`.
- `utils/http/interceptors.ts` injects Bearer tokens, refreshes once on 401, maps business codes to typed errors, and dispatches `auth:expired` on session loss.
- Router guards in `router/index.ts` redirect protected routes to `auth-view?tab=login&redirect=...`.

## Key Directories

- `nexus-forge-web/` — Spring Boot entry (`NexusForgeApplication`), OpenAPI config, runtime `application*.yaml`, and integration tests.
- `nexus-forge-common/` — shared API contracts: `Result`, `ResultCode`, exceptions (`BusinessException` / `AuthException` / `LlmException` + `BaseException`), `BaseEntity` (UTC `createdAt`/`updatedAt`), `UserPrincipal`, file DTOs/interfaces (`FileClient`, `FileMeta`, `FileBizType`, `FileAccess`, `UploadCredential`), events (`UserBannedEvent`), cache helpers (`CachedValueLoader`), AI DTOs (`ChatRequest`/`ChatResponse`/`ChatChunk`/`ChatUsage`/`DeltaToolCall`/`ToolCall`/`ToolDefinition`/`Role`), register-request DTO, and the SSE codec helper `chat/SseEventCodec` (currently NOT wired into `AiStreamController` — the controller writes `data: <json>\n\n` by hand; use `SseEventCodec` only if you add a new streaming endpoint).
- `nexus-forge-core/` — cross-cutting infrastructure: global exception handling, request logging, `@Idempotent`, `@RateLimit`, auto-configuration, Caffeine rate limiter backing `RateLimitGuard` in the AI module.
- `nexus-forge-auth/` — Spring Security: `config/{SecurityConfig,CorsConfig,ClockConfig,JwtProperties}`, `filter/{JwtAuthenticationFilter,JwtQueryTokenFilter}`, `controller/AuthController` (`/api/auth/{login,register,refresh,logout}`), `service/AuthService`, `util/JwtUtil`, `handler/JsonAuthHandlers` (401/403 JSON writer implementing `AuthenticationEntryPoint` + `AccessDeniedHandler`), `listener/AuthEventListener` (consumes `UserBannedEvent` from user module to revoke refresh state), `security/{LoginUser,UserDetailsServiceImpl,UserLoader,PermissionLoader}`, DTOs `dto/{LoginRequest,LogoutRequest,RefreshRequest,TokenBundle}`.
- `nexus-forge-user/` — `User` entity/repository/service/controller (`/api/users/**`), user DTOs/VOs (`dto/{ChangePasswordDto,UpdateUserDto}`, `vo/UserVo`), role and quota providers consumed by other modules (`service/{UserRoleProvider,UserQuotaProviderImpl}` — UserRoleProvider is read by `PermissionLoader`; UserQuotaProviderImpl is read by AI `QuotaService`). **Flyway migrations under `src/main/resources/db/migration/`** and `flyway/FlywayMigrationRunner.java` (Spring Boot 4.1 has no auto-config).
- `nexus-forge-file/` — S3-compatible storage abstraction. `controller/FileController` (`/api/files/**`), `file/FileClientImpl` (business-facing facade; `FileClient` interface lives in `nexus-forge-common`), `service/FileService`, `storage/{StorageProvider (SPI),S3StorageProvider (AWS SDK v2)}`, `bootstrap/StorageInitializer` (boot-time bucket auto-create, gated by `storage.auto-create-bucket`), `config/StorageProperties` (`storage.vendor` ∈ `rustfs|minio|aliyun|tencent|aws`; MinIO/RustFS share `path-style=true` S3 semantics).
- `nexus-forge-ai/` — LLM gateway (see "AI gateway data flow" below):
  - `bootstrap/AiAutoConfiguration` — Spring Boot 4.x `@AutoConfiguration` entry point; registers all AI beans in deterministic phase order. Adding beans here, not in `@Configuration` classes, is the convention.
  - `model/ChatModel` — SPI (`call` / `stream` / `name` / `capabilities`); implementations throw `LlmException` only, never raw third-party exceptions.
  - `provider/openai/` — `OpenAiChatModel` (OpenAI-hosted), `OpenAiCompatibleChatModel` (base class for all OpenAI-wire-compatible vendors; constructor `(vendor, defaultBaseUrl, defaultModel)`), `OpenAiJsonMapper` (request→OpenAI wire serializer), `QwenChatModel` / `DeepSeekChatModel` / `OllamaChatModel` (all extend `OpenAiCompatibleChatModel`). **All OpenAI-compatible vendors go in `provider/openai/`, not `provider/<vendor>/`** — one directory per protocol family, not per vendor.
  - `provider/anthropic/` — `AnthropicChatModel` + `AnthropicJsonMapper` + `AnthropicMessagesStreamParser` (**Anthropic is a complete provider, not a skeleton** — all three files are full implementations).
  - `provider/support/` — `ChatModelHttpSupport` (WebClient factory, common headers/timeouts) + `CircuitState` (per-vendor circuit breaker state used by `ChatModelRouter`).
  - `router/ChatModelRouter` — fallback chain over the `Map<vendor, ChatModel>` from `AiVendorRegistry`, using `CircuitState` to skip tripped breakers.
  - `client/{LlmClient,FunctionCallAggregator,RateLimitGuard,QuotaService,ConversationService,UsageRecorder,ToolRegistry,ToolExecutor,ToolResult}` — facade + helpers. `LlmClient` has both `call(...)` and `stream(...)` overloads, each with system-mode (fallback chain + quota + IP rate-limit) and private-key-mode (skip quota/fallback/IP-RL) branches.
  - `controller/{AiController (/api/ai/chat),AiStreamController (/api/ai/chat/stream, SSE),AiConversationController (/api/ai/conversations/**),AiUsageController (/api/ai/usage/**)}` + `ai/controller/{AiPreferenceController (/api/ai/preference),AiAdminController (/api/admin/ai/global-default)}`.
  - `service/{PreferenceResolver,AiPreferenceService}` (under `ai/service/`) + `service/{ConversationService,QuotaService,UsageService,ContextWindowBuilder}` (under bare `service/`) — resolver and admin-facing service use the `ai/` prefix, business services don't.
  - `stream/{OpenAiStreamParser,SseFormat}` — upstream SSE line→`ChatChunk` parser; `SseFormat` defines the line / event-channel naming (`event: delta|finish|error|done`).
  - `entity/{AiConversation,AiMessage,AiMessageUsage}` (bare `entity/`) + `ai/entity/{AiGlobalDefault,UserAiPreference}` (under `ai/`).
  - `repository/{AiConversationRepository,AiMessageRepository,AiMessageUsageRepository}` (bare) + `ai/repository/{AiGlobalDefaultRepository,UserAiPreferenceRepository}` (under `ai/`).
  - `tools/EchoTool` — example tool registered with `ToolRegistry`.
  - `error/{LlmErrorMapper,StreamCancelledException,StreamTimeoutException,StreamUpstreamException}` — LlmException mapper + stream-specific markers.
  - `ai/client/ApiKeyCipher` — AES-256-GCM helper, key derived from `spring.ai.preference.master-key` → `jwt.secret` fallback.
  - `ai/config/AiVendorRegistry` — vendor registration; constructor injection makes adding a new vendor a one-liner.
  - All controllers have `controller/dto/{ChatRequestDto,SendMessageDto,CreateConversationDto,UpdateTitleDto,PinConversationDto}` + `controller/vo/{ConversationVo,ConversationDetailVo,MessageVo,UsageVo,UsageSummaryVo}`; admin/preference controllers have `ai/controller/dto/{PreferenceVo,UpdatePreferenceDto,UpdateGlobalDefaultDto}`.
- `nexus-forge-ui/src/` — Vue app source: `api/{auth,user}.ts`, `composables/useAuthBoot.ts` (defines the `bootstrapAuth()` called from `main.ts` before mount), `stores/{auth,layout}.ts`, `router/{index,routes}.ts`, `utils/http/{interceptors,errors}.ts` + `utils/error.ts`, `views/`, `layout/`, `components/` (auto-imported by `unplugin-vue-components` with `PrimeVueResolver`), `styles/{base,components}/...scss` + `styles/main.scss` (entry), `themes/index.ts` (exports `MyPreset` PrimeVue theme), `types/{auth.ts (TokenBundle/TokenSlot),models/...}`.
- `docker/{Postgres,Redis,MinIO,RustFS}/` — per-service Compose stacks and env examples. MinIO and RustFS both bind `9000/9001`; do not run them together without remapping.
- `.github/workflows/ci.yml` — CI definition for backend Gradle build/test and frontend npm lint/build.
- `docs/ROADMAP.md` — backlog/status reference, not an implementation source of truth.

## Development Commands

Backend:

```bash
./gradlew :nexus-forge-web:bootRun          # run backend, default profile dev, port 8080
./gradlew test                              # unit tests; web integration tests are excluded
./gradlew :nexus-forge-web:test -Pintegration
./gradlew --no-daemon clean build           # CI backend command
```

On Windows, `gradlew.bat` is available for the same Gradle tasks.

Frontend:

```bash
cd nexus-forge-ui
npm install                                # CI uses npm install, not npm ci, due lockfile drift
npm run dev                                # Vite dev server, default port 5173
npm run build                              # vue-tsc -b && vite build
npm run lint                               # ESLint flat config
npm run format                             # Prettier over src/**/*.{vue,ts,js,css,scss,json}
```

Local services:

```bash
cd docker/Postgres && cp .env.example .env && docker compose up -d
cd ../Redis       && cp .env.example .env && docker compose up -d
cd ../RustFS      && cp .env.example .env && docker compose up -d
```

Before backend boot, create root `.env` from `.env.example` and set at least `DB_PASSWORD` and a 32+ byte `JWT_SECRET` (`openssl rand -base64 48`).

## Code Conventions & Common Patterns

Backend:

- Package root is `com.nexusforge`. Keep new modules under that root so the web app's default component scan sees them.
- Keep dependency direction clean. Shared DTOs, enums, exceptions, events, and cross-module interfaces belong in `nexus-forge-common`.
- Use layered Spring code: `controller` → `service` → `repository`/adapter. Use constructor injection, normally via Lombok `@RequiredArgsConstructor`; avoid field injection.
- API controllers return `Result<T>`. Add new business codes to `ResultCode`; throw `BusinessException`/`AuthException`/`LlmException` instead of manually building error responses. AI gateway errors (`LLM_CONFIG_MISSING`, `LLM_MODEL_NOT_FOUND`, `LLM_PROVIDER_ERROR`, `LLM_UPSTREAM_TIMEOUT`, `LLM_RATE_LIMITED`, `LLM_QUOTA_EXCEEDED`, `LLM_ALL_VENDORS_FAILED`, `LLM_CIRCUIT_OPEN`, `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED`) live in the same `ResultCode` enum.
- If a new error code needs a non-400 HTTP status, update `GlobalExceptionHandler.mapStatus`.
- Request DTOs use Jakarta validation annotations and `@Valid`; validation errors are flattened by the global handler.
- Public endpoints should add `@SecurityRequirements` because `OpenApiConfig` applies global bearer auth to Swagger.
- JPA entities extend `BaseEntity` for UTC `createdAt`/`updatedAt`; repositories extend `JpaRepository<T, Long>`.
- Redis keys currently cover idempotency (`idem:` + sha256), JWT blacklist (`auth:blacklist:`), refresh state (`auth:refresh:`), user roles (`auth:roles:`), and AI rate-limit counters (`ai:rl:` in `RateLimitGuard`, keyed by `user:{id}` or `ip:{addr}`, with bucket info stored as a hash — namespaced separately from `core/RateLimitAspect`'s `rl:` prefix).
- Cross-cutting annotations live in `core`: use `@Idempotent(key = "<SpEL>")` and `@RateLimit(key = "<SpEL>")` instead of ad hoc duplicate-submit or rate-limit logic. Both aspects call `tryAcquire` exactly once in their `@Around` — keep it that way.
- **AI gateway rules of thumb**:
  - New vendors go in `nexus-forge-ai/.../provider/<protocol>/` (one directory per protocol family, NOT per vendor). Today: `provider/openai/` holds `OpenAiChatModel` (OpenAI-hosted) plus `OpenAiCompatibleChatModel` and all its subclasses (`QwenChatModel`, `DeepSeekChatModel`, `OllamaChatModel`); `provider/anthropic/` is the second protocol family. Adding e.g. Google Gemini means `provider/google/GemanticChatModel.java`, not `provider/gemini/`.
  - OpenAI-compatible vendors extend `OpenAiCompatibleChatModel` (constructor injects `(vendor, defaultBaseUrl, defaultModel)`); Anthropic has its own complete implementation (`AnthropicChatModel` + `AnthropicJsonMapper` + `AnthropicMessagesStreamParser`) — it is **not** a skeleton.
  - System-mode model resolution is governed by `PreferenceResolver`. To influence the model of an LLM call, write into `req.options["model"]` (LlmClient helpers `call(req, vendor, model)` / `stream(req, vendor, model)` already do this) — never mutate the upstream payload directly.
  - Private-Key requests bypass `QuotaService`, the fallback chain, and IP `RateLimitGuard`. Add any "skip on private key" branch via the same `KeySource` enum.
  - Vendor registration fails fast when yaml has neither `spring.ai.providers.<vendor>.default-model` nor a non-null subclass default — keep this check so missing-config is loud at boot, not silent at runtime.
  - The admin global default is mandatory: `ai_global_default.model='__UNSET__'` (sentinel) makes every system-mode request return `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)` until an admin calls `PUT /api/admin/ai/global-default`. Don't change the sentinel string without grepping the resolver.
- **Flyway rules of thumb**:
  - Spring Boot 4.1 has no auto-config — any new SQL file must coexist with `nexus-forge-user/.../flyway/FlywayMigrationRunner.java`. The `spring.flyway.*` keys in `application*.yaml` are still legitimate config (consumed by `FlywayMigrationRunner.@Value`), so do NOT delete them from yaml; just edit the runner if behavior changes.
  - New migrations must be idempotent (`IF NOT EXISTS`, `ON CONFLICT DO NOTHING`, `DO $$ ... $$` for COMMENT blocks) because JPA `ddl-auto` may have pre-created the schema. Already-applied migrations in `flyway_schema_history` keep their checksum — when editing a previously-applied file, expect `FlywayMigrationRunner` to auto-`repair()` on next boot and log a warning.
  - **prod uses `ddl-auto: validate`, dev uses `ddl-auto: none`** — entity field changes MUST land together with a migration in the same commit; otherwise prod boot fails with a column-mismatch error. Local smoke test: `./gradlew :nexus-forge-web:bootRun --args='--spring.profiles.active=prod'` before pushing entity changes.

Frontend:

- Use `@` for `src` imports; tsconfig also defines `@utils/*` and `@components/*`.
- Keep backend calls in `src/api/`; keep transport behavior in `src/utils/http/`; do not bypass the configured Axios client for authenticated calls.
- Auth state belongs in `stores/auth.ts`; route protection belongs in `router/index.ts`.
- Use Zod schemas for PrimeVue forms and typed props/emits in Vue components.
- Styling should use SCSS/CSS tokens from `src/styles/base/_tokens.scss` and theme overrides; avoid raw color literals outside token/theme files.
- PrimeVue components are auto-imported through `unplugin-vue-components`; generated `components.d.ts` should not be hand-edited.
- Prettier conventions: semicolons, single quotes, 2-space indentation, trailing commas where valid, LF endings.

Repository conventions:

- `.gitattributes` enforces LF for JSON/YAML/TS/Vue/SCSS/CSS/Gradle/properties files.
- Never commit `.env`, `.env.*`, `application-local.yaml`, `application-secret.yaml`, `WATCHDOG.yml`, or `docker/MinIO/minio.license`.
- Commit style documented in README: Chinese Conventional Commits, e.g. `feat(auth): 实现 JWT 登录认证与权限校验`.

## Important Files

- `settings.gradle` — module list.
- `build.gradle` — Java 26 toolchain, Spring Boot 4.1.0, shared subproject dependencies, default test platform.
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 9.6.0 via Tencent mirror; CI rewrites to official Gradle distribution.
- `nexus-forge-web/build.gradle` — only module with `bootJar { enabled = true }`; integration-test tag gate lives here; also sets `bootRun { workingDir = rootProject.projectDir }` so `optional:file:.env[.properties]` resolves against the repo root.
- `nexus-forge-web/src/main/resources/application.yaml` — active profile default, `.env` import, multipart limits, Springdoc groups, logging trace pattern, storage defaults, `spring.ai.preference.master-key` (`SPRING_AI_PREFERENCE_MASTER_KEY`).
- `nexus-forge-web/src/main/resources/application-dev.yaml` / `application-prod.yaml` — environment binding for DB, Redis, JWT, storage, and per-vendor `spring.ai.providers.<vendor>.{enabled, api-key, default-model}`.
- `nexus-forge-common/src/main/java/com/nexusforge/base/Result.java` and `enums/ResultCode.java` — public API response contract (AI gateway codes share the same enum).
- `nexus-forge-core/src/main/java/com/nexusforge/error/GlobalExceptionHandler.java` — central HTTP/error mapping.
- `nexus-forge-auth/src/main/java/com/nexusforge/config/SecurityConfig.java` and `filter/JwtAuthenticationFilter.java` — request authentication pipeline.
- `nexus-forge-auth/src/main/java/com/nexusforge/service/AuthService.java` and `util/JwtUtil.java` — token lifecycle invariants.
- `nexus-forge-user/src/main/java/com/nexusforge/user/service/UserService.java` — registration, profile, avatar, password, ban flows.
- `nexus-forge-user/src/main/java/com/nexusforge/flyway/FlywayMigrationRunner.java` and `src/main/resources/db/migration/V*.sql` — Spring Boot 4.1 has no Flyway auto-config; this runner replaces it and the SQL files are the schema's source of truth.
- `nexus-forge-file/src/main/java/com/nexusforge/file/FileClientImpl.java` and `service/FileService.java` — business-facing file adapter and key-generation rules.
- `nexus-forge-file/src/main/java/com/nexusforge/storage/StorageProvider.java` and `storage/S3StorageProvider.java` — storage SPI + the single AWS-SDK-v2 implementation that backs all five vendor configs (`rustfs`/`minio`/`aliyun`/`tencent`/`aws`).
- `nexus-forge-file/src/main/java/com/nexusforge/bootstrap/StorageInitializer.java` — boot-time bucket auto-create, gated by `storage.auto-create-bucket`.
- `nexus-forge-file/src/main/java/com/nexusforge/config/StorageProperties.java` — `storage.*` yaml binding.
- `nexus-forge-ai/src/main/java/com/nexusforge/bootstrap/AiAutoConfiguration.java` — Spring Boot 4.x `@AutoConfiguration` entry; assembles all AI beans in phase: properties → http support → cipher → vendor models → router → quota/RL/usage → LlmClient → controllers. **All AI bean registration goes here, not in `@Configuration` classes.**
- `nexus-forge-ai/src/main/java/com/nexusforge/model/ChatModel.java` — SPI interface; implementations throw `LlmException` only.
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/config/AiVendorRegistry.java` — vendor registry; new vendors are added here (one-line registration) rather than in `LlmClient`.
- `nexus-forge-ai/src/main/java/com/nexusforge/router/ChatModelRouter.java` — fallback chain over `Map<vendor, ChatModel>`; consults `provider/support/CircuitState` to skip tripped vendors.
- `nexus-forge-ai/src/main/java/com/nexusforge/client/LlmClient.java` — facade for `call(req)` / `call(req, vendor, model)` / `stream(req)` / `stream(req, vendor, model)`; fallback chain for system-mode, dedicated private-key path.
- `nexus-forge-ai/src/main/java/com/nexusforge/client/FunctionCallAggregator.java` — aggregates `delta.tool_calls` by `index`, emits a synthesized terminating frame with full `toolCalls[]` when `finishReason="tool_calls"`.
- `nexus-forge-ai/src/main/java/com/nexusforge/client/RateLimitGuard.java` — Caffeine-backed per-user/IP rate limit; bypassed on private-key path; namespaced `ai:rl:` to keep separate from `core/RateLimitAspect`'s `rl:`.
- `nexus-forge-ai/src/main/java/com/nexusforge/service/QuotaService.java` — 24h sliding-window token quota; bypassed on private-key path.
- `nexus-forge-ai/src/main/java/com/nexusforge/service/ConversationService.java` — conversation/message persistence, calls LLM, records usage, updates conversation model on first turn.
- `nexus-forge-ai/src/main/java/com/nexusforge/client/UsageRecorder.java` — Micrometer usage metering; also writes to `ai_message_usage` windowed table.
- `nexus-forge-ai/src/main/java/com/nexusforge/error/LlmErrorMapper.java` — maps `LlmException` to JSON `Result`; `GlobalExceptionHandler` delegates here for `LLM_*` codes.
- `nexus-forge-ai/src/main/java/com/nexusforge/stream/OpenAiStreamParser.java` and `stream/SseFormat.java` — upstream SSE line parser + our wire-format constants.
- `nexus-forge-ai/src/main/java/com/nexusforge/provider/anthropic/AnthropicMessagesStreamParser.java` — Anthropic SSE line parser (separate from OpenAI's because the wire format is different).
- `nexus-forge-common/src/main/java/com/nexusforge/chat/SseEventCodec.java` — reusable SSE frame helper; currently NOT wired into `AiStreamController` (controller writes `data: <json>\n\n` by hand). Use this only if you add a new streaming endpoint.
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/client/ApiKeyCipher.java` — AES-256-GCM encryption for `user_ai_preference.encrypted_api_key`; key derived from `spring.ai.preference.master-key` → `jwt.secret` fallback.
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/service/PreferenceResolver.java` — three-way preference resolution (`SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`); enforces the `__UNSET__` sentinel.
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/provider/VendorChatModelFactory.java` — dynamically constructs private-Key ChatModels, cached by `sha256(apiKey)`.
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/controller/AiPreferenceController.java` and `AiAdminController.java` — user `/api/ai/preference` and admin `/api/admin/ai/global-default` REST endpoints.
- `nexus-forge-ui/src/main.ts` — frontend bootstrap order (Pinia + persisted state → router → Toast/Confirm → PrimeVue(`MyPreset`) → `bootstrapAuth().finally(mount)`).
- `nexus-forge-ui/src/composables/useAuthBoot.ts` — defines `bootstrapAuth()` called from `main.ts` before mount; orchestrates token refresh + auth store hydration.
- `nexus-forge-ui/src/themes/index.ts` — exports `MyPreset` PrimeVue theme used by `main.ts`.
- `nexus-forge-ui/src/stores/auth.ts` — auth/token state and refresh behavior; AES-persisted via `VITE_SECRET_KEY`.
- `nexus-forge-ui/src/utils/http/interceptors.ts` — token injection, refresh retry, error mapping.
- `nexus-forge-ui/src/types/auth.ts` — `TokenBundle` / `TokenSlot` / `LoginRequest` / `RegisterRequest` types; counterpart to backend `dto/TokenBundle` in `nexus-forge-auth`.
- `nexus-forge-ui/vite.config.ts` — `/api` proxy and `@` alias.
- `nexus-forge-ui/package.json` — npm scripts and frontend tool versions.
- `.github/workflows/ci.yml` — authoritative CI commands and known `npm install`/lint behavior.

## Runtime/Tooling Preferences

- Backend runtime: JDK 26, Gradle 9.6 wrapper, Spring Boot 4.1.0.
- Frontend runtime: Node >= 20; CI uses Node 22. Package manager is npm. Do not switch to Bun, pnpm, or yarn unless the repo is deliberately migrated.
- Backend artifact: run/build `nexus-forge-web`; other backend modules are libraries and have `bootJar` disabled.
- Frontend build output: `nexus-forge-ui/dist`.
- Required local services: PostgreSQL, Redis, and one S3-compatible store. Local default storage vendor is RustFS.
- `spring.config.import: optional:file:.env[.properties]` loads root `.env`; production config intentionally has no credential defaults. `bootRun` is configured with `workingDir = rootProject.projectDir` so the resolver walks up from `nexus-forge-web/build/classes` to the repo root; do not move `.env` into `nexus-forge-web/` — that workaround is no longer needed.
- CI backend runs `./gradlew --no-daemon clean build`; CI frontend runs `npm install --no-audit --no-fund`, `npm run lint` with `continue-on-error: true`, then `npm run build`.
- Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`; health at `http://localhost:8080/actuator/health`.
- README recommends `REDIS_HOST=127.0.0.1` on Windows to avoid `localhost` IPv6 resolution issues.
- AI gateway defaults: the dev profile ships with `qwen` vendor enabled and `qwen.default-model=qwen-turbo` (vendor registration fallback). `ai_global_default.model` is seeded as `'__UNSET__'` — the first boot after a fresh DB will refuse every system-mode chat with `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)` until an admin calls `PUT /api/admin/ai/global-default {vendor, model}`. Plan first-login setup accordingly.

## Testing & QA

- Backend tests use JUnit 5, AssertJ, Mockito, Spring Test, and `spring-security-test`.
- Unit test naming: `*Test.java`, package mirrors production code. Existing service tests share `UserServiceTestSupport` and use real `BCryptPasswordEncoder` where password hashing matters.
- Integration test naming: `*IT.java` under `nexus-forge-web/src/test/java/com/nexusforge/flows/`, all tagged `@Tag("integration")` and based on `IntegrationTestBase`.
- Integration tests use Testcontainers for PostgreSQL, Redis, and RustFS. **Implementation note**: `nexus-forge-web/build.gradle` declares only `testcontainers:postgresql` directly, but it pulls in `testcontainers:core` transitively, which exposes `GenericContainer<>` — that's what `IntegrationTestBase.REDIS` (`redis:latest`) and `RUSTFS` (`rustfs/rustfs:latest`) use. We don't pull `testcontainers-redis` or `testcontainers-s3`; Redis/RustFS reuse the generic container API with hand-crafted wait strategies (`DatabaseCleaner` / `RedisCleaner` reset state between tests).
- Default `./gradlew test` and CI `clean build` skip integration tests because `nexus-forge-web/build.gradle` excludes the `integration` tag unless `-Pintegration` is present.
- No Jacoco/coverage tooling is configured.
- No frontend test framework is installed: no `test` script, no Vitest/Jest/Playwright/Cypress dependencies, and no frontend `*.spec.*`/`*.test.*` files observed.
- For backend changes, run the narrowest affected Gradle test task first; add `-Pintegration` only for flows that need real DB/Redis/S3 behavior.
- For frontend changes, run at least `npm run build`; run `npm run lint` when touching TS/Vue/CSS even though CI currently allows lint failures.
