# 仓库指南

## 项目概览

Nexus Forge 是一个全栈应用骨架：Java 26 + Spring Boot 4.1 后端、Vue 3.5 + Vite 8 前端，本地通过 Docker 运行 PostgreSQL / Redis / S3 兼容存储。

后端是按能力拆分的 Gradle 多模块项目。`nexus-forge-web` 是唯一可启动模块，聚合共享基础设施（`core`）、公共契约（`common`）、auth、user、file 和 ai 模块。`nexus-forge-visual` 是规划占位模块。

AI 模块（`nexus-forge-ai`）是**完整的 LLM 网关**，而非占位实现：提供 `ChatModel` SPI（`com.nexusforge.model.ChatModel`），包含 OpenAI（`OpenAiChatModel`）/ OpenAI 兼容基类（`OpenAiCompatibleChatModel`）/ Qwen / DeepSeek / Ollama / Anthropic 实现、回退链（`ChatModelRouter`）、基于 Micrometer 的用量计量（`UsageRecorder`）、Caffeine 限流（`RateLimitGuard`）、每日 token 配额（`QuotaService`）、会话持久化（`ConversationService`）、**按用户 / 按厂商 / 私有 Key 的偏好解析**（`PreferenceResolver` + `VendorChatModelFactory`）、函数调用（`ToolRegistry` / `ToolExecutor` / `EchoTool`）、SSE 流解析（`OpenAiStreamParser` / `AnthropicMessagesStreamParser`）、`/api/ai/{chat,chat/stream,conversations,preference,usage}` 下的 REST 端点及管理端 `/api/admin/ai/global-default`，并通过 `bootstrap/AiAutoConfiguration` 中的 Spring Boot 4.x `@AutoConfiguration` 装配。

> **包命名注意事项**：AI 模块有两个并存的包根——新代码位于 `com.nexusforge.ai.X.*` 下（`ai/client/`、`ai/config/`、`ai/controller/`、`ai/entity/`、`ai/provider/`、`ai/repository/`、`ai/service/`、`ai/tools/`）；旧核心代码（`LlmClient`、`FunctionCallAggregator`、`RateLimitGuard`、`QuotaService`、`ConversationService`、`UsageRecorder`、`ChatModelRouter`、所有控制器、`AiProperties`、所有 provider、流解析器、错误映射器）位于裸 `com.nexusforge.X.*` 根下。新增 AI 相关代码应放入 `com.nexusforge.ai.X.*`；统一为单一前缀的完整迁移是已跟踪的重构项。

前端位于 `nexus-forge-ui/`，使用 npm、PrimeVue 5（当前锁定 `^5.0.0-rc.1`，**候选发布版——稳定前严格锁定**）、Pinia、Vue Router、Axios、Zod、Tailwind CSS 4 和 SCSS 设计令牌。Vite 将 `/api` 代理到 `localhost:8080` 的后端。

## 架构与数据流

后端模块 DAG：

```text
nexus-forge-web
├── nexus-forge-core  ──► nexus-forge-common
├── nexus-forge-auth  ──► nexus-forge-user ──► nexus-forge-common
├── nexus-forge-user  ──► nexus-forge-common
├── nexus-forge-file  ──► nexus-forge-common
├── nexus-forge-ai    ──► nexus-forge-core, nexus-forge-common   # LLM 网关
└── nexus-forge-visual──► nexus-forge-common                   # 占位模块
```

请求流程：

1. `RequestIdFilter` 创建或透传 `X-Trace-Id`，写入 MDC `traceId`，并记录 method/path/status/cost。
2. `SecurityConfig` 中的 Spring Security 运行无状态 CORS/JWT 认证。`/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、Swagger/OpenAPI 路径是公开的；其他路由需要认证。
3. `JwtAuthenticationFilter` 校验访问令牌、在业务路径上拒绝刷新令牌、检查 Redis 黑名单、通过 `PermissionLoader` 加载角色，并将 `UserPrincipal(userId, username)` 设为认证主体。
4. 控制器返回 `Result<T>`；服务错误抛出带 `ResultCode` 的 `BusinessException`/`AuthException`。AI 网关还为上游 / 配额 / 限流 / 全局默认未配置错误额外抛出 `LlmException`，由同一处理器映射为 JSON `Result` 响应。
5. `GlobalExceptionHandler` 将领域错误映射为 JSON `Result` 响应以及特殊状态码，如幂等冲突 `409`、限流 `429`、上传过大 `413`。

认证数据流：

- `AuthController` → `AuthenticationManager`/`AuthService` → `JwtUtil` 签发 `TokenBundle(access, refresh)`。
- 刷新会轮换两个令牌，将活动刷新 JTI 存于 `auth:refresh:{userId}`，并将已消费的刷新令牌加入黑名单。
- 登出按剩余 TTL 将 access/refresh JTI 加入 Redis 黑名单。user 模块的 `UserBannedEvent` 由 auth 消费以撤销刷新状态。

文件数据流：

- 业务代码使用 `nexus-forge-common` 中的 `FileClient`；其他模块不要直接依赖存储内部实现。
- `FileClientImpl` → `FileService` → `StorageProvider` → 基于 AWS SDK v2 的 `S3StorageProvider`。
- 活动厂商来自 `storage.vendor` 和 `StorageProperties`：`rustfs`、`minio`、`aliyun`、`tencent` 或 `aws`。

AI 网关数据流：

- `AiAutoConfiguration`（`com.nexusforge.bootstrap.AiAutoConfiguration`，注册为 Spring Boot 4.x `@AutoConfiguration`）按确定性阶段顺序装配所有 AI bean：`AiProperties` → `ChatModelHttpSupport` → `ApiKeyCipher` → 厂商 `ChatModel` → `ChatModelRouter`（回退链）→ `QuotaService` / `RateLimitGuard` / `UsageRecorder` → `LlmClient` → 控制器。厂商注册在 `AiVendorRegistry`（`com.nexusforge.ai.config`）中完成——新增厂商意味着在那里注册，而不是在 `LlmClient` 中。
- `AiController` / `AiStreamController` / `AiConversationController` / `AiUsageController` 通过 `PreferenceResolver.resolve(userId, dto.model)` 解析每请求偏好（请求模型 → 用户偏好 → 全局默认 → yaml）。结果为 `Resolved(vendor, model, apiKey, source)`，`source ∈ {SYSTEM, USER_OVERRIDE_SYSTEM_KEY, USER_PRIVATE_KEY}`。此处抛出的领域错误（`LlmException` + 9 个特定 `LLM_*` 码）经 `error/LlmErrorMapper` 映射为 `Result` JSON。
- 系统 Key 路径：`LlmClient.call(req, vendor, model)` 将 `pref.model` 写入 `req.options["model"]`；`OpenAiJsonMapper.toOpenAi` 先读 `options["model"]`，再回退到厂商的 `cfg.defaultModel`（yaml）；构造上游载荷从不读取 `req.model`（该字段只是路由器通道）。
- 私有 Key 路径：`VendorChatModelFactory` 动态构造 OpenAI 兼容的 `ChatModel`，按 `sha256(apiKey)` 缓存。密钥从 `user_ai_preference.encrypted_api_key` 解密（AES-256-GCM，密钥由 `spring.ai.preference.master-key` 派生 → 回退到 `jwt.secret`）。私有 Key 请求绕过平台配额、回退链和 IP 限流。
- `ConversationService.sendMessage` 持久化 user/assistant 消息、调用 LLM、将用量记录到 `ai_message_usage`（窗口化）、在首轮更新会话模型，并通过 `UsageRecorder` 馈送 Micrometer。`UsageService` / `ContextWindowBuilder` 聚合每用户用量并组装会话上下文窗口。
- `ai_global_default` 是单行全局默认，种子为哨兵 `model='__UNSET__'`。在管理员调用 `PUT /api/admin/ai/global-default` 之前，每个系统模式请求都返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`。厂商注册还要求 yaml 中有 `spring.ai.providers.<vendor>.default-model`——缺失会在启动时快速失败。
- 流式响应使用 `StreamingResponseBody`（而非 `SseEmitter`），以规避 Spring 7 + Tomcat 11 的 chunked-transfer-encoding EOF 问题。`AiStreamController.writeChunks` 每请求只做一次 `Flux.subscribe` + `CountDownLatch.await`，防止冷 Flux 双重订阅（那会发出 2 次 LLM HTTP 请求）。工具调用增量由 `FunctionCallAggregator`（按 `index` 键控）聚合，当 `finishReason="tool_calls"` 时发出带完整 `toolCalls[]` 的合成终止帧。线协议格式是扁平的 camelCase `ChatChunk` JSON 帧——**不是** OpenAI `chat.completion.chunk`（无 `choices[]`、无 `data: [DONE]`、无 `object`/`created`/`model`）；自定义错误帧为 `{"error": "..."}`。

Flyway 数据流：

- Spring Boot 4.1 移除了 `spring-boot-flyway` 自动配置模块，且 Flyway 不发布 Spring Boot starter。**`spring.flyway.*` 键仍存在于 `application.yaml`、`application-dev.yaml` 和 `application-prod.yaml` 中，不是死配置**——它们由手写的 `FlywayMigrationRunner`（`nexus-forge-user`，注册为 `@Component`，在 JPA 之前的 `@PostConstruct` 中运行）消费。runner 通过 `@Value` 读取 `spring.flyway.{enabled, locations, baseline-on-migrate, validate-on-migrate, baseline-version}`。遇到 `FlywayValidateException`（编辑已应用的 SQL 文件导致校验和不匹配）时自动运行 `flyway.repair()` 并重试——已应用的迁移通过 SQL 中的 `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... $$` 块保持幂等。
- 所有迁移位于 `nexus-forge-user/src/main/resources/db/migration` 下。最新的两个（`V20260801_001/002`）新增 `ai_global_default`（单行，`id=1 CHECK (id=1)`，哨兵 `model='__UNSET__'`）和 `user_ai_preference`（按用户，AES 加密的 API key 存为 `BYTEA`，外加供 UI 展示的 `api_key_fingerprint VARCHAR(16)`）。
- **⚠️ 生产 schema 耦合风险**：`application-prod.yaml` 设置 `spring.jpa.hibernate.ddl-auto: validate`。任何 JPA 实体字段/列偏离 Flyway 管理的 schema 时，Hibernate 都会拒绝启动。每个实体变更必须与同一提交中的迁移配套；忘记的话生产启动会失败（没有 Hibernate 自动修复）。开发环境用 `ddl-auto: none`（Flyway 是唯一权威），因此 schema 漂移先在生产暴露——合并实体变更前在本地运行 `./gradlew :nexus-forge-web:bootRun --args='--spring.profiles.active=prod'`。

前端流程：

- `src/main.ts` 安装 Pinia + 持久化状态、router、Toast/Confirm 服务、PrimeVue 主题 `MyPreset`，然后在挂载前调用 `bootstrapAuth()`。
- `stores/auth.ts` 持有令牌状态、AES 持久化的认证数据、单飞刷新和 `ensureFreshAccess()`。
- `utils/http/interceptors.ts` 注入 Bearer 令牌、401 时刷新一次、将业务码映射为类型化错误，并在会话丢失时派发 `auth:expired`。
- `router/index.ts` 的路由守卫将受保护路由重定向到 `auth-view?tab=login&redirect=...`。

## 关键目录

- `nexus-forge-web/` — Spring Boot 入口（`NexusForgeApplication`）、OpenAPI 配置、运行时 `application*.yaml` 和集成测试。
- `nexus-forge-common/` — 共享 API 契约：`Result`、`ResultCode`、异常（`BusinessException` / `AuthException` / `LlmException` + `BaseException`）、`BaseEntity`（UTC `createdAt`/`updatedAt`）、`UserPrincipal`、文件 DTO/接口（`FileClient`、`FileMeta`、`FileBizType`、`FileAccess`、`UploadCredential`）、事件（`UserBannedEvent`）、缓存助手（`CachedValueLoader`）、AI DTO（`ChatRequest`/`ChatResponse`/`ChatChunk`/`ChatUsage`/`DeltaToolCall`/`ToolCall`/`ToolDefinition`/`Role`）、注册请求 DTO，以及 SSE 编解码助手 `chat/SseEventCodec`（当前未接入 `AiStreamController`——控制器手写 `data: <json>\n\n`；只有新增流式端点时才用 `SseEventCodec`）。
- `nexus-forge-core/` — 横切基础设施：全局异常处理、请求日志、`@Idempotent`、`@RateLimit`、自动配置、支撑 AI 模块 `RateLimitGuard` 的 Caffeine 限流器。
- `nexus-forge-auth/` — Spring Security：`config/{SecurityConfig,CorsConfig,ClockConfig,JwtProperties}`、`filter/{JwtAuthenticationFilter,JwtQueryTokenFilter}`、`controller/AuthController`（`/api/auth/{login,register,refresh,logout}`）、`service/AuthService`、`util/JwtUtil`、`handler/JsonAuthHandlers`（实现 `AuthenticationEntryPoint` + `AccessDeniedHandler` 的 401/403 JSON 写入器）、`listener/AuthEventListener`（消费 user 模块的 `UserBannedEvent` 以撤销刷新状态）、`security/{LoginUser,UserDetailsServiceImpl,UserLoader,PermissionLoader}`、DTO `dto/{LoginRequest,LogoutRequest,RefreshRequest,TokenBundle}`。
- `nexus-forge-user/` — `User` 实体/仓库/服务/控制器（`/api/users/**`）、用户 DTO/VO（`dto/{ChangePasswordDto,UpdateUserDto}`、`vo/UserVo`）、供其他模块消费的角色与配额提供者（`service/{UserRoleProvider,UserQuotaProviderImpl}`——UserRoleProvider 被 `PermissionLoader` 读取；UserQuotaProviderImpl 被 AI `QuotaService` 读取）。**`src/main/resources/db/migration/` 下的 Flyway 迁移**和 `flyway/FlywayMigrationRunner.java`（Spring Boot 4.1 无自动配置）。
- `nexus-forge-file/` — S3 兼容存储抽象。`controller/FileController`（`/api/files/**`）、`file/FileClientImpl`（面向业务的门面；`FileClient` 接口位于 `nexus-forge-common`）、`service/FileService`、`storage/{StorageProvider (SPI),S3StorageProvider (AWS SDK v2)}`、`bootstrap/StorageInitializer`（启动时自动建桶，由 `storage.auto-create-bucket` 控制）、`config/StorageProperties`（`storage.vendor` ∈ `rustfs|minio|aliyun|tencent|aws`；MinIO/RustFS 共享 `path-style=true` S3 语义）。
- `nexus-forge-ai/` — LLM 网关（见上文「AI 网关数据流」）：
  - `bootstrap/AiAutoConfiguration` — Spring Boot 4.x `@AutoConfiguration` 入口点；按确定性阶段顺序注册所有 AI bean。约定是：bean 加在这里，而不是加在 `@Configuration` 类里。
  - `model/ChatModel` — SPI（`call` / `stream` / `name` / `capabilities`）；实现只抛 `LlmException`，绝不抛原始第三方异常。
  - `provider/openai/` — `OpenAiChatModel`（OpenAI 托管）、`OpenAiCompatibleChatModel`（所有 OpenAI 线协议兼容厂商的基类；构造函数 `(vendor, defaultBaseUrl, defaultModel)`）、`OpenAiJsonMapper`（请求 → OpenAI 线协议序列化器）、`QwenChatModel` / `DeepSeekChatModel` / `OllamaChatModel`（均继承 `OpenAiCompatibleChatModel`）。**所有 OpenAI 兼容厂商都放在 `provider/openai/`，而不是 `provider/<vendor>/`**——按协议家族一个目录，而非按厂商。
  - `provider/anthropic/` — `AnthropicChatModel` + `AnthropicJsonMapper` + `AnthropicMessagesStreamParser`（**Anthropic 是完整 provider，不是骨架**——三个文件都是完整实现）。
  - `provider/support/` — `ChatModelHttpSupport`（WebClient 工厂、公共头/超时）+ `CircuitState`（`ChatModelRouter` 使用的每厂商熔断器状态）。
  - `router/ChatModelRouter` — 基于 `AiVendorRegistry` 的 `Map<vendor, ChatModel>` 回退链，借助 `CircuitState` 跳过已跳闸的熔断器。
  - `client/{LlmClient,FunctionCallAggregator,RateLimitGuard,QuotaService,ConversationService,UsageRecorder,ToolRegistry,ToolExecutor,ToolResult}` — 门面 + 助手。`LlmClient` 有 `call(...)` 和 `stream(...)` 重载，各自含系统模式（回退链 + 配额 + IP 限流）与私有 Key 模式（跳过配额/回退/IP 限流）分支。
  - `controller/{AiController (/api/ai/chat),AiStreamController (/api/ai/chat/stream, SSE),AiConversationController (/api/ai/conversations/**),AiUsageController (/api/ai/usage/**)}` + `ai/controller/{AiPreferenceController (/api/ai/preference),AiAdminController (/api/admin/ai/global-default)}`。
  - `service/{PreferenceResolver,AiPreferenceService}`（在 `ai/service/` 下）+ `service/{ConversationService,QuotaService,UsageService,ContextWindowBuilder}`（在裸 `service/` 下）——resolver 和面向管理的服务用 `ai/` 前缀，业务服务不用。
  - `stream/{OpenAiStreamParser,SseFormat}` — 上游 SSE 行 → `ChatChunk` 解析器；`SseFormat` 定义行 / 事件通道命名（`event: delta|finish|error|done`）。
  - `entity/{AiConversation,AiMessage,AiMessageUsage}`（裸 `entity/`）+ `ai/entity/{AiGlobalDefault,UserAiPreference}`（在 `ai/` 下）。
  - `repository/{AiConversationRepository,AiMessageRepository,AiMessageUsageRepository}`（裸）+ `ai/repository/{AiGlobalDefaultRepository,UserAiPreferenceRepository}`（在 `ai/` 下）。
  - `tools/EchoTool` — 注册到 `ToolRegistry` 的示例工具。
  - `error/{LlmErrorMapper,StreamCancelledException,StreamTimeoutException,StreamUpstreamException}` — LlmException 映射器 + 流专用标记。
  - `ai/client/ApiKeyCipher` — AES-256-GCM 助手，密钥由 `spring.ai.preference.master-key` 派生 → 回退 `jwt.secret`。
  - `ai/config/AiVendorRegistry` — 厂商注册；构造函数注入使新增厂商成为一行改动。
  - 所有控制器都有 `controller/dto/{ChatRequestDto,SendMessageDto,CreateConversationDto,UpdateTitleDto,PinConversationDto}` + `controller/vo/{ConversationVo,ConversationDetailVo,MessageVo,UsageVo,UsageSummaryVo}`；admin/preference 控制器有 `ai/controller/dto/{PreferenceVo,UpdatePreferenceDto,UpdateGlobalDefaultDto}`。
- `nexus-forge-ui/src/` — Vue 应用源码：`api/{auth,user}.ts`、`composables/useAuthBoot.ts`（定义挂载前由 `main.ts` 调用的 `bootstrapAuth()`）、`stores/{auth,layout}.ts`、`router/{index,routes}.ts`、`utils/http/{interceptors,errors}.ts` + `utils/error.ts`、`views/`、`layout/`、`components/`（由 `unplugin-vue-components` 配合 `PrimeVueResolver` 自动导入）、`styles/{base,components}/...scss` + `styles/main.scss`（入口）、`themes/index.ts`（导出 `MyPreset` PrimeVue 主题）、`types/{auth.ts (TokenBundle/TokenSlot),models/...}`。
- `docker/{Postgres,Redis,MinIO,RustFS}/` — 每服务 Compose 栈和 env 示例。MinIO 和 RustFS 都绑定 `9000/9001`；不重新映射就不要同时运行。
- `.github/workflows/ci.yml` — 后端 Gradle 构建/测试与前端 npm lint/build 的 CI 定义。
- `docs/ROADMAP.md` — 待办/状态参考，不是实现的事实来源。

## 开发命令

后端：

```bash
./gradlew :nexus-forge-web:bootRun          # 运行后端，默认 dev profile，端口 8080
./gradlew test                              # 单元测试；web 集成测试被排除
./gradlew :nexus-forge-web:test -Pintegration
./gradlew --no-daemon clean build           # CI 后端命令
```

Windows 上 `gradlew.bat` 可用于相同的 Gradle 任务。

前端：

```bash
cd nexus-forge-ui
npm install                                # CI 用 npm install 而非 npm ci（lockfile 漂移）
npm run dev                                # Vite 开发服务器，默认端口 5173
npm run build                              # vue-tsc -b && vite build
npm run lint                               # ESLint flat config
npm run format                             # Prettier 作用于 src/**/*.{vue,ts,js,css,scss,json}
```

本地服务：

```bash
cd docker/Postgres && cp .env.example .env && docker compose up -d
cd ../Redis       && cp .env.example .env && docker compose up -d
cd ../RustFS      && cp .env.example .env && docker compose up -d
```

启动后端前，从 `.env.example` 创建根 `.env`，至少设置 `DB_PASSWORD` 和 32+ 字节的 `JWT_SECRET`（`openssl rand -base64 48`）。

## 代码约定与常见模式

后端：

- 包根是 `com.nexusforge`。新模块保持在该根下，web 应用的默认组件扫描才能发现它们。
- 保持依赖方向干净。共享 DTO、枚举、异常、事件和跨模块接口属于 `nexus-forge-common`。
- 使用分层 Spring 代码：`controller` → `service` → `repository`/适配器。使用构造器注入，通常经 Lombok `@RequiredArgsConstructor`；避免字段注入。
- API 控制器返回 `Result<T>`。新业务码加到 `ResultCode`；抛出 `BusinessException`/`AuthException`/`LlmException` 而非手工构建错误响应。AI 网关错误（`LLM_CONFIG_MISSING`、`LLM_MODEL_NOT_FOUND`、`LLM_PROVIDER_ERROR`、`LLM_UPSTREAM_TIMEOUT`、`LLM_RATE_LIMITED`、`LLM_QUOTA_EXCEEDED`、`LLM_ALL_VENDORS_FAILED`、`LLM_CIRCUIT_OPEN`、`LLM_GLOBAL_DEFAULT_NOT_CONFIGURED`）位于同一个 `ResultCode` 枚举中。
- 新错误码需要非 400 HTTP 状态时，更新 `GlobalExceptionHandler.mapStatus`。
- 请求 DTO 使用 Jakarta 校验注解和 `@Valid`；校验错误由全局处理器扁平化。
- 公开端点应加 `@SecurityRequirements`，因为 `OpenApiConfig` 对 Swagger 应用全局 bearer 认证。
- JPA 实体继承 `BaseEntity` 获得 UTC `createdAt`/`updatedAt`；仓库继承 `JpaRepository<T, Long>`。
- Redis 键当前覆盖：幂等（`idem:` + sha256）、JWT 黑名单（`auth:blacklist:`）、刷新状态（`auth:refresh:`）、用户角色（`auth:roles:`）和 AI 限流计数器（`RateLimitGuard` 中的 `ai:rl:`，按 `user:{id}` 或 `ip:{addr}` 键控，桶信息存为 hash——与 `core/RateLimitAspect` 的 `rl:` 前缀分属不同命名空间）。
- 横切注解位于 `core`：用 `@Idempotent(key = "<SpEL>")` 和 `@RateLimit(key = "<SpEL>")` 代替临时重复提交或限流逻辑。两个切面在各自 `@Around` 中恰好调用一次 `tryAcquire`——保持这一点。
- **AI 网关经验法则**：
  - 新厂商放在 `nexus-forge-ai/.../provider/<protocol>/`（按协议家族一个目录，而非按厂商）。当前：`provider/openai/` 存放 `OpenAiChatModel`（OpenAI 托管）以及 `OpenAiCompatibleChatModel` 及其全部子类（`QwenChatModel`、`DeepSeekChatModel`、`OllamaChatModel`）；`provider/anthropic/` 是第二个协议家族。例如添加 Google Gemini 意味着 `provider/google/GemanticChatModel.java`，而不是 `provider/gemini/`。
  - OpenAI 兼容厂商继承 `OpenAiCompatibleChatModel`（构造函数注入 `(vendor, defaultBaseUrl, defaultModel)`）；Anthropic 有自己完整的实现（`AnthropicChatModel` + `AnthropicJsonMapper` + `AnthropicMessagesStreamParser`）——它不是骨架。
  - 系统模式的模型解析由 `PreferenceResolver` 管理。要影响 LLM 调用的模型，写入 `req.options["model"]`（LlmClient 助手 `call(req, vendor, model)` / `stream(req, vendor, model)` 已经这样做）——绝不直接改动上游载荷。
  - 私有 Key 请求绕过 `QuotaService`、回退链和 IP `RateLimitGuard`。通过同一个 `KeySource` 枚举添加任何「私有 Key 跳过」分支。
  - yaml 中既无 `spring.ai.providers.<vendor>.default-model` 又无非空子类默认值时，厂商注册快速失败——保留此检查，让缺失配置在启动时响亮暴露，而非运行时静默。
  - 管理员全局默认是强制的：`ai_global_default.model='__UNSET__'`（哨兵）使每个系统模式请求返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`，直到管理员调用 `PUT /api/admin/ai/global-default`。不 grep resolver 就不要改哨兵字符串。
- **Flyway 经验法则**：
  - Spring Boot 4.1 没有自动配置——任何新 SQL 文件必须与 `nexus-forge-user/.../flyway/FlywayMigrationRunner.java` 共存。`application*.yaml` 里的 `spring.flyway.*` 键仍是合法配置（被 `FlywayMigrationRunner.@Value` 消费），所以不要从 yaml 删除它们；行为变化直接改 runner。
  - 新迁移必须幂等（`IF NOT EXISTS`、`ON CONFLICT DO NOTHING`、COMMENT 块用 `DO $$ ... $$`），因为 JPA `ddl-auto` 可能已预先建好 schema。`flyway_schema_history` 中已应用的迁移保留校验和——编辑已应用过的文件时，预期 `FlywayMigrationRunner` 下次启动自动 `repair()` 并记录警告。
  - **生产用 `ddl-auto: validate`，开发用 `ddl-auto: none`**——实体字段变更必须与同一提交中的迁移一起落地；否则生产启动会因列不匹配失败。本地冒烟测试：推送实体变更前运行 `./gradlew :nexus-forge-web:bootRun --args='--spring.profiles.active=prod'`。

前端：

- `src` 导入用 `@`；tsconfig 还定义了 `@utils/*` 和 `@components/*`。
- 后端调用放在 `src/api/`；传输行为放在 `src/utils/http/`；认证调用不要绕过配置好的 Axios 客户端。
- 认证状态属于 `stores/auth.ts`；路由保护属于 `router/index.ts`。
- PrimeVue 表单用 Zod schema，Vue 组件用类型化 props/emits。
- 样式使用 `src/styles/base/_tokens.scss` 的 SCSS/CSS 令牌和主题覆盖；避免在令牌/主题文件之外使用原始颜色字面量。
- PrimeVue 组件经 `unplugin-vue-components` 自动导入；生成的 `components.d.ts` 不要手工编辑。
- Prettier 约定：分号、单引号、2 空格缩进、有效处尾随逗号、LF 行尾。

仓库约定：

- `.gitattributes` 对 JSON/YAML/TS/Vue/SCSS/CSS/Gradle/properties 文件强制 LF。
- 永不提交 `.env`、`.env.*`、`application-local.yaml`、`application-secret.yaml`、`WATCHDOG.yml` 或 `docker/MinIO/minio.license`。
- 提交风格记录在 README：中文 Conventional Commits，例如 `feat(auth): 实现 JWT 登录认证与权限校验`。

## 重要文件

- `settings.gradle` — 模块列表。
- `build.gradle` — Java 26 toolchain、Spring Boot 4.1.0、共享子项目依赖、默认测试平台。
- `gradle/wrapper/gradle-wrapper.properties` — 经腾讯镜像的 Gradle 9.6.0；CI 重写为官方 Gradle 发行版。
- `nexus-forge-web/build.gradle` — 唯一启用 `bootJar { enabled = true }` 的模块；集成测试标签门控在此；还设置 `bootRun { workingDir = rootProject.projectDir }`，使 `optional:file:.env[.properties]` 相对仓库根解析。
- `nexus-forge-web/src/main/resources/application.yaml` — 默认激活 profile、`.env` 导入、multipart 限制、Springdoc 分组、日志 trace 模式、存储默认值、`spring.ai.preference.master-key`（`SPRING_AI_PREFERENCE_MASTER_KEY`）。
- `nexus-forge-web/src/main/resources/application-dev.yaml` / `application-prod.yaml` — DB、Redis、JWT、存储及每厂商 `spring.ai.providers.<vendor>.{enabled, api-key, default-model}` 的环境绑定。
- `nexus-forge-common/src/main/java/com/nexusforge/base/Result.java` 和 `enums/ResultCode.java` — 公开 API 响应契约（AI 网关码共享同一枚举）。
- `nexus-forge-core/src/main/java/com/nexusforge/error/GlobalExceptionHandler.java` — 中央 HTTP/错误映射。
- `nexus-forge-auth/src/main/java/com/nexusforge/config/SecurityConfig.java` 和 `filter/JwtAuthenticationFilter.java` — 请求认证流水线。
- `nexus-forge-auth/src/main/java/com/nexusforge/service/AuthService.java` 和 `util/JwtUtil.java` — 令牌生命周期不变量。
- `nexus-forge-user/src/main/java/com/nexusforge/user/service/UserService.java` — 注册、资料、头像、密码、封禁流程。
- `nexus-forge-user/src/main/java/com/nexusforge/flyway/FlywayMigrationRunner.java` 和 `src/main/resources/db/migration/V*.sql` — Spring Boot 4.1 没有 Flyway 自动配置；该 runner 取代之，SQL 文件是 schema 的事实来源。
- `nexus-forge-file/src/main/java/com/nexusforge/file/FileClientImpl.java` 和 `service/FileService.java` — 面向业务的文件适配器和键生成规则。
- `nexus-forge-file/src/main/java/com/nexusforge/storage/StorageProvider.java` 和 `storage/S3StorageProvider.java` — 存储 SPI + 支撑全部五种厂商配置（`rustfs`/`minio`/`aliyun`/`tencent`/`aws`）的单一 AWS-SDK-v2 实现。
- `nexus-forge-file/src/main/java/com/nexusforge/bootstrap/StorageInitializer.java` — 启动时自动建桶，由 `storage.auto-create-bucket` 控制。
- `nexus-forge-file/src/main/java/com/nexusforge/config/StorageProperties.java` — `storage.*` yaml 绑定。
- `nexus-forge-ai/src/main/java/com/nexusforge/bootstrap/AiAutoConfiguration.java` — Spring Boot 4.x `@AutoConfiguration` 入口；按阶段装配所有 AI bean：properties → http support → cipher → 厂商模型 → router → quota/RL/usage → LlmClient → 控制器。**所有 AI bean 注册都在这里，而不是在 `@Configuration` 类中。**
- `nexus-forge-ai/src/main/java/com/nexusforge/model/ChatModel.java` — SPI 接口；实现只抛 `LlmException`。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/config/AiVendorRegistry.java` — 厂商注册表；新厂商在此添加（一行注册），而非在 `LlmClient` 中。
- `nexus-forge-ai/src/main/java/com/nexusforge/router/ChatModelRouter.java` — 基于 `Map<vendor, ChatModel>` 的回退链；咨询 `provider/support/CircuitState` 以跳过已跳闸的厂商。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/LlmClient.java` — `call(req)` / `call(req, vendor, model)` / `stream(req)` / `stream(req, vendor, model)` 的门面；系统模式走回退链，另有专用私有 Key 路径。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/FunctionCallAggregator.java` — 按 `index` 聚合 `delta.tool_calls`，`finishReason="tool_calls"` 时发出带完整 `toolCalls[]` 的合成终止帧。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/RateLimitGuard.java` — Caffeine 支撑的按用户/IP 限流；私有 Key 路径绕过；命名空间 `ai:rl:` 以区别于 `core/RateLimitAspect` 的 `rl:`。
- `nexus-forge-ai/src/main/java/com/nexusforge/service/QuotaService.java` — 24 小时滑动窗口 token 配额；私有 Key 路径绕过。
- `nexus-forge-ai/src/main/java/com/nexusforge/service/ConversationService.java` — 会话/消息持久化、调用 LLM、记录用量、首轮更新会话模型。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/UsageRecorder.java` — Micrometer 用量计量；同时写入 `ai_message_usage` 窗口化表。
- `nexus-forge-ai/src/main/java/com/nexusforge/error/LlmErrorMapper.java` — 将 `LlmException` 映射为 JSON `Result`；`GlobalExceptionHandler` 对 `LLM_*` 码委托到此。
- `nexus-forge-ai/src/main/java/com/nexusforge/stream/OpenAiStreamParser.java` 和 `stream/SseFormat.java` — 上游 SSE 行解析器 + 线协议格式常量。
- `nexus-forge-ai/src/main/java/com/nexusforge/provider/anthropic/AnthropicMessagesStreamParser.java` — Anthropic SSE 行解析器（与 OpenAI 分离，因为线协议格式不同）。
- `nexus-forge-common/src/main/java/com/nexusforge/chat/SseEventCodec.java` — 可复用 SSE 帧助手；当前未接入 `AiStreamController`（控制器手写 `data: <json>\n\n`）。仅新增流式端点时使用。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/client/ApiKeyCipher.java` — 对 `user_ai_preference.encrypted_api_key` 的 AES-256-GCM 加密；密钥由 `spring.ai.preference.master-key` 派生 → 回退 `jwt.secret`。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/service/PreferenceResolver.java` — 三路偏好解析（`SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`）；强制 `__UNSET__` 哨兵。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/provider/VendorChatModelFactory.java` — 动态构造私有 Key ChatModel，按 `sha256(apiKey)` 缓存。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/controller/AiPreferenceController.java` 和 `AiAdminController.java` — 用户 `/api/ai/preference` 和管理员 `/api/admin/ai/global-default` REST 端点。
- `nexus-forge-ui/src/main.ts` — 前端引导顺序（Pinia + 持久化状态 → router → Toast/Confirm → PrimeVue(`MyPreset`) → `bootstrapAuth().finally(mount)`）。
- `nexus-forge-ui/src/composables/useAuthBoot.ts` — 定义挂载前由 `main.ts` 调用的 `bootstrapAuth()`；编排令牌刷新 + 认证 store 水合。
- `nexus-forge-ui/src/themes/index.ts` — 导出 `main.ts` 使用的 `MyPreset` PrimeVue 主题。
- `nexus-forge-ui/src/stores/auth.ts` — 认证/令牌状态与刷新行为；经 `VITE_SECRET_KEY` AES 持久化。
- `nexus-forge-ui/src/utils/http/interceptors.ts` — 令牌注入、刷新重试、错误映射。
- `nexus-forge-ui/src/types/auth.ts` — `TokenBundle` / `TokenSlot` / `LoginRequest` / `RegisterRequest` 类型；对应 `nexus-forge-auth` 后端 `dto/TokenBundle`。
- `nexus-forge-ui/vite.config.ts` — `/api` 代理和 `@` 别名。
- `nexus-forge-ui/package.json` — npm 脚本和前端工具版本。
- `.github/workflows/ci.yml` — 权威 CI 命令和已知的 `npm install`/lint 行为。

## 运行时/工具偏好

- 后端运行时：JDK 26、Gradle 9.6 wrapper、Spring Boot 4.1.0。
- 前端运行时：Node >= 20；CI 使用 Node 22。包管理器是 npm。除非仓库刻意迁移，否则不要切换到 Bun、pnpm 或 yarn。
- 后端产物：运行/构建 `nexus-forge-web`；其他后端模块是库，`bootJar` 已禁用。
- 前端构建输出：`nexus-forge-ui/dist`。
- 必需本地服务：PostgreSQL、Redis 和一个 S3 兼容存储。本地默认存储厂商是 RustFS。
- `spring.config.import: optional:file:.env[.properties]` 加载根 `.env`；生产配置故意不设凭据默认值。`bootRun` 配置了 `workingDir = rootProject.projectDir`，因此解析器从 `nexus-forge-web/build/classes` 向上走到仓库根；不要将 `.env` 移入 `nexus-forge-web/`——该变通方案已不再需要。
- CI 后端运行 `./gradlew --no-daemon clean build`；CI 前端运行 `npm install --no-audit --no-fund`、`npm run lint`（`continue-on-error: true`），然后 `npm run build`。
- Swagger UI 位于 `http://localhost:8080/swagger-ui/index.html`；健康检查位于 `http://localhost:8080/actuator/health`。
- README 建议 Windows 上使用 `REDIS_HOST=127.0.0.1` 以避免 `localhost` IPv6 解析问题。
- AI 网关默认值：dev profile 启用 `qwen` 厂商且 `qwen.default-model=qwen-turbo`（厂商注册回退）。`ai_global_default.model` 种子为 `'__UNSET__'`——全新 DB 首次启动后，每个系统模式聊天都会返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`，直到管理员调用 `PUT /api/admin/ai/global-default {vendor, model}`。相应规划首次登录设置。

## 测试与 QA

- 后端测试使用 JUnit 5、AssertJ、Mockito、Spring Test 和 `spring-security-test`。
- 单元测试命名：`*Test.java`，包镜像生产代码。现有服务测试共享 `UserServiceTestSupport`，在密码哈希重要处使用真实 `BCryptPasswordEncoder`。
- 集成测试命名：`*IT.java`，位于 `nexus-forge-web/src/test/java/com/nexusforge/flows/` 下，全部标记 `@Tag("integration")` 并基于 `IntegrationTestBase`。
- 集成测试对 PostgreSQL、Redis 和 RustFS 使用 Testcontainers。**实现说明**：`nexus-forge-web/build.gradle` 只直接声明 `testcontainers:postgresql`，但它传递引入 `testcontainers:core`，暴露 `GenericContainer<>`——`IntegrationTestBase.REDIS`（`redis:latest`）和 `RUSTFS`（`rustfs/rustfs:latest`）用的正是它。我们不引入 `testcontainers-redis` 或 `testcontainers-s3`；Redis/RustFS 复用通用容器 API，配合手工编写的等待策略（`DatabaseCleaner` / `RedisCleaner` 在测试间重置状态）。
- 默认 `./gradlew test` 和 CI `clean build` 跳过集成测试，因为 `nexus-forge-web/build.gradle` 排除 `integration` 标签，除非存在 `-Pintegration`。
- 未配置 Jacoco/覆盖率工具。
- 未安装前端测试框架：没有 `test` 脚本、没有 Vitest/Jest/Playwright/Cypress 依赖，也未观察到前端 `*.spec.*`/`*.test.*` 文件。
- 后端变更先运行受影响面最窄的 Gradle 测试任务；只有需要真实 DB/Redis/S3 行为的流程才加 `-Pintegration`。
- 前端变更至少运行 `npm run build`；改动 TS/Vue/CSS 时运行 `npm run lint`，尽管 CI 目前允许 lint 失败。
