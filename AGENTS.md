# 仓库指南

## 项目概览

Nexus Forge 是一个全栈应用骨架：Java 26 + Spring Boot 4.1 后端、Vue 3.5 + Vite 8 前端，本地通过 Docker 运行 PostgreSQL / Redis / S3 兼容存储。

后端是按能力拆分的 Gradle 多模块项目。`nexus-forge-web` 是唯一可启动模块，聚合共享基础设施（`core`）、公共契约（`common`）、auth、user、file 和 ai 模块。`nexus-forge-visual` 是规划占位模块。

AI 模块（`nexus-forge-ai`）是**完整的 LLM 网关**，跑在 Spring AI 2.0 之上。spring-ai-full-migration 把所有 vendor 切到 Spring AI 官方 starter（`spring-ai-starter-model-openai/anthropic/ollama` — DeepSeek 之前有独立 starter,Phase X 移除,DeepSeek API 走 OpenAI 协议家族复用 openai starter,ChatModelRouter 通过 aliasing 把 "deepseek" vendor 路由到 `openAiChatModel` bean）,自实现的 ChatModel / 流解析器 / JsonMapper / SSE 格式 helper / Tool SPI 全部下线。

当前能力：
- **3 个官方 starter 提供 ChatModel bean**（`OpenAiChatModel` / `AnthropicChatModel` / `OllamaChatModel`）,由 `AiAutoConfiguration.chatModelRouter(Map<String, ChatModel>)` 收集并按 bean 名归一化为小写 vendor 名（`openAiChatModel → openai` 等）。yaml 的 `providers.deepseek.*` 由 `ProviderPropertiesBridge` 桥到 `spring.ai.openai.*`,`ChatModelRouter` aliasing 把 "deepseek" vendor 路由到 `openAiChatModel` bean — 业务面仍用 "deepseek" 字符串,无感
- **回退链**（`ChatModelRouter.resolveWithFallback`）按 `spring.ai.fallback-chain` 顺序跳下一跳
- **Micrometer 用量计量**（`UsageRecorder`，4 个 counter 标 `model` + `source` 标签）
- **Caffeine 限流**（`RateLimitGuard`，秒级防突发）和**每日 token 配额**（`QuotaService`，24h 滑窗）
- **会话持久化**（`ConversationService` + `ai_message_usage` 实体）
- **三态偏好解析**（`PreferenceResolver` + `VendorChatModelFactory`，`SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`）
- **Tool 回路**（`@Tool` 注解 + `MethodToolCallbackProvider` + `DefaultToolCallingManager`，max iterations 由 `AiProperties.maxToolTurns` 兜底）
- REST 端点：`/api/ai/{chat,chat/stream,conversations,preference,usage}` + 管理端 `/api/admin/ai/global-default`
- 装配入口：`bootstrap/AiAutoConfiguration`（Spring Boot 4.x `@AutoConfiguration`）

> **包命名注意**：AI 模块当前以 `com.nexusforge.*`（裸包根）为主，admin/preference 域的部分类在 `com.nexusforge.ai.*` 子包（`ai/config/`、`ai/controller/dto/`、`ai/service/`、`ai/client/` 等），跟裸根文件混存。spring-ai-full-migration 之前曾经有"老核心 vs 新代码"的更严格划分；迁移完成后该界限已软化，新代码按"业务域就近"原则放在 `com.nexusforge.ai.*`，通用/共享类继续放 `com.nexusforge.*`。

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

- `AiAutoConfiguration`（`com.nexusforge.bootstrap.AiAutoConfiguration`，Spring Boot 4.x `@AutoConfiguration`）装配所有 AI bean：`ApiKeyCipher`（私 Key AES）→ `AiVendorRegistry`（协议层成员）→ `MethodToolCallbackProvider`（`@Tool` 扫描）→ `ChatModelRouter`（回退链 + vendor 名归一化）→ `DefaultToolCallingManager`（tool 回路，`@Bean` 隐式由 LlmClient 持有）→ `LlmClient` → 5 个控制器。
- `AiController` / `AiStreamController` / `AiConversationController` / `AiUsageController` 通过 `PreferenceResolver.resolve(userId, dto.model)` 解析每请求偏好（请求模型 → 用户偏好 → 全局默认 → yaml）。结果 `Resolved(vendor, model, apiKey, source)` 中 `source ∈ {SYSTEM, USER_OVERRIDE_SYSTEM_KEY, USER_PRIVATE_KEY}`。`LlmException` + 9 个 `LLM_*` 码经 `error/LlmErrorMapper` 映射为 `Result` JSON。
- 系统 Key 路径：`LlmClient.call(prompt, vendor, model)` 把 model 写入 `prompt.getOptions()`（Spring AI 强类型 `ChatOptions`，通过 `mutate()` 重建）；`LlmClient.callWithToolLoop` 自动注入 `toolCallbacks` 到 prompt options，然后调 Spring AI `ChatModel.call(prompt)`。熔断查询 `isPrimaryVendorOpen` 暂退化为恒 false（Phase 5 计划用 Spring AI retry / Resilience4j 重做）。
- 私有 Key 路径：`VendorChatModelFactory.resolveOrCreate(vendor, baseUrl, apiKey)` 用 `OpenAiChatModel.builder().options(...).apiKey(...).baseUrl(...).build()` 动态构造 `OpenAiChatModel`（OpenAI 协议家族）/ 占位 throw 对 Anthropic（暂不支持私 Key），按 `sha256(apiKey)` 缓存。密钥从 `user_ai_preference.encrypted_api_key` 解密（AES-256-GCM，密钥由 `spring.ai.preference.master-key` 派生 → 回退到 `jwt.secret`）。私有 Key 路径 yaml 缺 `base-url` / `default-model` 时 `LLM_CONFIG_MISSING` 直接 fail-fast，不再静默回退到 Java 硬编码。
- `ConversationService.sendMessage` 持久化 user/assistant 消息、调用 LLM（tool 回路已由 LlmClient 内部跑完，service 这层不感知）、将 Spring AI `Usage` 提取记录到 `ai_message_usage`（窗口化）、在首轮更新会话模型，并通过 `UsageRecorder` 馈送 Micrometer。`ContextWindowBuilder` 返 `List<Message>`（Spring AI 多态）给 `LlmClient`。
- `ai_global_default` 是单行全局默认，种子为哨兵 `model='__UNSET__'`。在管理员调用 `PUT /api/admin/ai/global-default` 之前，每个系统模式请求都返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`。
- 流式响应使用 `StreamingResponseBody`（规避 Spring 7 + Tomcat 11 chunked-transfer-encoding EOF 问题）。`AiStreamController.writeChunks` 每请求只做一次 `Flux.subscribe` + `CountDownLatch.await`，防止 cold-Flux 双重订阅。**SSE wire 格式**：每帧是 Spring AI `ChatResponse` 的 Jackson 序列化结果（`data: <json>\n\n`），跟 OpenAI Chat Completions 流式 `chat.completion.chunk` 字段路径一致；错误帧 `{"error": "..."}`。流结束靠 socket 关闭（无 `data: [DONE]`）。

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
- `nexus-forge-common/` — 共享 API 契约：`Result`、`ResultCode`、异常（`BusinessException` / `AuthException` / `LlmException` + `BaseException`）、`BaseEntity`（UTC `createdAt`/`updatedAt`）、`UserPrincipal`、文件 DTO/接口（`FileClient`、`FileMeta`、`FileBizType`、`FileAccess`、`UploadCredential`）、事件（`UserBannedEvent`）、缓存助手（`CachedValueLoader`）、注册请求 DTO。spring-ai-full-migration 之前还包含 9 个 `com.nexusforge.ai.*` 旧 chat DTO（`ChatMessage` / `ChatRequest` / `ChatResponse` / `ChatChunk` / `ChatUsage` / `DeltaToolCall` / `ToolCall` / `ToolDefinition` / `Role`），已全部删，业务面直接用 Spring AI 类型。
- `nexus-forge-core/` — 横切基础设施：全局异常处理、请求日志、`@Idempotent`、`@RateLimit`、自动配置、支撑 AI 模块 `RateLimitGuard` 的 Caffeine 限流器。
- `nexus-forge-auth/` — Spring Security：`config/{SecurityConfig,CorsConfig,ClockConfig,JwtProperties}`、`filter/{JwtAuthenticationFilter,JwtQueryTokenFilter}`、`controller/AuthController`（`/api/auth/{login,register,refresh,logout}`）、`service/AuthService`、`util/JwtUtil`、`handler/JsonAuthHandlers`（实现 `AuthenticationEntryPoint` + `AccessDeniedHandler` 的 401/403 JSON 写入器）、`listener/AuthEventListener`（消费 user 模块的 `UserBannedEvent` 以撤销刷新状态）、`security/{LoginUser,UserDetailsServiceImpl,UserLoader,PermissionLoader}`、DTO `dto/{LoginRequest,LogoutRequest,RefreshRequest,TokenBundle}`。
- `nexus-forge-user/` — `User` 实体/仓库/服务/控制器（`/api/users/**`）、用户 DTO/VO（`dto/{ChangePasswordDto,UpdateUserDto}`、`vo/UserVo`）、供其他模块消费的角色与配额提供者（`service/{UserRoleProvider,UserQuotaProviderImpl}`——UserRoleProvider 被 `PermissionLoader` 读取；UserQuotaProviderImpl 被 AI `QuotaService` 读取）。**`src/main/resources/db/migration/` 下的 Flyway 迁移**和 `flyway/FlywayMigrationRunner.java`（Spring Boot 4.1 无自动配置）。
- `nexus-forge-file/` — S3 兼容存储抽象。`controller/FileController`（`/api/files/**`）、`file/FileClientImpl`（面向业务的门面；`FileClient` 接口位于 `nexus-forge-common`）、`service/FileService`、`storage/{StorageProvider (SPI),S3StorageProvider (AWS SDK v2)}`、`bootstrap/StorageInitializer`（启动时自动建桶，由 `storage.auto-create-bucket` 控制）、`config/StorageProperties`（`storage.vendor` ∈ `rustfs|minio|aliyun|tencent|aws`；MinIO/RustFS 共享 `path-style=true` S3 语义）。
- `nexus-forge-ai/` — LLM 网关（Spring AI 2.0，spring-ai-full-migration 完成）：
  - `bootstrap/AiAutoConfiguration` — Spring Boot 4.x `@AutoConfiguration` 入口点。装配 `ApiKeyCipher` / `MethodToolCallbackProvider`（扫 `EchoTool` 的 `@Tool` 方法）/ `ChatModelRouter`（`Map<String, ChatModel>` 注入，bean 名归一化为小写 vendor 名）/ `LlmClient` / 5 个控制器 / 启动日志。
  - `client/LlmClient` — 系统模式（4 个 `call/stream` 重载：vendor+model / 自动解析 / PreferenceResolver）/ 私 Key 模式（2 个重载：用调用方构造的 `ChatModel`）。`callWithToolLoop(prompt, chatModel)` 内部跑 Spring AI `DefaultToolCallingManager` 的 tool 回路。
  - `client/{RateLimitGuard,UsageRecorder,ConversationService,QuotaService,ContextWindowBuilder}` — 限流 / Micrometer / 会话 / 配额 / 上下文窗口。
  - `ai/provider/VendorChatModelFactory` — 私 Key 模式动态构造 `OpenAiChatModel` 并按 `sha256(apiKey)` 缓存。
  - `ai/tools/EchoTool` — `@Tool` 注解的最小示例工具（`echo(input: String)`）。业务侧加工具只需在 `@Component` 类的 `@Tool` 方法上 + `AiAutoConfiguration.toolCallbackProvider` bean 加 `.toolObjects(...)` 一行。
  - `ai/config/AiVendorRegistry` — 协议层成员表（`OPENAI_COMPATIBLE_VENDORS`，完整清单见类级 Javadoc），决定哪些 vendor 走 OpenAI 兼容协议（支持私 Key）。覆盖国外官方（`openai` / `deepseek` / `ollama` — deepseek 走 OpenAI starter 复用 `openAiChatModel`）+ 国内 OpenAI 兼容（`dashscope` 阿里通义 qwen / `glm` 智谱 / `kimi` 月之暗面 / `doubao` 字节豆包 / `hunyuan` 腾讯混元）+ 通用中转（`siliconflow` / `oneapi` / `openrouter` / `minimax`）。url/model/enabled/api-key 已搬到 `application.yaml` 的 `spring.ai.providers.*` 段（Phase 5 统一 schema）；`ProviderPropertiesBridge`（`EnvironmentPostProcessor`）自动桥接到对应 starter namespace，业务代码零改动。
  - `ai/service/PreferenceResolver` + `ai/service/AiPreferenceService` — 三态偏好解析 + 私 Key 增删改查。
  - `ai/client/ApiKeyCipher` — AES-256-GCM 加解密，密钥派生自 `spring.ai.preference.master-key` → 回退 `jwt.secret`。
  - `router/ChatModelRouter` — 回退链主实现。`resolveWithFallback(prompt)` 按 `spring.ai.fallback-chain` 顺序展开链；`isFallbackTriggering` 白名单 (`LLM_PROVIDER_ERROR` / `LLM_UPSTREAM_TIMEOUT`)；`isPrimaryVendorOpen` 暂恒 false（Phase 5 计划用 Spring AI retry / Resilience4j 重做）。构造时按 bean 名归一化：`openAiChatModel → openai` 等；并跑一遍 OpenAI 兼容 vendor aliasing — yaml 配的 `deepseek` / `dashscope` / `glm` / `kimi` / `doubao` / `hunyuan` / `siliconflow` / `oneapi` / `openrouter` / `minimax` 等 vendor key,如果没自己 ChatModel bean,按 protocol 推断别名到对应 starter 的 ChatModel(OPENAI 协议共用 `openAiChatModel`)。
  - `controller/{AiController (/api/ai/chat),AiStreamController (/api/ai/chat/stream, SSE),AiConversationController (/api/ai/conversations/**),AiUsageController (/api/ai/usage/**)}` + `ai/controller/{AiPreferenceController (/api/ai/preference),AiAdminController (/api/admin/ai/global-default)}`。
  - `controller/dto/{ChatRequestDto (messages: List<Message>),SendMessageDto,CreateConversationDto,UpdateTitleDto,PinConversationDto}` + `controller/vo/{ConversationVo,ConversationDetailVo,MessageVo,UsageVo,UsageSummaryVo}`；admin/preference 控制器有 `ai/controller/dto/{PreferenceVo,UpdatePreferenceDto,UpdateGlobalDefaultDto}`。
  - `entity/{AiConversation,AiMessage,AiMessageUsage}`（裸 `entity/`）+ `ai/entity/{AiGlobalDefault,UserAiPreference}`（在 `ai/` 下）。
  - `repository/{AiConversationRepository,AiMessageRepository,AiMessageUsageRepository}`（裸）+ `ai/repository/{AiGlobalDefaultRepository,UserAiPreferenceRepository}`（在 `ai/` 下）。
  - `error/LlmErrorMapper` + `error/{StreamCancelledException,StreamTimeoutException,StreamUpstreamException}` — LlmException 映射 + 流专用标记。
  - spring-ai-full-migration 已删：`com.nexusforge.model.ChatModel` / `ChatCapabilities` SPI；`provider/openai/{OpenAiChatModel,OpenAiCompatibleChatModel,QwenChatModel,DeepSeekChatModel,OllamaChatModel,OpenAiJsonMapper,StreamParser}` 自实现；`provider/anthropic/{AnthropicChatModel,AnthropicJsonMapper,AnthropicMessagesStreamParser}` 自实现；`provider/support/{ChatModelHttpSupport,CircuitState}`；`client/{FunctionCallAggregator,ToolRegistry,ToolExecutor,ToolResult}` 工具自实现；`stream/{OpenAiStreamParser,SseFormat}`；`com.nexusforge.ai.*` 9 个旧 chat DTO（`ChatMessage` / `ChatRequest` / `ChatResponse` / `ChatChunk` / `ChatUsage` / `DeltaToolCall` / `ToolCall` / `ToolDefinition` / `Role`）；`com.nexusforge.chat.SseEventCodec`。所有这些都已被 Spring AI 官方实现替代。
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
  - 新厂商接入通过 Spring AI 官方 starter（`spring-ai-starter-model-<vendor>`，放在 `nexus-forge-ai/build.gradle`）+ 在 `application.yaml` 加 `spring.ai.providers.<vendor>.{enabled, api-key, base-url, default-model}`（Phase 5 起为统一 schema，`api-key` / `base-url` / `default-model` 由 `ProviderPropertiesBridge` 自动桥接到 `spring.ai.<starter-ns>.api-key` / `…base-url` / `…chat.options.model`）+ 在 `AiVendorRegistry.OPENAI_COMPATIBLE_VENDORS` 加 vendor 名（仅 OpenAI 兼容协议需要；Anthropic / Gemini 走自家协议无需这步）。spring-ai-full-migration 完成后本仓库已无自实现 provider，所有 vendor 走 starter。
  - OpenAI 兼容 vendor 的私 Key 路径通过 `VendorChatModelFactory` 用 `OpenAiChatModel.builder().options(...).apiKey(...).baseUrl(...).build()` 动态构造（按 `sha256(apiKey)` 缓存）。Anthropic 私 Key 模式暂未实现（`VendorChatModelFactory` 显式 throw `LLM_INVALID_REQUEST`）。
  - 系统模式的模型解析由 `PreferenceResolver` 管理。要影响 LLM 调用的模型,通过 `LlmClient.callWithToolLoop` 的 `enrichPromptWithToolCallbacks` helper 或 `LlmClient.withModelInOptions` 写 `prompt.getOptions().getModel()`——Spring AI 强类型 `ChatOptions` 通过 `mutate()` 重建,绝不直接改动上游载荷。
  - 私有 Key 请求绕过 `QuotaService`、回退链和 IP `RateLimitGuard`。通过 `PreferenceResolver.KeySource` 枚举分流(平台侧按 source 标签在 Micrometer 里区分"平台承担" vs "用户自付")。
  - yaml 中既无 `spring.ai.providers.<vendor>.base-url` 又无非空子类默认值时,`VendorChatModelFactory` 立即 fail-fast 抛 `LLM_CONFIG_MISSING`(不静默回退到 Java 硬编码;Phase 4 重构后 Java 已无硬编码值,这种"过期默认值"陷阱物理消失)。
  - 管理员全局默认是强制的:`ai_global_default.model='__UNSET__'`(哨兵)使每个系统模式请求返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`,直到管理员调用 `PUT /api/admin/ai/global-default`。不 grep resolver 就不要改哨兵字符串。
  - Tool 回路:加业务工具只需在 `@Component` 类里写 `@Tool` 方法,然后 `AiAutoConfiguration.toolCallbackProvider` bean 的 `MethodToolCallbackProvider.builder().toolObjects(...)` 加一行。Spring AI 2.0 的 `AssistantMessage.builder().toolCalls(...)` 是构造 tool_call 终止帧的唯一路径(没有 `(content, List<ToolCall>, Map)` 公共构造器)。Tool loop 上限由 `AiProperties.maxToolTurns` 兜底,默认 3。
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

分页响应约定：

- 列表类接口统一返回 `Result<PageResult<T>>`，**不再**返回 `Result<List<T>>`。
- `PageResult<T>`（`com.nexusforge.base.PageResult`）字段：records / total / page（1-based）/ size / pages / hasNext / hasPrevious。字段命名遵循 Element Plus / Ant Design Pro 主流约定。
- Controller 端通过 `@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size` 接参；service 层用 `Pageable`（Spring Data，0-based）查询，调 `PageResult.of(Page<T>)` 转换。
- 旧 `List<T>` 全量接口保留为 deprecated 风格，**不再新增**；后续改造按需就地切到分页。

软删除约定（BaseEntity + @SQLDelete / @SQLRestriction）：

- **基础设施**：`com.nexusforge.base.BaseEntity` 提供 `deletedAt: OffsetDateTime` 字段 + `isDeleted()` helper。`@PrePersist` / `@PreUpdate` 只动 `createdAt` / `updatedAt`，**不**自动写 `deletedAt`（由软删拦截器处理）。
- **必须直接放在 `@Entity` 上**：`@SQLDelete` 和 `@SQLRestriction` **不能**只放在 `@MappedSuperclass` 父类（Hibernate 6 / 7 不继承软删 SQL 改写语义到子类），必须在每个继承实体的 `@Entity` 上显式声明，参考 `User` / `AiConversation` / `AiMessage`。
- **软删触发**：`repo.delete(entity)` 自动拦截转 `UPDATE ... SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL`；所有 `find*` 自动加 `WHERE deleted_at IS NULL` 过滤。
- **真删入口**：合规 / 迁移场景用 JPQL `@Modifying @Query("DELETE FROM ...")` 或 `EntityManager.createNativeQuery`，并标注 Javadoc 说明。
- **恢复路径**：因为 `@SQLRestriction` 对 `@Modifying` 的 JPQL UPDATE **也会**拼接 `WHERE deleted_at IS NULL`，不能用 repo 的派生方法；用 `EntityManager.createNativeQuery` 走纯 JDBC 通道绕开（参考 `ConversationService.restoreConversation`）。
- **UserStatus.DELETED 已 @Deprecated**：`deleted_at` 取代其语义；存量数据兼容保留枚举值，但业务侧**不要再写** `user.setStatus(UserStatus.DELETED)`。
- **新增继承实体接入软删**：复制 `@SQLDelete(sql = "UPDATE <table> SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")` + `@SQLRestriction("deleted_at IS NULL")` 到新实体即可。

## 账号生命周期约定（封禁 / 注销 / 恢复，`nexus-forge-user`）

`AccountLifecycleService` + `AdminUserLifecycleController` 集中账号状态变更;
`AuditLogger<AccountLifecycleAction>` 写 `account_lifecycle_log` 表;
`UserDataDeletionEvent` 事件解耦 user 模块与业务模块(ai 等)的数据清理。

### 1. 端点

| Method | Path | 鉴权 | 描述 |
|---|---|---|---|
| `POST` | `/api/users/me/delete/request` | 登录态,提交密码二次确认 | 申请注销,发邮件验证码 |
| `POST` | `/api/users/me/delete/confirm`  | 公开(邮件链接) | 校验验证码,执行真删 |
| `POST` | `/api/users/me/restore`          | 公开(邮件 token) | 一次性 token 撤销注销(14 天内) |
| `POST` | `/api/admin/users/{id}/ban`     | `@PreAuthorize("hasRole('ADMIN')")` | 管理员封禁 |
| `POST` | `/api/admin/users/{id}/unban`   | `@PreAuthorize("hasRole('ADMIN')")` | 管理员解封 |
| `GET`  | `/api/admin/users/{id}/lifecycle` | ADMIN | 查某 user 全部审计事件 |
| `GET`  | `/api/admin/users/lifecycle?action=BAN` | ADMIN | 按 action 分页查全表 |

### 2. 数据模型

- `account_lifecycle_log` 表:`user_id / action / actor_id / actor_role / reason / metadata(JSONB) / created_at`
- 无 FK 到 `users.id` —— 真删 users 时审计必须保留(合规追溯)
- `@PreAuthorize` 走 Spring AOP,需 SecurityConfig 启用 `@EnableMethodSecurity`

### 3. PII 擦除不可逆

`AccountAnonymizer.anonymize(user)` 在注销时擦除:
- `username` → `deleted-{id}`
- `email` → `deleted-{id}@deleted.local`(释放原邮箱 + 保持 UNIQUE 约束)
- `nickname` → `已注销用户`
- `phone / avatarUrl / avatarKey` → null
- `password` → bcrypt(random UUID)(防止旧密码登录)
- `status` → BANNED(临时,防旧 refresh)
- `deleted_at` → now()(由 `@SQLDelete` 写)

**恢复时只清 `deleted_at` + 改 `status=ACTIVE`,username/email 不可恢复**。
用户恢复后必须走"忘记密码"流程重设凭证。这是 GDPR/个保法的合规边界。

### 4. Redis 命名空间

| Key | 用途 |
|---|---|
| `pwd:delete:code:{emailHash}`      | 注销验证码 hash(5 min) |
| `pwd:delete:attempts:{emailHash}`  | 验证失败计数(5 min) |
| `pwd:delete:rate:{emailHash}`      | 邮箱维度限流 60s 内 1 次 |
| `pwd:restore:token:{tokenHash}`    | 一次性恢复 token → userId(14 天) |

`emailHash = SHA-256(email.toLowerCase().trim())`,Redis 不留邮箱明文。
**`RedisCleaner` 集成测试清 `pwd:delete:*` 前缀**(commit 5 新增)。

### 5. 事件机制

- `UserBannedEvent` 触发 `AuthService.logoutAllRefreshTokens`(已有,封禁 / 注销踢 refresh)
- `UserDataDeletionEvent` 触发 `AiUserDataDeletionListener` 真删 ai_conversations / messages / usage
- 监听方约束:幂等 + 不抛异常(失败 log warn)

### 6. 通用 AuditLogger 接口(为后续模块复用)

`com.nexusforge.audit.AuditLogger<A>` 接口定义于 `nexus-forge-common`:
- 单方法 `log(AuditEvent<A> event)`,泛型 A 让模块用枚举约束 action
- 实现不抛异常、线程安全;由 `@Component` 注册
- 当前实现:`AccountLifecycleAuditLogger` 写 `account_lifecycle_log`;后续模块(文件 / 订单)可加自己的实现

### 7. 邮件发送

`UserDeletionMailer` interface + `LoggingUserDeletionMailer` / `SmtpUserDeletionMailer` 二选一实现:
- `mail.mode=logging`(默认):落 `build/dev-mail/delete-{ts}-{emailHash8}.eml`
- `mail.mode=smtp`:走 `JavaMailSender` 真实 SMTP

模板:`templates/account-delete-confirm.html`(6 位验证码)/`account-deleted-notice.html`(restoreUrl)。

### 8. 关键踩坑(从 commit 5 IT 调试中提炼)

#### 8.1 @AuthenticationPrincipal 在 unit test / @Nested IT 中不解析
`@AuthenticationPrincipal` 走 `AuthenticationPrincipalArgumentResolver` → MVC dispatcher 链,
在 unit test 直接调 controller 方法时 / `@Nested` 上下文切换时不解析 → NPE。
**修复**:controller 用 helper 方法手动从 `SecurityContextHolder` 拿 principal。
`AccountDeletionController` 和 `AdminUserLifecycleController` 都用此模式。

#### 8.2 hasRole('X') 期望 "ROLE_X" 前缀
`SimpleGrantedAuthority` 必须带 `ROLE_` 前缀,否则 `hasRole('ADMIN')` 永远不匹配。
- `JwtAuthenticationFilter`:authority 加 `"ROLE_" + role`
- `LoginUser.getAuthorities()`:authority 加 `"ROLE_" + role.name()`
- 缓存清理:`UserRoleProvider.evict(userId)` 在角色变更后必须调,否则 Filter 加载旧 role

#### 8.3 @PreAuthorize 抛 AuthorizationDeniedException
Spring Security 6 的 `@PreAuthorize` 抛的 `AccessDeniedException` 走 AOP 拦截器,
**不会进入** Filter chain 的 `ExceptionTranslationFilter`,默认被 `GlobalExceptionHandler` 兜底 500。
**修复**:`GlobalExceptionHandler` 加 `@ExceptionHandler(AccessDeniedException.class)` → 403 + 1005 FORBIDDEN。
对应 `SecurityConfig` 必须有 `@EnableMethodSecurity`。

#### 8.4 LockedException / DisabledException 在 login 端点
被封禁 / 已禁用的账号登录会抛 `LockedException` / `DisabledException`,
若只 catch `BadCredentialsException` 会冒泡到兜底 500。
**修复**:`AuthController.login` catch 这两个 → 转 `INVALID_CREDENTIALS`(对外不区分原因,防枚举)。

#### 8.5 @Scheduled cron placeholder 解析时序
`@Scheduled(cron = "${account-lifecycle.expire-deletions-cron}")` 的 placeholder 解析
**早于** `@ConfigurationProperties` bean 绑定,缺默认值会报 `Could not resolve placeholder`。
**修复**:给 placeholder 加默认值 `@Scheduled(cron = "${account-lifecycle.expire-deletions-cron:0 0 3 * * *}")`。

#### 8.6 Flyway 容器复用 + IF NOT EXISTS 幂等
Testcontainers `withReuse(true)` 容器跨 JVM 启动保留 → DB schema 固化;
迁移用 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` 保证幂等。

#### 8.7 entity @Column(insertable=false) 跨容器问题
`@Column(insertable=false, updatable=false)` 让 JPA 不写 `created_at`,依赖 DB DEFAULT。
容器复用 + ALTER 路径可能让 DEFAULT 不生效 → 报 NOT NULL。
**修复**:用 `@PrePersist` 钩子让 entity 自己填 `createdAt`,不依赖 DB DEFAULT。

#### 8.8 RestTemplate 对 4xx / 429 自动重试
Spring `RestTemplate` 用 Apache HttpClient 时:
- 4xx 抛 `HttpClientErrorException`(默认 ErrorHandler 行为)
- 429 自动重试(因 `HttpRequestRetryExec` 默认配置)
**修复**:用 `restNoErrorHandling()` + 手动断言 status code;或用 `java.net.http.HttpClient` 绕开。
(commit 3 的 PasswordResetIT 已踩过同类坑,见 commit 3 踩坑节。)

#### 8.9 MailCapture 解析多类邮件
"确认"邮件有 `<div class="code">NNNNNN</div>`, "已注销通知"没有。
**修复**:`MailCapture.Mail.code` 允许为 null,提供 `latestTo(email)`(有 code)和
`latestWithoutCodeTo(email)`(无 code)两个 helper。

### 9. 关键文件

- `nexus-forge-common/.../audit/AuditEvent.java` `AuditLogger.java` — 通用审计接口
- `nexus-forge-common/.../event/UserDataDeletionEvent.java` — 跨模块数据清理事件
- `nexus-forge-user/.../service/AccountLifecycleService.java` — 主业务
- `nexus-forge-user/.../service/AccountAnonymizer.java` — PII 擦除工具
- `nexus-forge-user/.../audit/AccountLifecycleAuditLogger.java` — 审计实现
- `nexus-forge-user/.../controller/AccountDeletionController.java` — 用户端点
- `nexus-forge-user/.../controller/AdminUserLifecycleController.java` — admin 端点
- `nexus-forge-user/.../notification/UserDeletionMailer.java` — 邮件接口
- `nexus-forge-ai/.../event/AiUserDataDeletionListener.java` — AI 数据清理
- `nexus-forge-web/.../test/.../AccountLifecycleIT.java` — 端到端 happy path


## 操作审计约定（`operation_audit_log` 表 + `@Audited` AOP,`nexus-forge-core`）

`OperationAuditLog` 实体 + `OperationAuditLogRepository` + `@Audited` 注解 + `AuditAspect` AOP 切面 + `AdminAuditController` 管理端点。P2 5 commit 落地,约定如下:

### 1. 与已有 `account_lifecycle_log` 的区别

| 维度 | `account_lifecycle_log` | `operation_audit_log` |
|---|---|---|
| 粒度 | 高粒度业务事件(BAN / DELETE / RESTORE) | 低粒度 HTTP 请求 |
| 触发方式 | service 显式调 `AuditLogger.log(...)` | controller 加 `@Audited` 注解,AOP 自动 |
| 字段 | 业务 metadata + 审计时间 | method / path / ip / UA / status_code / latency / error_code |
| 用途 | 业务回溯(状态机) | 安全 / 合规 / 性能分析 |
| 写权限 | service | controller 方法(自动) |

两表并存,各管各的。

### 2. 端点(AdminAuditController `/api/admin/audit-logs`)

| Method | Path | 鉴权 | 描述 |
|---|---|---|---|
| `GET` | `/api/admin/audit-logs?userId=&action=&resource=&page=&size=` | `@PreAuthorize("hasRole('ADMIN')")` | 多维过滤 + 分页;`userId` / `action` / `resource` 任一可空 |

不在 admin_search 加 `@Audited`(自己审计自己,会无限循环)。

### 3. 表结构(`operation_audit_log`,V20260830_002)

- **15 列**:`id` / `user_id` / `action` / `resource` / `resource_id` / `method` / `path` / `ip` / `user_agent` / `result` / `status_code` / `latency_ms` / `error_code` / `metadata` / `created_at`
- **3 索引**:
  - `idx_op_audit_log_user_created (user_id, created_at DESC)` — 业务主查询
  - `idx_op_audit_log_resource (resource, resource_id, created_at DESC)` — 资源维度
  - `idx_op_audit_log_created (created_at DESC)` — 管理员后台时间窗 / 合规导出
- **不软删**:审计行只追加,合规追溯需要物理持久;不挂 `@SQLDelete` / `@SQLRestriction`(防误删)

### 4. `@Audited` 注解

```java
@Target(METHOD) @Retention(RUNTIME)
public @interface Audited {
    String value();                    // 必填 action 名称,如 "user.update"
    String resource() default "";       // 资源类型
    String resourceId() default "";     // SpEL 表达式("#userId" / "#principal.userId()")
    boolean recordArgs() default false;  // 入参到 metadata JSONB
    boolean recordResult() default false; // 留 TODO
}
```

**只用在 controller 注解方法**,不要用在 service 内部(避免与 `AccountLifecycleAuditLogger` 业务事件层重复)。

### 5. AOP 切面捕获字段

| 字段 | 来源 |
|---|---|
| `user_id` | `SecurityContext` + `UserPrincipal.userId()`(null 表示 anon) |
| `method` / `path` | `HttpServletRequest.getMethod()` / `getRequestURI()` |
| `ip` | `X-Forwarded-For` 头(多级代理取最左)> `getRemoteAddr()` |
| `user_agent` | 截断 255 字符 |
| `result` | 抛异常 → `FAILURE`,正常 → `SUCCESS` |
| `status_code` | `HttpServletResponse.getStatus()`,拿不到兜底 200/500 |
| `error_code` | `BaseException.getCode()`(业务异常时) |
| `latency_ms` | `System.nanoTime()` 算 |
| `resource_id` | SpEL 求值,失败 catch 兜 null |
| `metadata` | `recordArgs=true` 时存基本类型 / String,跳过 Object / 集合(防大对象 / 敏感 DTO) |

### 6. 容错

- **审计写库失败 log warn 不抛**(主链路不能因审计挂)
- **SpEL 求值失败 catch 兜 null**(`#userId` 找不到变量时)
- **业务异常原样抛给上游** — 切面只在 finally 写审计,异常路径正常传播
- **Micrometer 指标**:`audit.record{result=success|failure}` counter;`MeterRegistry=null` 走"无埋点"降级

### 7. 已应用端点(本 commit 3)

- `UserController.updateMe` → `user.update`(`#principal.userId()`)
- `UserController.changePassword` → `user.password.change`
- `UserController.removeAvatar` → `user.avatar.remove`
- `FileController.upload` → `file.upload`(`recordArgs=true` 存 filename / biz)
- `FileController.deleteById` → `file.delete`(`#id`)

未加的端点(留 TODO,后续按需补):
- `UserController.uploadAvatar`(头像上传走 updateAvatar 内部)
- `FileController.adminSearch`(admin 查操作无业务审计价值)
- 分片上传 3 端点(init / presign-part / complete)— 系统级辅助操作

### 8. VO 脱敏(`OperationAuditLogVo`)

- 不暴露 `metadata` JSONB 内部(可能含密码 / token)
- 不暴露 `bucket` / `etag` 等内部细节
- `ipPrefix` 截断 16 字符 + "…" — admin 看不到完整 IP(防滥用,真实定位走日志 / DB 直查)

### 9. 关键踩坑

- **JSONB 反序列化 Long 变 Integer**(Jackson 默认配置)— 测试断言用 `isEqualTo(42)` 而非 `42L`
- **Mockito strict 模式 + setUp 共享 mock**:`UnnecessaryStubbingException`;`setUp` 用 `lenient()` 兜底
- **Spring AOP self-invocation**:`@Audited` 注解方法被同类方法调 → 不走代理;只用在 controller 入口,不在 service 内部(无此问题)
- **`@PreAuthorize` 抛 `AccessDeniedException`** 走 `GlobalExceptionHandler` 兜底 403 + 1005(commit 5f368cf 已加);admin 端点 `@PreAuthorize("hasRole('ADMIN')")` 不进自身审计(无循环)

### 10. 集成测试覆盖(端到端)

`OperationAuditIT` 5 case 走真 PG / Redis / RustFS:
- `Aspect`(2) PATCH /me 落 user.update 行 / POST /me/password 落 user.password.change 行
- `Failure`(1) 业务异常 → result=FAILURE + error_code 提取
- `Admin`(2) admin 查 VO 字段对齐 / 非 admin 403 不进审计行


## 文件元数据约定（`file_metadata` 表与落库路径,`nexus-forge-file`）

`FileEntity` + `FileMetadataRepository` + `FileService` 提供"业务可查我上传过的文件"能力。`UserDataDeletionEvent` 触发 `FileUserDataDeletionListener` 真删,与账号生命周期 GDPR 闭环。P2 5 个 commit 落地,约定如下:

### 1. 端点(FileController `/api/files/**`)

| Method | Path | 鉴权 | 描述 |
|---|---|---|---|
| `POST`   | `/upload` | 登录(biz 必填) | multipart 上传,owner 从 SecurityContext 取,落 ACTIVE 行 |
| `POST`   | `/upload-legacy` | 登录 | 老无 biz 路径(系统级,无 owner 不入库) |
| `POST`   | `/confirm/{key}` | 登录 | 前端直传完成回调,翻 PENDING→ACTIVE;幂等 |
| `GET`    | `/mine?biz=&page=&size=` | 登录 | 我的文件分页;只查 ACTIVE |
| `GET`    | `/{id}` | 登录 + owner 校验 | 单文件详情 |
| `DELETE` | `/{id}` | 登录 + owner 校验 | 软删(@SQLDelete 翻 status=DELETED) |
| `GET`    | `/admin?ownerId=&biz=&status=&page=&size=` | `@PreAuthorize("hasRole('ADMIN')")` | 管理员视角,跨 biz / status 过滤 |
| `GET`    | `/presigned/put?biz=&filename=&expirySeconds=` | 登录 | 写 PENDING + 颁 PUT URL |
| 老端点  | `/download/{key}` / `/object/{key}` / `/multipart/*` / `/presigned/get` | 登录 | 签名不变;只清对象存储不动 DB 元数据 |

### 2. 表结构(`file_metadata`,V20260830_001)

- **三态**:`status` = `PENDING`(凭证已发待 PUT) / `ACTIVE`(已确认) / `DELETED`(软删)
- **unique**:`(bucket, object_key)` 联合唯一 — confirm 重复入库 / 多副本上传幂等
- **索引**:
  - `idx_file_metadata_owner_uploaded (owner_id, created_at DESC)` — 「我的文件」主查询
  - `idx_file_metadata_biz_owner (biz_type, owner_id)` — 按 biz 过滤
  - `idx_file_metadata_pending (created_at) WHERE status='PENDING'` — 后续超时清理 TODO
  - `idx_file_metadata_checksum (checksum_sha256) WHERE checksum_sha256 IS NOT NULL` — 后续 dedup
- **`owner_id` 可空**:系统 / anon 上传无 user;业务侧查"我的文件"永远带 `owner_id = currentUser()`
- **`checksum_sha256` 留字段**但暂不加 unique 约束,等真用上 dedup 再加
- **DDL 幂等**:`CREATE TABLE / INDEX IF NOT EXISTS` — 容器复用 + ddl-auto 切 prod 时不会因"已存在"挂

### 3. PENDING vs ACTIVE 状态机

```text
PENDING ──confirm──▶ ACTIVE ──softDelete──▶ DELETED
   │                                              ▲
   └────── 直接 hardDelete(GDPR 真删)─────────────┘
```

- 上传(`uploadByBiz`):`upsertPending` 写 PENDING → storage 上传 → `markConfirmed` 翻 ACTIVE
- 直传凭证(`issueUploadCredential`):只写 PENDING,size=0 待 confirm 回填
- confirm:`markConfirmed` 幂等(已 ACTIVE 早返,不重写 confirmedAt)
- 软删:`repo.delete` 触发 `@SQLDelete`:`status='DELETED'` + `deleted_at=now()`,查询自动过滤

### 4. GDPR 真删闭环

- `user` 模块 `AccountLifecycleService.deleteConfirm` publish `UserDataDeletionEvent(userId)`
- `file` 模块 `FileUserDataDeletionListener.onUserDataDeletion` 监听:
  1. `fileRepo.findAllByOwnerId` 拿未软删行(避免对已删的 storage 重复 delete)
  2. 逐个 `storage.delete` 清对象,失败 log warn 不抛
  3. `EntityManager.createNativeQuery("DELETE FROM file_metadata WHERE owner_id = :userId")` 物理删(**绕 @SQLRestriction**,含已软删)
  4. 整体异常被 catch,不抛给主业务(账号已注销,数据没清是次要问题)
- 端到端覆盖在 `AccountLifecycleIT.forgot_account_lifecycle_full_path` 验证(`UserDataDeletionEvent` 串联 ai / file)

### 5. 错误码

- `2017 FILE_NOT_FOUND` — confirmUpload 找不到对应 key(4xx)
- `2018 FILE_ALREADY_DELETED` — confirmUpload 撞上已软删行
- `2019 FILE_FORBIDDEN` — 软删 / 查详情 owner 校验失败(403)

### 6. 模块依赖与 SQL 位置约定

- **SQL 迁移放 `nexus-forge-user/src/main/resources/db/migration/`**:本约定下,file 表的 DDL 也由 user 模块持有。理由:Spring Boot 4.1 移除了 Flyway 自动装配,`FlywayMigrationRunner`(`nexus-forge-user`)统一接管,跨模块 SQL 都聚合在它读得到的资源路径。等模块多了再统一搬到各模块自有 `db/migration/`
- **JPA 实体在 owning 模块**:`FileEntity` / `FileMetadataRepository` 都在 `nexus-forge-file`,`@SpringBootApplication` 默认扫 `com.nexusforge` 已覆盖,无需 `@EntityScan`
- **flyway-database-postgresql** 必需 — Hibernate 6 在 PG 上需要 dialect
- **单测不依赖 Docker**:`FileServiceTest` / `FileControllerTest` / `FileUserDataDeletionListenerTest` 全 Mockito 隔离(共 27 case);`FileMetadataRepositoryTest`(8 case,Testcontainers 跑真实 SQL)在 Docker 恢复后跑

### 7. 关键踩坑

- **`@SQLDelete` 必须直接放 `@Entity` 上**(Hibernate 6 不从 `@MappedSuperclass BaseEntity` 继承) — 已在 `FileEntity` 显式声明;删时同步把 `status='DELETED'`,避免"deleted_at 非空但 status 还是 ACTIVE"状态不一致
- **`(bucket, object_key)` unique**:前端重试 confirm → service 走 `findByBucketAndObjectKey` 先查后翻,不抛 unique 冲突
- **EntityManager 原生 SQL 物理删**:绕过 `@SQLRestriction` 是唯一办法(JPA 派生方法会被拼 `WHERE deleted_at IS NULL`,GDPR 路径会漏删已软删行)。参考 `AiUserDataDeletionListener` 同模式
- **`@AuthenticationPrincipal` 在 unit test 不解析** → controller 用 `currentUserId()` helper 从 `SecurityContextHolder` 拿(同 `AccountDeletionController` 模式)
- **`status` VARCHAR(16) DEFAULT 'PENDING'**:JPA 写新行时 entity 默认值 `FileStatus.PENDING` + 兜底 DDL DEFAULT,容器复用场景两端都生效
- **`presigned/put` 改造后必填 biz**:老无 biz 路径迁到 `/upload-legacy`,系统级场景用。AGENTS.md 这条约定被 `AuthUserFileE2EIT` 验证


## 分布式锁约定（`nexus-forge-core` SPI,Redis 实现）

`DistributedLock` SPI + `RedisDistributedLock` + `DistributedLockTemplate` 三件套,提供"拿不到立即抛 / 拿不到返空 / 带等待 / try-finally 自动释放"四种 API。已应用于 `FileService.uploadByBiz` 防并发上传、`AccountLifecycleService.requestDeletion` 防同 user 重复申请。P2 5 commit 落地,约定如下:

### 1. 三层 API 选型

| 场景 | 推荐 API | 说明 |
|---|---|---|
| 拿不到立即返 Optional | `lock.tryLock(key, lease)` | 业务 try-catch 转 429 |
| 拿不到立即抛异常 | `lock.tryLockOrThrow(...)` 或 `template.lock(...)` | 业务零样板,锁层兜 |
| 带等待超时 | `template.tryLockWithWait(key, wait, lease, sup)` | 50ms 轮询 |
| 无返回值业务 | `template.runWithLock(key, lease, runnable)` | 便捷 |
| 业务常用模式(自动释放) | `template.lock(key, lease, sup)` | try-finally 兜底,supplier 抛错锁仍释放 |

### 2. Redis 协议

- **拿锁**:`SET key token NX PX leaseMs`(走 Spring `setIfAbsent` 重载;token 是 `UUID.randomUUID()` 字符串,保证唯一性)
- **放锁**:Lua 脚本比对 token 后 DEL(防误删别人的锁)
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
  else return 0 end
  ```
- **lease 必填**:持锁线程 crash 后锁不释放 → 死锁;lease 应该**大于**业务最大耗时,否则中途自动过期别人可拿,本 holder 调 unlock 静默返 false
- **key 命名空间**:`lock:` 前缀(全局 `DistributedLockProperties.keyPrefix`),与 `auth:` / `pwd:` / `ai:rl:` 隔离;业务侧 key 仍带业务前缀(例 `upload:avatar:user-100`)

### 3. 未实现(留 TODO,跟 Redisson 对比)

- **重入**:同线程不可重入,会死锁自己;需要 `ThreadLocal` token 栈
- **Watchdog 自动续约**:超 lease 业务不会被自动续期,直接放掉;需要后台线程(Redisson 那种)
- **PubSub 通知**:`tryLockWithWait` 现在轮询(50ms 间隔),有 redis pubsub 的话能更省
- **AOP `@DistributedLock` 注解**:目前只 programmatic API

### 4. 错误码

- `DistributedLock` SPI:**不**抛业务异常(只抛 Redis 透传异常)
- `LockAcquireException`:`DistributedLockTemplate.lock` / `tryLockWithWait` 拿不到时抛
- `GlobalExceptionHandler.mapStatus` 已加 `2020 FILE_UPLOAD_CONFLICT → 409 Conflict`(用于 `FileController` 锁冲突返业务码)
- **AccountLifecycleService.requestDeletion** 锁冲突 → 转 `2015 RESET_CODE_SEND_TOO_FREQUENT`(与 Redis 60s 邮箱限流同一错误码,前端体验一致)

### 5. Micrometer 指标

- `lock.acquire{result=success|busy|miss}` —— 拿锁结果
- `lock.release{result=success|miss}` —— 释放结果(miss = lease 过期)
- `lock.contention{result=hit|miss}` —— 实际竞争程度
- `lock.acquire.latency` —— 拿锁耗时(含等待轮询)timer
- `MeterRegistry` 走构造器注入,允许 `null`(单测 / 没启 actuator 时不埋点,主链路不受影响)

### 6. Spring AOP self-invocation 坑(踩了 2 次)

`@Transactional` 加在外层方法 + lambda 内 `this.doXxx()` 调不到 AOP 代理,事务不生效。**正确拆法**:把工作方法拆为 private(`doUploadByBizInternal` / `doRequestDeletion`),lambda 调 private 方法;`@Transactional` 留在外层方法上,代理在 controller/service 入口触发,锁在事务内拿,事务在锁释放前 commit。

### 7. 关键踩坑

- **`setIfAbsent(key, value, Duration)`**:Spring 6 内部走 `SET NX PX 毫秒`,不是 `EX 秒`,精度到亚秒
- **Lua 比对 + DEL 必须原子**:不能先 `GET` 再 `DEL`(中间窗口期被别人拿,删了别人的锁);用 Lua 脚本保证原子
- **Mockito strict 模式 + setUp 共享 lockTemplate mock**:`lock(...)` stub 在 ConfirmUpload / FindMyFiles 等不调 upload 的子测试用不到 → 报 `UnnecessaryStubbingException`;`setUp` 用 `lenient()` 兜底
- **RedisCleaner** 要扩 `lock:*` 前缀清理(在 commit 5 加),否则不同 IT 类之间污染

### 8. 集成测试覆盖(端到端)

`DistributedLockIT` 13 case 走真 Redis:
- Acquire/Release 3 case(基本循环)
- AutoExpire 1 case(lease 1s 后 key 自动消失)
- Ownership 1 case(旧 token 不能 unlock 新 holder 的锁,防误删)
- KeyPrefix 2 case(自动加 `lock:` / 已带前缀不重复)
- Template 3 case(`lock` / supplier 异常仍释放 / `tryLockWithWait` 200ms 超时)
- Concurrency 1 case(10 线程真竞争,只有 1 个拿得到)
- Namespace 2 case(不影响其它命名空间 / `flush()` 清干净)


## 密码重置约定（邮箱验证码，`nexus-forge-auth`）

`PasswordResetService` + `AuthController` 的 `/api/auth/password/reset/{request,confirm}` 提供"忘记密码"流程，详见 commit 2 / 3。约定如下：

- **端点**：`POST /api/auth/password/reset/request`（提交邮箱） + `POST /api/auth/password/reset/confirm`（邮箱 + 6 位 code + 新密码）；均 `@SecurityRequirements` 公开端点，`SecurityConfig` 放行 `/api/auth/password/reset/**`。
- **邮件通道**：`com.nexusforge.mail.EmailSender` SPI（`nexus-forge-common`）；`LoggingEmailSender`（`@ConditionalOnProperty(mail.mode=logging)`，默认，邮件落 `build/dev-mail/*.eml`） + `SmtpEmailSender`（`@ConditionalOnProperty(mail.mode=smtp)`，prod 走 `spring.mail.*`）。两个 `@Component` 模式互斥，业务侧 `@RequiredArgsConstructor private final EmailSender emailSender;` 拿到唯一实现。**不要**在业务代码里直接依赖 `JavaMailSender`。
- **验证码存 hash 不存明文**：`pwd:reset:code:{emailHash}` 存 `SHA-256(code)`；`emailHash = SHA-256(email.toLowerCase().trim())` —— Redis 残留**不留**邮箱明文。比对用 `MessageDigest.isEqual` 防 timing attack。
- **Redis 键命名空间 `pwd:reset:*`**（与认证 `auth:*` 隔离）：`code:{emailHash}` / `attempts:{emailHash}` / `rate:{emailHash}` / `ip-rate:{ip}`。**`RedisCleaner`**（`testsupport`）扩展清 `pwd:reset:*` 前缀做 IT 隔离。
- **限流分两层**：邮箱维度（60s 内 1 次，key `pwd:reset:rate:{emailHash}`）+ IP 维度（60s 内 3 次，key `pwd:reset:ip-rate:{ip}`），走 `RedisRateLimiter`（`core`，INCR + EXPIRE NX 模式）。**与 `@RateLimit` 注解互补**（注解是本地令牌桶，本类是 Redis 固定窗口）。
- **失败次数超限自动失效**：`pwd:reset:attempts:{emailHash}` 用 INCR 自增，首次设 TTL（= `codeTtlSeconds`），超过 `maxAttempts`（默认 5）立即删 `code` + `attempts` 两个 key，抛 `2014 RESET_CODE_TOO_MANY_ATTEMPTS`（HTTP 429）。
- **防邮箱枚举**：未知邮箱 / 被封禁用户 / 邮件发送失败 —— **都**返回 200 OK + server log 体现真实状态。响应中不暴露邮箱是否有效。限流和 `2014` / `2015` 是仅有的对外可见的"防滥用"信号。
- **改密后只踢 refresh**：`authService.logoutAllRefreshTokens(userId)` 删 refresh 版本号 key，使历史 refresh 失效。**Access 不吊销**——access 在 ≤15min TTL 内自然到期；如未来需立即吊销，扩 access 黑名单（已在 `AuthService` Javadoc 留 TODO）。
- **错误码**：`2013 RESET_CODE_INVALID`（400）、`2014 RESET_CODE_TOO_MANY_ATTEMPTS`（429）、`2015 RESET_CODE_SEND_TOO_FREQUENT`（429）、`2016 RESET_CODE_USER_BANNED`（403，目前服务端静默不返回，留作未来显式分支）。`GlobalExceptionHandler.mapStatus` 加 2014/2015 → 429、2016 → 403。
- **集成测试踩坑**：
  - `LoggingEmailSender` 写盘路径 `build/dev-mail/` 是相对工作目录；`bootRun` 跑在仓库根，`@SpringBootTest` 跑在当前模块。**`MailCapture` 必须 `@Autowired LoggingEmailSender` 拿 `getOutDir()` 实际路径**，不能硬编码。
  - 验证码从 Thymeleaf 模板的 `<div class="code">NNNNNN</div>` 用 regex 抓取；模板改格式时 `MailCapture.CODE_PATTERN` 同步改。
  - 测试 **必须显式禁 DevTools**（`application-test.yaml` 加 `spring.devtools.restart.enabled: false` + `exclude: '**/bin/**'`），否则 DevTools 监视 IDE 输出目录（`nexus-forge-core/bin/main/`）残留旧 class，`GlobalExceptionHandler.mapStatus` 改动不生效（症状：2014 → 实际 400）。
  - **不能用默认 `RestTemplate` 测 429** —— 它底层 Apache HttpClient 默认对 429 自动重试，重试后 server 端 `codeKey` 已被清，第二次返回 `2013 INVALID`，assertThrows 看到最终 `BadRequest 2013` 完全误判。**用 `java.net.http.HttpClient` 绕开**（见 `PasswordResetIT.forgot_password_attempts_exceeded_clears_code`）。
  - 测试"验证码过期"用 `@Nested @TestPropertySource(properties = "password-reset.code-ttl-seconds=1")` 类 + `Thread.sleep(1500)`；不要污染外层默认 300s 配置。



## 重要文件

- `settings.gradle` — 模块列表。
- `build.gradle` — Java 26 toolchain、Spring Boot 4.1.0、共享子项目依赖、默认测试平台。
- `gradle/wrapper/gradle-wrapper.properties` — 经腾讯镜像的 Gradle 9.6.0；CI 重写为官方 Gradle 发行版。
- `nexus-forge-web/build.gradle` — 唯一启用 `bootJar { enabled = true }` 的模块；集成测试标签门控在此；还设置 `bootRun { workingDir = rootProject.projectDir }`，使 `optional:file:.env[.properties]` 相对仓库根解析。
- `nexus-forge-web/src/main/resources/application.yaml` — 默认激活 profile、`.env` 导入、multipart 限制、Springdoc 分组、日志 trace 模式、存储默认值、`spring.ai.preference.master-key`（`SPRING_AI_PREFERENCE_MASTER_KEY`）。
- `nexus-forge-web/src/main/resources/application-dev.yaml` / `application-prod.yaml` — DB、Redis、JWT、存储、quota / rate-limit 及每厂商 `spring.ai.providers.<vendor>.{enabled, api-key}` 的环境绑定；model / base-url 走 application.yaml 唯一来源(Phase 5 起 `providers.*` 是 single source of truth，`ProviderPropertiesBridge` 桥接到 starter namespace，dev/prod profile 不再单独配 `spring.ai.openai.*` / `spring.ai.deepseek.*` 等)。
- `nexus-forge-common/src/main/java/com/nexusforge/base/Result.java` 和 `enums/ResultCode.java` — 公开 API 响应契约（AI 网关码共享同一枚举）。
- `nexus-forge-core/src/main/java/com/nexusforge/error/GlobalExceptionHandler.java` — 中央 HTTP/错误映射。
- `nexus-forge-auth/src/main/java/com/nexusforge/config/SecurityConfig.java` 和 `filter/JwtAuthenticationFilter.java` — 请求认证流水线。
- `nexus-forge-auth/src/main/java/com/nexusforge/service/AuthService.java` 和 `util/JwtUtil.java` — 令牌生命周期不变量。
- `nexus-forge-user/src/main/java/com/nexusforge/user/service/UserService.java` — 注册、资料、头像、密码、封禁流程。
- `nexus-forge-user/src/main/java/com/nexusforge/flyway/FlywayMigrationRunner.java` 和 `src/main/resources/db/migration/V*.sql` — Spring Boot 4.1 没有 Flyway 自动配置；该 runner 取代之，SQL 文件是 schema 的事实来源。
- `nexus-forge-file/src/main/java/com/nexusforge/file/FileClientImpl.java` 和 `service/FileService.java` — 面向业务的文件适配器和键生成规则(P2 起 FileService 注入 `FileMetadataRepository`,upload / confirm / findMyFiles / softDelete 全部走 file_metadata 表)。
- `nexus-forge-file/src/main/java/com/nexusforge/file/entity/FileMetadata.java` 和 `repository/FileMetadataRepository.java` — 文件元数据 JPA 实体与仓库;`@SQLDelete / @SQLRestriction` 直接放 `@Entity` 上(不靠 BaseEntity 继承)。
- `nexus-forge-file/src/main/java/com/nexusforge/controller/FileController.java` — P2 新增 5 端点(`/upload` / `/confirm/{key}` / `/mine` / `/{id}` / `/admin` + admin `@PreAuthorize`);owner 走 SecurityContextHolder helper(同 AccountDeletionController 模式)。
- `nexus-forge-file/src/main/java/com/nexusforge/event/FileUserDataDeletionListener.java` — 监听 `UserDataDeletionEvent` 真删 file rows + 清对象存储(GDPR 闭环,与 `AiUserDataDeletionListener` 同模式)。
- `nexus-forge-user/src/main/resources/db/migration/V20260830_001__add_file_metadata.sql` — file_metadata 表的 DDL 权威源(遵循「SQL 放 user 模块」现有约定)。
- `nexus-forge-file/src/main/java/com/nexusforge/storage/StorageProvider.java` 和 `storage/S3StorageProvider.java` — 存储 SPI + 支撑全部五种厂商配置（`rustfs`/`minio`/`aliyun`/`tencent`/`aws`）的单一 AWS-SDK-v2 实现。
- `nexus-forge-file/src/main/java/com/nexusforge/bootstrap/StorageInitializer.java` — 启动时自动建桶，由 `storage.auto-create-bucket` 控制。
- `nexus-forge-file/src/main/java/com/nexusforge/config/StorageProperties.java` — `storage.*` yaml 绑定。
- `nexus-forge-ai/src/main/java/com/nexusforge/bootstrap/AiAutoConfiguration.java` — Spring Boot 4.x `@AutoConfiguration` 入口。Phase 5 起按阶段装配所有 AI bean：`ApiKeyCipher` → `AiVendorRegistry` → `MethodToolCallbackProvider`(扫 `@Tool` 方法) → `ChatModelRouter`(`Map<String, ChatModel>` 注入) → `LlmClient` → 5 个控制器 → 启动日志。**所有 AI bean 注册都在这里，而不是在 `@Configuration` 类中。**
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/config/AiVendorRegistry.java` — 协议层成员表（`OPENAI_COMPATIBLE_VENDORS`，完整清单见类级 Javadoc：国外官方 + 国内 OpenAI 兼容 + 通用中转）。决定哪些 vendor 走 OpenAI 兼容协议（支持私 Key），url/model/enabled/api-key 在 `application.yaml` 的 `spring.ai.providers.*` 段（Phase 5 统一 schema）。`ProviderPropertiesBridge`（commit 2 落地）自动桥接到对应 starter namespace。
- `nexus-forge-ai/src/main/java/com/nexusforge/config/ProviderPropertiesBridge.java` + `nexus-forge-ai/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` — Phase 5 桥接层。Spring Boot 启动最早阶段把 `spring.ai.providers.<v>.{api-key, base-url, default-model}` 写到 `spring.ai.<starter-ns>.*`，让 Spring AI 各 vendor starter 装配 ChatModel bean 时看到正确 key。`addFirst` 优先级最高，single source of truth 永远赢。
- `nexus-forge-ai/src/main/java/com/nexusforge/router/ChatModelRouter.java` — 回退链主实现。`Map<String, ChatModel>` 按 bean 名（`openAiChatModel` 等）注入，构造时 `normalizeBeanName` 归一化为小写 vendor 名。`resolveWithFallback(prompt)` 按 `spring.ai.fallback-chain` 展开链；`isFallbackTriggering` 白名单 (`LLM_PROVIDER_ERROR` / `LLM_UPSTREAM_TIMEOUT`)；`isPrimaryVendorOpen` 暂恒 false。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/LlmClient.java` — 系统模式（`call(Prompt)` / `call(Prompt, vendor, model)` / `stream(Prompt)` / `stream(Prompt, vendor, model)`）+ 私 Key 模式（`call(Prompt, ChatModel)` / `stream(Prompt, ChatModel)`）的门面。内部用 `callWithToolLoop` 包 Spring AI `DefaultToolCallingManager` 的 tool 回路。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/provider/VendorChatModelFactory.java` — 私 Key 模式动态构造 `OpenAiChatModel`（按 `sha256(apiKey)` 缓存），Anthropic 暂不支持。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/tools/EchoTool.java` — `@Tool` 注解的最小示例工具（`echo(input: String)`）；业务侧加新工具只需 `@Component` + `@Tool` 方法，`AiAutoConfiguration.toolCallbackProvider` bean 加 `.toolObjects(...)` 一行。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/RateLimitGuard.java` — Caffeine 支撑的按用户/IP 限流；私有 Key 路径绕过；命名空间 `ai:rl:` 以区别于 `core/RateLimitAspect` 的 `rl:`。
- `nexus-forge-ai/src/main/java/com/nexusforge/service/QuotaService.java` — 24 小时滑动窗口 token 配额；私有 Key 路径绕过。
- `nexus-forge-ai/src/main/java/com/nexusforge/service/ConversationService.java` — 会话/消息持久化、调 LLM（tool 回路已由 `LlmClient.callWithToolLoop` 内部跑完）、记用量、首轮更新会话模型。
- `nexus-forge-ai/src/main/java/com/nexusforge/service/ContextWindowBuilder.java` — 把 `ai_messages` 行列表按 token 预算截断并转成 Spring AI `List<Message>` 喂给 `Prompt`。
- `nexus-forge-ai/src/main/java/com/nexusforge/client/UsageRecorder.java` — Micrometer 用量计量（4 个 counter：`ai.chat.requests` / `ai.chat.tokens.prompt` / `ai.chat.tokens.completion` / `ai.chat.tokens.total`，`model` + `source` 双标签），同时把 Spring AI `Usage` 写进 `ai_message_usage` 窗口化表。
- `nexus-forge-ai/src/main/java/com/nexusforge/error/LlmErrorMapper.java` — 将 `LlmException` 映射为 JSON `Result`；`GlobalExceptionHandler` 对 `LLM_*` 码委托到此。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/client/ApiKeyCipher.java` — 对 `user_ai_preference.encrypted_api_key` 的 AES-256-GCM 加密；密钥由 `spring.ai.preference.master-key` 派生 → 回退 `jwt.secret`。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/service/PreferenceResolver.java` — 三路偏好解析（`SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`）；强制 `__UNSET__` 哨兵。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/provider/VendorChatModelFactory.java` — 动态构造私有 Key ChatModel，按 `sha256(apiKey)` 缓存。
- `nexus-forge-ai/src/main/java/com/nexusforge/ai/controller/AiPreferenceController.java` 和 `AiAdminController.java` — 用户 `/api/ai/preference` 和管理员 `/api/admin/ai/global-default` REST 端点。
- **AI 网关 Phase 1-8 新增关键文件**:
  - 业务面 Controller: `AiPublicModelController(/api/ai/models)` / `AiUserProxyController(/api/ai/proxy)` / `AiUserAliasController(/api/ai/aliases)` — Phase 1/2/4
  - 管理端 Controller: `AiAdminModelController(/api/admin/ai/models)` / `AiAdminVendorController(/api/admin/ai/vendors + /{vendor}/api-key)` / `AiAdminFallbackChainController(/api/admin/ai/fallback-chain)` / `AiAdminApiKeyAuditController(/api/admin/ai/{vendors/{v}/,}api-key-audit)` — Phase 1/5/6/7/8
  - Service: `ai/service/ModelCatalogService`(模型目录 CRUD) / `ai/service/VendorConfigService`(vendor 配置 + apiKey 5 参 ctor + `setApiKey` / `clearApiKey` 同步发审计事件) / `ai/service/UserAiProxyService` / `ai/service/UserAiModelAliasService` / `ai/service/FallbackChainService` / `ai/service/AiPreferenceService` / `ai/service/PreferenceResolver` — Phase 1-7
  - Provider: `ai/provider/SystemKeyChatModelFactory`(系统 Key 路径 ChatModel 工厂,Phase 5/6 重建 + 事件驱动本地缓存失效) / `ai/provider/VendorChatModelFactory`(私 Key 路径,Phase 1) / `ai/jackson/MessageJacksonDeserializer`(Spring AI `Message` 多态反序列化)
  - Event/Listener(5 对): `ai/event/{ModelCatalog,VendorConfig,UserAiProxy,UserAiModelAlias,FallbackChain}{ChangedEvent,ChangeListener}.java` — Phase 1-7,事件驱动热重建
  - Audit(Phase 8): `ai/audit/VendorApiKeyAuditEvent`(record,不复用 `AuditEvent<A>` 因为 vendor 是 String 不是 userId: Long) + `ai/audit/VendorApiKeyAuditLogger`(`@Component` + 同事务同步写 + 失败 log warn 不阻塞) + `ai/enums/VendorApiKeyAuditAction`(`SET` / `CLEAR`,`READ` / `DECRYPT_FAILED` 留 Phase 9+) + `ai/entity/AiApiKeyAuditLog`(`@JdbcTypeCode(SqlTypes.JSON)` metadata 模式) + `ai/repository/AiApiKeyAuditLogRepository`(`findByMetadataVendorOrderByCreatedAtDesc` 走 JSONB GIN 索引) + `ai/controller/vo/VendorApiKeyAuditLogVo`(`@Builder` + `from(entity)` 从 metadata 抽提 vendor / fingerprintBefore / fingerprintAfter / requestIp 扁平化)
  - 迁移: `V20260902_002__add_ai_model_catalog.sql` / `V20260902_003__add_ai_vendor_config.sql` / `V20260902_004__add_user_ai_proxy.sql` / `V20260902_005__add_user_ai_model_alias.sql` / `V20260902_006__add_ai_vendor_config_api_key.sql` / `V20260902_007__add_ai_fallback_chain.sql` / `V20260902_008__add_ai_api_key_audit_log.sql`
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
- AI 网关默认值：dev profile 启用 `deepseek` 厂商（`openai` 国内不通关掉；`ollama` 默认禁用，需本地推理时开；`qwen` / `dashscope` / `glm` / `minimax` 等国内 vendor 在 `application.yaml` 的 `providers.*` 段默认 `enabled: false`，按需在 dev / prod profile 打开）。`ai_global_default.model` 种子为 `'__UNSET__'`,`vendor` 种子在 V20260902_001 迁移后从 `qwen` 改为 `deepseek`——全新 DB 首次启动后,每个系统模式聊天都会返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`,直到管理员调用 `PUT /api/admin/ai/global-default {vendor, model}`。相应规划首次登录设置。

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
