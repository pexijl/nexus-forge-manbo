# Roadmap

> 与 `README.md` 中的"待开发"清单联动。本文件按模块拆分,每条都是一条独立的待办。
> 完成后将 `[ ]` 改成 `[x]`;若发现范围偏差,直接编辑本文件,不要改 README。
>
> 重要:**AGENTS.md 是项目内"事实来源"**(代码约定的实时说明),本文件只是 backlog,不是实施规范。

---

## 状态图例

- `[ ]` 待开始
- `[~]` 进行中
- `[x]` 已完成
- `[!]` 阻塞 / 需要决策

## 优先级

- 🔥 高 —— 当前冲刺,优先完成
- 🟡 中 —— 有空就做
- 🟢 低 —— 长期 / 有时间再说

---

## 🔥 当前冲刺(2026-09-02)

> 上一轮冲刺(07-07)的对象存储 / 限流 / 幂等 / 个人中心 / Swagger 已全部完成,
> 后续接续 P2(账号生命周期 / 操作审计 / 文件元数据 / 分布式锁 / 密码重置)
> 和 AI 网关 Phase 0-8 全部完成,本轮聚焦**前端 AI 业务接入 + admin UI 集成 + 跨实例缓存同步**。

### ✅ 上一轮已完成(2026-08-29 ~ 2026-09-02)

- [x] 账号生命周期:`AccountLifecycleService` + PII 不可逆擦除 + GDPR 跨模块真删(ai / file 监听 `UserDataDeletionEvent`)
- [x] 账号审计:`account_lifecycle_log` + `AccountLifecycleAuditLogger` 写表(无 FK,合规追溯)
- [x] HTTP 操作审计:`@Audited` + `AuditAspect` + `operation_audit_log`(15 列 + 3 索引,只追加)+ `/api/admin/audit-logs` 分页查询
- [x] 文件元数据落库:`file_metadata` 三态(PENDING / ACTIVE / DELETED)+ 上传 / confirm / 我的 / 软删 / admin 视图
- [x] 分布式锁 SPI:`DistributedLock` + `RedisDistributedLock` + `DistributedLockTemplate`(已用 FileService.uploadByBiz / AccountLifecycleService.requestDeletion)
- [x] 密码重置:`/api/auth/password/reset/{request,confirm}` 邮箱验证码 + 限流 + 防枚举
- [x] 角色从 Redis 读(JwtAuthenticationFilter → UserRoleProvider,避免 token 膨胀)
- [x] AI 网关 Phase 0-8 全部完成 — 模型目录 / vendor base_url / 用户 BYOK / 用户 model alias / 用户代理 / 系统 apiKey DB 化 / 降级链 DB 化 / apiKey 轮换审计,全部走 DB + 事件驱动热重建 + 审计
- [x] Flyway 集成:`FlywayMigrationRunner`(`@Component` + `@PostConstruct`)替代 Spring Boot 4.1 缺失的自动装配;checksum 不匹配自动 `repair()`

### 🔥 本轮待办(2026-09-02 ~)

- [ ] **前端 AI 业务接入**:`src/api/ai.ts` + 聊天页(SSE 流式)+ 偏好页 + 管理员全局默认 / vendor 配置 / 模型目录 / 审计页
- [ ] admin UI 集成 apiKey 轮换审计 + 模型目录 CRUD + vendor 配置 CRUD
- [ ] 路由级 Role 守卫(`router/index.ts` 按 `Role` 过滤菜单)
- [ ] `/home` 业务首页真实数据(当前为占位)
- [ ] Lint 当前错误清零(去掉 CI `continue-on-error: true`)
- [ ] Jacoco 后端覆盖率(暂未配,等业务稳定)

---

## nexus-forge-common

- [x] `Result.success(String, T)` 与 `Result.fail(String)` 中的 magic number 替换为 `ResultCode` 常量
- [x] 抽取 `PageResult<T>` 统一分页响应(列表接口统一 `Result<PageResult<T>>`)
- [x] 抽取 `BaseEntity` 软删除字段(`deletedAt: OffsetDateTime`)+ `@SQLDelete` / `@SQLRestriction` 约定
- [x] 通用审计接口 `AuditEvent<A>` / `AuditLogger<A>`(供各模块复用)
- [x] 跨模块事件 `UserBannedEvent` / `UserDataDeletionEvent`(解耦 user 与 file / ai)
- [x] 邮件 SPI `EmailSender` + `LoggingEmailSender` / `SmtpEmailSender` 模式互斥

## nexus-forge-core

- [x] 请求日志中间件(请求 ID + MDC 链路追踪) — `RequestIdFilter` + `WebLogAspect`
- [x] 统一幂等组件(`@Idempotent` 注解 + Redis SET NX EX) — `IdempotentAspect` + `RedisIdempotentStore`
- [x] 限流组件(基于 Bucket4j + Caffeine Token Bucket) — `@RateLimit` + `TokenBucketRateLimiter`
- [x] 分布式锁 SPI + Redis 实现 — `DistributedLock` + `RedisDistributedLock` + `DistributedLockTemplate`(5 API: tryLock / tryLockOrThrow / tryLockWithWait / lock / runWithLock)
- [x] HTTP 操作审计 — `@Audited` + `AuditAspect` + `operation_audit_log` + `/api/admin/audit-logs` 分页
- [x] 全局异常处理(`@RestControllerAdvice`)— 业务异常 + 校验异常 + 404 + 413 + `AccessDeniedException` → 403 + 1005
- [ ] Jacoco 覆盖率(留待业务稳定后)

## nexus-forge-auth

- [x] 用户注册(`POST /api/auth/register`)
- [x] 用户登录 / JWT 签发(`POST /api/auth/login`,access + refresh 双 Token)
- [x] `JwtAuthenticationFilter` 解析 Token 写 `SecurityContext`(已校验 Redis 黑名单)
- [x] `UserPrincipal` 统一认证主体
- [x] JSON 格式 401/403 响应(`JsonAuthHandlers`)
- [x] Token 刷新机制(`POST /api/auth/refresh`)— `6e43be9`
- [x] 登出 / Token 黑名单(`POST /api/auth/logout`)— `6e43be9`
- [x] 从 Redis 读角色与权限(`UserRoleProvider` + `auth:roles:{userId}`,TTL 5min,evict 链路就绪)— 避免 Token 膨胀
- [x] 密码重置(`POST /api/auth/password/reset/{request,confirm}`,邮箱 6 位验证码 + 限流 + 防枚举)
- [ ] 第三方登录(预留扩展点)

## nexus-forge-user

- [x] `User` 实体 + `BaseEntity` 继承 + 软删
- [x] 用户注册服务
- [x] 当前用户查询(`GET /api/users/me`)
- [x] 修改个人资料(`PATCH /api/users/me`)
- [x] 修改密码(`POST /api/users/me/password`)
- [x] 头像上传 / 删除(`POST /api/users/me/avatar` + `DELETE /api/users/me/avatar`)
- [x] 单元测试(注册 / 更新资料 / 修改密码 / 实体默认值)
- [x] 账号生命周期 — `AccountLifecycleService` + PII 不可逆擦除(`AccountAnonymizer`)
  - 用户端点:`POST /api/users/me/delete/{request,confirm,restore}`(邮件验证码 + 一次性 restore token 14 天)
  - 管理端点:`POST /api/admin/users/{id}/{ban,unban}` + `GET /api/admin/users/{id}/lifecycle` + `GET /api/admin/users/lifecycle?action=BAN`
  - `UserDataDeletionEvent` 解耦跨模块真删(ai / file 监听真删)
- [x] 账号审计表 `account_lifecycle_log`(无 FK,合规追溯)+ `AccountLifecycleAuditLogger`
- [x] 角色 provider `UserRoleProvider`(auth 模块消费)+ 配额 provider `UserQuotaProviderImpl`(ai 模块消费)

## nexus-forge-file

### 架构决策

**统一使用 AWS S3 SDK 作为基础抽象** —— MinIO、阿里云 OSS、腾讯云 COS 均提供 S3 兼容接口,可通过 `endpoint` 切换实现零成本迁移。仅在需要厂商特有能力(如 OSS 图片处理回调)时再额外接入官方 SDK。

- **开发**:MinIO(本地 Docker,`docker/MinIO`)/ RustFS(Apache 2.0 平迁备选,`docker/RustFS`)二选一 —— S3 SDK 接入,Console Web UI(`:9001`)调试
- **测试**:任意 S3 兼容服务 —— Ceph RGW / SeaweedFS / 自建 MinIO
- **生产**:阿里云 OSS / 腾讯云 COS / 自选 —— 优先走 S3 兼容模式,endpoint 切换

### StorageProvider SPI

- [x] 定义 `StorageProvider` 接口:`upload` / `download` / `delete` / `presignedUrl` / `exists`
- [x] 通过 `@ConfigurationProperties(prefix = "storage")` 选择实现
- [x] 统一配置:`endpoint` / `bucket` / `access-key` / `secret-key` / `region` / `path-style`

### MinIO / RustFS 开发环境(本地)

- [x] Docker Compose 启动 MinIO / RustFS(API `:9000` + Console `:9001`)
- [x] `S3StorageProvider` 实现(基于 `software.amazon.awssdk:s3`,兼容 MinIO / RustFS / 阿里云 / 腾讯 / AWS)
- [x] `application-dev.yaml` 完整配置示例
- [x] `StorageProvider#createBucket` 已实现 + `StorageInitializer` 启动钩子(`storage.auto-create-bucket` 控制,三层兜底)

### 生产环境(阿里云 OSS / 腾讯云 COS)

- [x] 阿里云 OSS 适配:`endpoint = https://oss-cn-<region>.aliyuncs.com`
- [x] 腾讯云 COS 适配:`endpoint = https://cos-<region>.tencentcos.cn`
- [x] `application-prod.yaml` 配置模板(各 provider 切换示例)
- [ ] 若使用厂商特有能力,再单独接入官方 SDK(图片处理 / 回调 / 视频转码)

### 通用能力

- [x] `POST /api/files/upload` 通用上传接口(单文件 / 多文件,必填 biz)
- [x] 分片上传 / 秒传(基于文件 hash)/ 断点续传
- [x] 文件访问权限与临时签名 URL(presigned PUT + GET)
- [x] `FileClientImpl` 业务门面(供头像等场景调用)
- [x] `file_metadata` 三态落库(PENDING / ACTIVE / DELETED)+ 软删 + 我的文件 + admin 视图
- [x] GDPR 真删闭环:`FileUserDataDeletionListener` 监听 `UserDataDeletionEvent` 物理删 DB + 清对象存储
- [x] 分布式锁防并发上传(`DistributedLock` 锁 `upload:<biz>:<userId>`)
- [x] HTTP 操作审计(`@Audited` 在 `file.upload` / `file.delete`)
- [ ] 图片处理(缩略图、水印)
- [ ] 文件类型白名单 / 安全校验

## nexus-forge-ai(Phase 0–8 全部完成)

> Spring AI 2.0 官方 starter 网关,`@AutoConfiguration` 入口在 `bootstrap/AiAutoConfiguration`。
> 自实现 ChatModel / 流解析器 / JsonMapper / SSE helper / Tool SPI 已全部下线(spring-ai-full-migration)。

### 业务面(用户端点)

- [x] 同步对话 `POST /api/ai/chat`
- [x] SSE 流式 `POST /api/ai/chat/stream`(`StreamingResponseBody`,单次 `Flux.subscribe` 防双重订阅)
- [x] 对话 CRUD:`POST /api/ai/conversations` / `GET /api/ai/conversations`(分页)/ `GET /api/ai/conversations/{id}`(消息列表在 `ConversationDetailVo` 内)/ `PATCH /{id}`(改标题 / pin)/ `DELETE /{id}`(软删)
- [x] 发送消息 `POST /api/ai/conversations/{id}/messages`(触发 LLM 调用 + 自动更新会话 model)
- [x] 用户偏好 `GET/PUT/DELETE /api/ai/preference`(vendor / model / 私 Key 明文入库 AES-256-GCM 加密)
- [x] 用户代理 `GET/PUT/DELETE /api/ai/proxy`(vendor + base_url + model)
- [x] 用户 model alias `GET/POST/PUT/DELETE /api/ai/aliases`(短名 → 真实 model)
- [x] 24h 用量摘要 `GET /api/ai/usage`(`AiUsageController`)
- [x] 公开模型目录 `GET /api/ai/models`(按用户可见 vendor 过滤)

### 管理端(`@PreAuthorize("hasRole('ADMIN')")`)

- [x] 全局默认 `GET/PUT /api/admin/ai/global-default`(`ai_global_default` 单行表,`__UNSET__` 哨兵)
- [x] 模型目录 CRUD `GET/POST/PUT/DELETE /api/admin/ai/models`(`ai_model_catalog` + JSONB capabilities)
- [x] vendor 配置 CRUD `GET/POST/PUT/DELETE /api/admin/ai/vendors`(`ai_vendor_config` enabled / base_url / 模型白名单)
- [x] vendor 系统 apiKey `GET/PUT/DELETE /api/admin/ai/vendors/{vendor}/api-key`(`ai_vendor_config.encrypted_api_key` AES-256-GCM 加密 + fingerprint 展示)
- [x] 降级链策略 `GET/PUT/DELETE /api/admin/ai/fallback-chain`(`ai_fallback_chain` JSONB 单行,事件驱动 `ChatModelRouter` 重载)
- [x] apiKey 轮换审计 `GET /api/admin/ai/vendors/{vendor}/api-key-audit?page=&size=` + `GET /api/admin/ai/api-key-audit?page=&size=`(`ai_api_key_audit_log` + JSONB GIN 索引,记录 action / actor / ip / 改前改后 fingerprint)

### 核心能力(按 Phase 拆分)

- [x] **Phase 0**: `ai_global_default` 单行表 + `__UNSET__` 哨兵 + `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)` 错误码
- [x] **Phase 1**: 用户 BYOK `user_ai_preference` + AES-256-GCM + `api_key_fingerprint` + `VendorChatModelFactory`(按 `sha256(apiKey)` 缓存动态构造 `OpenAiChatModel`);Anthropic 私 Key 模式占位 throw `LLM_INVALID_REQUEST`
- [x] **Phase 2**: 按用户代理 `user_ai_proxy` + `AiUserProxyService` + `UserAiProxyChangedEvent` + `UserAiProxyChangeListener`
- [x] **Phase 3**: 三态偏好解析 `PreferenceResolver`(`SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`)+ 优先级链路:请求 model → 用户偏好 → 全局默认 → yaml
- [x] **Phase 4**: 按用户 model alias `user_ai_model_alias` + `UserAiModelAliasService` + `LLM_MODEL_ALIAS_NOT_FOUND (3xxx)` 错误码
- [x] **Phase 5**: vendor base_url DB 化 `ai_vendor_config` + `SystemKeyChatModelFactory` 重建 ChatModel + `VendorConfigChangedEvent` + `VendorConfigChangeListener` 热失效本地缓存
- [x] **Phase 6**: vendor 系统 apiKey DB 化 `ai_vendor_config.encrypted_api_key` + `api_key_fingerprint`;yaml 仅做启动兜底;私 Key 路径仍走 `user_ai_preference`;`LLM_CONFIG_MISSING` 缺 base-url / default-model 立即 fail-fast
- [x] **Phase 7**: 降级链策略 DB 化 `ai_fallback_chain` JSONB 单行 + `FallbackChainService` + `FallbackChainChangedEvent` + `FallbackChainChangeListener` 事件驱动 `ChatModelRouter` 重载;`isPrimaryVendorOpen` 暂恒 false(Spring AI retry / Resilience4j 留后续)
- [x] **Phase 8**: apiKey 轮换审计 `ai_api_key_audit_log` + JSONB GIN 索引(4 索引:`created_at` / `action` / `actor_id` / `(metadata->>'vendor')` GIN)+ `VendorApiKeyAuditLogger`(`@Component` + 同事务同步写 + 失败 log warn 不阻塞);action enum `SET` / `CLEAR`(`READ` / `DECRYPT_FAILED` 留 Phase 9+)

### Spring AI 2.0 集成

- [x] 3 个官方 starter(`spring-ai-starter-model-openai` / `anthropic` / `ollama`);`AiAutoConfiguration.chatModelRouter(Map<String, ChatModel>)` 按 bean 名归一化为小写 vendor 名
- [x] `ProviderPropertiesBridge`(`EnvironmentPostProcessor`,`addFirst` 优先级)把 `spring.ai.providers.*` 桥到 `spring.ai.<starter-ns>.*`
- [x] `ChatModelRouter` aliasing:OpenAI 兼容 vendor(`deepseek` / `dashscope` / `glm` / `kimi` / `doubao` / `hunyuan` / `siliconflow` / `oneapi` / `openrouter` / `minimax`)路由到 `openAiChatModel` bean
- [x] 国内 vendor 覆盖:阿里通义 `dashscope` / 智谱 `glm` / 月之暗面 `kimi` / 字节 `doubao` / 腾讯 `hunyuan` / 通用中转 `siliconflow` / `oneapi` / `openrouter` / `minimax`
- [x] Spring AI 强类型 `ChatOptions` 通过 `mutate()` 重建,绝不直接改动上游载荷
- [x] Tool 回路:`@Tool` 注解 + `MethodToolCallbackProvider` + `DefaultToolCallingManager`;`AiProperties.maxToolTurns` 兜底,默认 3

### 横切能力

- [x] `RateLimitGuard`(Caffeine 本地 Token Bucket,user + IP 维度,私 Key 跳 IP;键前缀 `ai:rl:` 与 core `rl:` 隔离)
- [x] `QuotaService` 24h 滑窗 token / request 计数,支持 per-user override,私 Key 跳过平台 quota
- [x] `UsageRecorder` + Micrometer 埋点(`ai.chat.requests` / `ai.chat.tokens.{prompt,completion,total}`,`model` + `source` 双标签)
- [x] `ConversationService` 持久化 user/assistant,首轮更新会话 model,`ai_message_usage` 窗口化表
- [x] `ContextWindowBuilder` 把 `ai_messages` 按 token 预算截断并转成 Spring AI `List<Message>` 喂给 `Prompt`
- [x] GDPR 真删:`AiUserDataDeletionListener` 监听 `UserDataDeletionEvent` 真删 ai_conversations / messages / usage
- [x] LLM 错误码:`LLM_CONFIG_MISSING` / `LLM_MODEL_NOT_FOUND` / `LLM_PROVIDER_ERROR` / `LLM_UPSTREAM_TIMEOUT` / `LLM_RATE_LIMITED` / `LLM_QUOTA_EXCEEDED` / `LLM_ALL_VENDORS_FAILED` / `LLM_CIRCUIT_OPEN` / `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED` / `LLM_MODEL_ALIAS_NOT_FOUND`

### Phase 9+ 候选

- [ ] apiKey 轮换审计扩展:`READ` / `DECRYPT_FAILED` action(只读路径的审计留痕,合规深挖)
- [ ] Anthropic 私 Key 模式(`VendorChatModelFactory` 当前显式 throw `LLM_INVALID_REQUEST`)
- [ ] Ollama 系统 Key 路径热重建(Phase 5 / 6 局限延续,本地推理场景)
- [ ] Redis pub/sub 跨实例 vendor config / fallback chain / apiKey 热失效(当前每实例本地缓存)
- [ ] `X-Forwarded-For` 解析(反代部署场景,Phase 8 暂拿 `getRemoteAddr()`)
- [ ] admin UI 集成(apiKey 轮换审计 + 全局默认 + 模型目录 + vendor 配置 + 降级链可视化编辑)
- [ ] LLM 调用"最近 50 次"管理面板(`AiMessageUsageRepository` 查询方法已就绪)
- [ ] 向量检索 / RAG(embedding + 向量库,长期项)
- [ ] Anthropic Messages API tool_use / Anthropic 端 tool 回路(目前 OpenAI 协议家族跑通)

## nexus-forge-visual

- [ ] 通用图表组件(折线 / 柱状 / 饼图,基于 ECharts 或 Chart.js)
- [ ] 看板 / Dashboard 布局组件
- [ ] 大屏适配(1920×1080 + 比例缩放)
- [ ] 实时数据推送(SSE)

## nexus-forge-ui

- [x] 应用骨架(路由 + 布局 + 全局样式)
- [x] 主题(PrimeVue 5 + Tailwind + SCSS 设计令牌)
- [x] 登录 / 注册页 + Zod 校验(粒子背景)
- [x] Pinia `auth store` + AES 加密持久化
- [x] Axios 封装(请求取消 / token 注入 / 401/403/413 拦截)
- [x] 路由过渡动画(路由级 `meta.transition`)

### AppLayout 布局骨架

- [x] 整体三栏布局(Sidebar + HeaderBar + Main)
- [x] HeaderBar 全局操作区:用户头像下拉(个人中心 / 退出登录)
- [x] Main 内容区:路由切换过渡动画(Fade / Slide)
- [ ] 🟡 Sidebar 侧边栏:Logo + 系统名 + 主导航菜单(支持折叠 / 展开)
- [ ] 🟡 Sidebar 折叠状态持久化(Pinia + localStorage,刷新后保留)
- [ ] 🟡 Sidebar 当前路由高亮 + 嵌套子菜单(最多两级)
- [ ] 🟡 Sidebar 按 `Role` 过滤菜单项
- [ ] 🟡 HeaderBar 面包屑导航(基于当前路由)
- [ ] 🟡 HeaderBar 主题切换按钮(明 / 暗)
- [ ] 🟡 HeaderBar 通知中心(预留接口)
- [ ] 🟡 响应式适配:窄屏(<768px)自动收起 Sidebar 为抽屉
- [ ] 🟡 Layout 状态 store(`useLayoutStore`: sidebarCollapsed、theme、breadcrumb)

### 业务页与权限

- [x] 个人中心页(`/profile` — 4 个子面板:基础/联系/通知/安全)
- [x] 头像上传组件(`AvatarUploader` + 后端对接)
- [ ] 🟡 业务首页(`/home`)占位 → 真实数据
- [ ] 🟡 路由级权限控制(基于 `Role` 过滤菜单与跳转)
- [x] ✅ 401 / 403 统一处理(Axios 拦截器 + `auth:expired` 事件)
- [ ] 🟡 全局错误边界 + Loading 状态
- [ ] 🟢 国际化(i18n,中文 / 英文)
- [ ] 🔥 **AI 业务页**:`/ai/chat`(同步 + SSE 流式)+ `/ai/preference`(偏好 / 私 Key)+ admin `/ai/admin/*`(全局默认 / 模型目录 / vendor 配置 / 降级链 / 审计)

### 文档 / 基础设施

- [x] OpenAPI / Swagger 文档接入(springdoc-openapi 3.0.3, `/swagger-ui/index.html`)
- [x] 全局统一异常响应(`GlobalExceptionHandler` + `Result<T>`)
- [x] 单元测试: 后端 381 case,8 预存失败(7 个 `AiMessageUsageRepositoryTest` 需真实 DB + 1 个 `ConversationServiceTest` Spring AI 2.0 metadata 假设,与本路线无关)
- [x] 集成测试: 13 IT(端到端覆盖 auth / user / file / ai,Testcontainers 真 PG / Redis / RustFS)— `AuthRegisterLoginIT` / `AuthRefreshIT` / `UserProfileIT` / `FileUploadIT` / `AiChatIT` / `AiStreamIT` / `AiQuotaIT` / `AiRateLimitIT` / `FallbackIT` / `ConversationIT` / `UsageEndpointIT` / `ApplicationMetricsIT` / `HealthIT`
- [x] Docker Compose 一键起 PostgreSQL(`docker/Postgres`)+ Redis(`docker/Redis`)+ 对象存储(`docker/MinIO` / `docker/RustFS`)
- [x] GitHub Actions CI — `.github/workflows/ci.yml` 后端 `./gradlew --no-daemon clean build` + 前端 `npm install --no-audit --no-fund && npm run lint && npm run build`(lint `continue-on-error: true`,待现有错误清零后再移除)
- [ ] 日志规范(SLF4J + Logback,JSON 结构化输出)
- [ ] 请求 / 响应日志脱敏(手机 / 邮箱 / Token)

---

## 来源

- `README.md`「待开发」章节
- `docs/NEXT-STEPS.md` 近期优先级
- `AGENTS.md` 项目内事实来源(实施规范)
- 代码内 `// TODO` 扫描
- git log 已完成功能回溯
