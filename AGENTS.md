# Repository Guidelines

## Project Overview

Nexus Forge is a full-stack application skeleton: Java 26 + Spring Boot 4.1 backend, Vue 3.5 + Vite 8 frontend, PostgreSQL/Redis/S3-compatible storage locally via Docker.

The backend is a Gradle multi-module project split by capability. `nexus-forge-web` is the only bootable module and aggregates shared infrastructure (`core`), common contracts (`common`), auth, user, and file modules. `nexus-forge-ai` and `nexus-forge-visual` exist as planning stubs.

The frontend lives in `nexus-forge-ui/`, uses npm, PrimeVue 5, Pinia, Vue Router, Axios, Zod, Tailwind CSS 4, and SCSS design tokens. Vite proxies `/api` to the backend on `localhost:8080`.

## Architecture & Data Flow

Backend module DAG:

```text
nexus-forge-web
├── nexus-forge-core  ──► nexus-forge-common
├── nexus-forge-auth  ──► nexus-forge-user ──► nexus-forge-common
├── nexus-forge-user  ──► nexus-forge-common
├── nexus-forge-file  ──► nexus-forge-common
├── nexus-forge-ai    ──► nexus-forge-common   # stub
└── nexus-forge-visual──► nexus-forge-common   # stub
```

Request flow:

1. `RequestIdFilter` creates or propagates `X-Trace-Id`, writes MDC `traceId`, and logs method/path/status/cost.
2. Spring Security in `SecurityConfig` runs stateless CORS/JWT auth. `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, Swagger/OpenAPI paths are public; other routes require auth.
3. `JwtAuthenticationFilter` validates access tokens, rejects refresh tokens on business paths, checks Redis blacklist, loads roles via `PermissionLoader`, and sets `UserPrincipal(userId, username)` as the authentication principal.
4. Controllers return `Result<T>`; service errors throw `BusinessException`/`AuthException` with `ResultCode`.
5. `GlobalExceptionHandler` maps domain errors into JSON `Result` responses and special statuses such as idempotency conflict `409`, rate limit `429`, upload too large `413`.

Auth data flow:

- `AuthController` → `AuthenticationManager`/`AuthService` → `JwtUtil` issues `TokenBundle(access, refresh)`.
- Refresh rotates both tokens, stores the active refresh JTI at `auth:refresh:{userId}`, and blacklists consumed refresh tokens.
- Logout blacklists access/refresh JTIs in Redis using remaining TTL. `UserBannedEvent` from the user module is consumed by auth to revoke refresh state.

File data flow:

- Business code uses `FileClient` from `nexus-forge-common`; do not depend directly on storage internals from other modules.
- `FileClientImpl` → `FileService` → `StorageProvider` → `S3StorageProvider` using AWS SDK v2.
- Active vendor comes from `storage.vendor` and `StorageProperties`: `rustfs`, `minio`, `aliyun`, `tencent`, or `aws`.

Frontend flow:

- `src/main.ts` installs Pinia + persisted state, router, Toast/Confirm services, PrimeVue theme `MyPreset`, then calls `bootstrapAuth()` before mount.
- `stores/auth.ts` owns token state, AES-persisted auth data, single-flight refresh, and `ensureFreshAccess()`.
- `utils/http/interceptors.ts` injects Bearer tokens, refreshes once on 401, maps business codes to typed errors, and dispatches `auth:expired` on session loss.
- Router guards in `router/index.ts` redirect protected routes to `auth-view?tab=login&redirect=...`.

## Key Directories

- `nexus-forge-web/` — Spring Boot entry (`NexusForgeApplication`), OpenAPI config, runtime `application*.yaml`, and integration tests.
- `nexus-forge-common/` — shared API contracts: `Result`, `ResultCode`, exceptions, `BaseEntity`, `UserPrincipal`, file DTOs/interfaces, events, cache helpers.
- `nexus-forge-core/` — cross-cutting infrastructure: global exception handling, request logging, `@Idempotent`, `@RateLimit`, auto-configuration.
- `nexus-forge-auth/` — Spring Security, JWT properties/filter/utilities, auth controller/service, JSON 401/403 handlers, token revocation listener.
- `nexus-forge-user/` — `User` entity/repository/service/controller, role cache provider, user DTOs/VOs, Flyway migration resources.
- `nexus-forge-file/` — S3-compatible storage abstraction, file controller/service, `FileClient` adapter, vendor configuration.
- `nexus-forge-ui/src/` — Vue app source: `api/`, `stores/`, `router/`, `utils/http/`, `views/`, `layout/`, `components/`, `styles/`, `themes/`.
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
- API controllers return `Result<T>`. Add new business codes to `ResultCode`; throw `BusinessException`/`AuthException` instead of manually building error responses.
- If a new error code needs a non-400 HTTP status, update `GlobalExceptionHandler.mapStatus`.
- Request DTOs use Jakarta validation annotations and `@Valid`; validation errors are flattened by the global handler.
- Public endpoints should add `@SecurityRequirements` because `OpenApiConfig` applies global bearer auth to Swagger.
- JPA entities extend `BaseEntity` for UTC `createdAt`/`updatedAt`; repositories extend `JpaRepository<T, Long>`.
- Redis keys currently cover idempotency (`idem:*`), JWT blacklist (`auth:blacklist:*`), refresh state (`auth:refresh:*`), and user roles (`auth:roles:*`).
- Cross-cutting annotations live in `core`: use `@Idempotent(key = "<SpEL>")` and `@RateLimit(key = "<SpEL>")` instead of ad hoc duplicate-submit or rate-limit logic.
- Known caveat: `RateLimitAspect` currently calls `tryAcquire` twice; do not copy that pattern when touching rate limiting.

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
- `nexus-forge-web/build.gradle` — only module with `bootJar { enabled = true }`; integration-test tag gate lives here.
- `nexus-forge-web/src/main/resources/application.yaml` — active profile default, `.env` import, multipart limits, Springdoc groups, logging trace pattern, storage defaults.
- `nexus-forge-web/src/main/resources/application-dev.yaml` / `application-prod.yaml` — environment binding for DB, Redis, JWT, storage.
- `nexus-forge-common/src/main/java/com/nexusforge/base/Result.java` and `enums/ResultCode.java` — public API response contract.
- `nexus-forge-core/src/main/java/com/nexusforge/error/GlobalExceptionHandler.java` — central HTTP/error mapping.
- `nexus-forge-auth/src/main/java/com/nexusforge/config/SecurityConfig.java` and `filter/JwtAuthenticationFilter.java` — request authentication pipeline.
- `nexus-forge-auth/src/main/java/com/nexusforge/service/AuthService.java` and `util/JwtUtil.java` — token lifecycle invariants.
- `nexus-forge-user/src/main/java/com/nexusforge/user/service/UserService.java` — registration, profile, avatar, password, ban flows.
- `nexus-forge-file/src/main/java/com/nexusforge/file/FileClientImpl.java` and `service/FileService.java` — business-facing file adapter and key-generation rules.
- `nexus-forge-ui/src/main.ts` — frontend bootstrap order.
- `nexus-forge-ui/src/stores/auth.ts` — auth/token state and refresh behavior.
- `nexus-forge-ui/src/utils/http/interceptors.ts` — token injection, refresh retry, error mapping.
- `nexus-forge-ui/vite.config.ts` — `/api` proxy and `@` alias.
- `nexus-forge-ui/package.json` — npm scripts and frontend tool versions.
- `.github/workflows/ci.yml` — authoritative CI commands and known `npm install`/lint behavior.

## Runtime/Tooling Preferences

- Backend runtime: JDK 26, Gradle 9.6 wrapper, Spring Boot 4.1.0.
- Frontend runtime: Node >= 20; CI uses Node 22. Package manager is npm. Do not switch to Bun, pnpm, or yarn unless the repo is deliberately migrated.
- Backend artifact: run/build `nexus-forge-web`; other backend modules are libraries and have `bootJar` disabled.
- Frontend build output: `nexus-forge-ui/dist`.
- Required local services: PostgreSQL, Redis, and one S3-compatible store. Local default storage vendor is RustFS.
- `spring.config.import: optional:file:.env[.properties]` loads root `.env`; production config intentionally has no credential defaults.
- CI backend runs `./gradlew --no-daemon clean build`; CI frontend runs `npm install --no-audit --no-fund`, `npm run lint` with `continue-on-error: true`, then `npm run build`.
- Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`; health at `http://localhost:8080/actuator/health`.
- README recommends `REDIS_HOST=127.0.0.1` on Windows to avoid `localhost` IPv6 resolution issues.

## Testing & QA

- Backend tests use JUnit 5, AssertJ, Mockito, Spring Test, and `spring-security-test`.
- Unit test naming: `*Test.java`, package mirrors production code. Existing service tests share `UserServiceTestSupport` and use real `BCryptPasswordEncoder` where password hashing matters.
- Integration test naming: `*IT.java` under `nexus-forge-web/src/test/java/com/nexusforge/flows/`, all tagged `@Tag("integration")` and based on `IntegrationTestBase`.
- Integration tests use Testcontainers for PostgreSQL, Redis, and RustFS; `DatabaseCleaner` and `RedisCleaner` reset state between tests.
- Default `./gradlew test` and CI `clean build` skip integration tests because `nexus-forge-web/build.gradle` excludes the `integration` tag unless `-Pintegration` is present.
- No Jacoco/coverage tooling is configured.
- No frontend test framework is installed: no `test` script, no Vitest/Jest/Playwright/Cypress dependencies, and no frontend `*.spec.*`/`*.test.*` files observed.
- For backend changes, run the narrowest affected Gradle test task first; add `-Pintegration` only for flows that need real DB/Redis/S3 behavior.
- For frontend changes, run at least `npm run build`; run `npm run lint` when touching TS/Vue/CSS even though CI currently allows lint failures.
