# Nexus Forge

一个基于 **Java 26 + Spring Boot 4** 与 **Vue 3** 的全栈应用骨架,按业务能力拆分为 Gradle 多模块项目。

> 当前进度: `auth` / `user` / `file` / `core` / `ai` 五个后端模块均已实现; `core` 提供了限流、幂等、请求日志等基础设施; 前端已补齐布局骨架与个人中心页; 接入 Swagger UI 在线文档; `user` 模块已有单元测试覆盖; 认证侧已落地 Token 双轨制(access + refresh)与 Redis 黑名单精确吊销; Docker 编排覆盖 PostgreSQL / Redis / 对象存储(MinIO + RustFS)四栈; **AI 网关已落地 P1–P5(同步 / 流式 / 工具调用 / 偏好解析 / 限流 / 配额 / 用量埋点),`nexus-forge-ai` 不再是 stub**。

---

## 技术栈

### 后端

| 类别 | 技术 |
|------|------|
| 语言 / 运行时 | Java 26 |
| 框架 | Spring Boot 4.1.0、Spring Security、Spring Data JPA |
| 鉴权 | JJWT、Spring Security `OncePerRequestFilter` |
| API 文档 | springdoc-openapi 3.0.3、Swagger UI |
| 数据库 | PostgreSQL(多环境配置 `dev` / `prod`) |
| 缓存 | Redis(用于幂等、限流) |
| 限流 | Bucket4j + Caffeine(本地 Token Bucket) |
| 构建 | Gradle(子模块禁用 `bootJar`,由 `web` 聚合打 fat jar) |
| 工具 | Lombok、Spring Boot DevTools |

### 前端(`nexus-forge-ui`)

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3.5、Vite 7、Vue Router 4 |
| UI | PrimeVue 5 + PrimeVue Forms、PrimeUIX Themes、Tailwind CSS 4 |
| 状态管理 | Pinia 3 + `pinia-plugin-persistedstate`(AES 加密持久化) |
| 网络 | Axios(请求取消、401/403 拦截、413 处理) |
| 校验 | Zod 4 |
| 代码质量 | ESLint 10、vue-tsc |

---

## 模块结构

```
nexus-forge/
├── nexus-forge-web/          # 应用入口,聚合所有子模块,统一配置 + OpenAPI
├── nexus-forge-common/       # 公共基础(Result / ResultCode / BaseEntity / 枚举 / UserPrincipal / File DTO)
├── nexus-forge-core/         # 核心基础设施(限流 / 幂等 / 请求日志 / 全局异常处理)
├── nexus-forge-auth/         # 认证:Spring Security + JWT(登录 / 注册 / 鉴权)
├── nexus-forge-user/         # 用户:实体、注册、资料修改、头像、密码变更(含单元测试)
├── nexus-forge-file/         # 文件:对象存储抽象,支持 MinIO / RustFS / 阿里云 OSS / 腾讯云 COS(均走 S3 协议)
├── nexus-forge-ai/           # AI 网关:ChatModel SPI(OpenAI / Qwen / DeepSeek / Ollama)+ Anthropic skeleton、4 vendor、LlmClient、对话、限流、配额、用量、个性化偏好、私 Key(详见"AI 网关"章节)
├── nexus-forge-visual/       # 可视化(规划中)
└── nexus-forge-ui/           # 前端(Vue 3)
```

### 模块依赖方向

```
web ─┬─► auth ─┬─► common
     ├─► user ─┘
     ├─► core ► common
     ├─► file ► common
     ├─► ai   ─► core, common
     └─► visual ► common
```

`common` 是叶子模块,所有业务模块都可依赖它,但不允许反向依赖。

---

## 目录约定

### 后端(以 `auth` 为例)

```
nexus-forge-auth/src/main/java/com/nexusforge/
├── config/          # Spring 配置(Security / CORS / JwtProperties)
├── controller/      # REST 控制器
├── dto/             # 请求 DTO
├── filter/          # Servlet 过滤器(JWT 鉴权)
├── handler/         # 认证异常处理器(JSON 401/403)
├── security/        # Spring Security 扩展(LoginUser、UserDetailsServiceImpl)
└── util/            # 工具类(JwtUtil)
```

### `core` 模块结构

```
nexus-forge-core/src/main/java/com/nexusforge/
├── NexusForgeCoreAutoConfiguration.java   # spring.factories 自动装配
├── error/          # GlobalExceptionHandler(统一异常处理)
├── log/            # 请求 ID + MDC 链路追踪 + WebLog AOP
├── idempotent/     # @Idempotent 注解 + Redis SET NX EX
└── ratelimit/      # @RateLimit 注解 + Bucket4j Token Bucket
```

### 前端(`nexus-forge-ui/src`)

```
src/
├── api/             # 后端接口封装(auth.ts / user.ts)
├── stores/          # Pinia 状态(auth / layout)
├── router/          # 路由配置(含按 Role 权限守卫)
├── types/           # TS 类型(api.d.ts / models/)
├── views/           # 页面(按业务模块划分子目录)
├── layout/          # 应用布局(AppLayout / AppToolbar / AppSidePanel)
├── components/      # 通用组件(AvatarUploader / ParticleCanvas)
├── composables/     # 组合式函数(useDateFormat)
├── themes/          # PrimeVue 主题定制(颜色 / 组件 / 语义)
├── styles/          # SCSS 设计令牌(基础 / 工具 / 主题)
└── utils/           # 工具函数(http / error)
```

---

## 快速开始

### 环境要求

- JDK 26
- Node.js ≥ 20
- PostgreSQL ≥ 14(本地或 Docker) —— 本仓库提供 `docker/Postgres` 一键编排(`postgres:latest`,端口 5432,库 `nexus-forge`)
- Redis ≥ 7(本地或 Docker) —— 幂等、限流、Token 黑名单都需要 —— 本仓库提供 `docker/Redis` 一键编排(`redis:7-alpine`,端口 6379)
- 对象存储:MinIO / RustFS / 阿里云 OSS / 腾讯云 COS(任选其一,均通过 S3 协议对接;本地推荐 `docker/MinIO` 或 `docker/RustFS` 一键启动)

### 后端启动

1. 起本地依赖(`docker/` 目录提供一栈编排,各服务独立目录可单独启停):

   ```bash
   cd docker/Postgres && cp .env.example .env && docker compose up -d   # PostgreSQL
   cd ../Redis      && cp .env.example .env && docker compose up -d     # Redis
   cd ../RustFS     && cp .env.example .env && docker compose up -d     # 对象存储(RustFS,平迁 MinIO)
   ```

   各编排默认 bind 到 `G:\Volumes\docker\<service>\data`,Windows + Docker Desktop 无需 chown。
2. 修改环境配置(如需):`nexus-forge-web/src/main/resources/application-dev.yaml` 调整数据源与 JWT 配置
3. 启动:

   ```bash
   ./gradlew :nexus-forge-web:bootRun
   ```

默认端口:`8080`,默认 profile:`dev`(可在根 `application.yaml` 修改)。启动后访问:

- API 文档: `http://localhost:8080/swagger-ui/index.html`
- 健康检查: `http://localhost:8080/actuator/health`

### 前端启动

```bash
cd nexus-forge-ui
npm install
npm run dev
```

默认地址:`http://localhost:5173`,通过 Vite 代理转发 `/auth`、`/users` 等到后端 `8080`。

---

## 环境变量

后端配置优先从环境变量读取,**仓库中不包含任何真实凭据**。`application.yaml` 通过 `spring.config.import: optional:file:.env[.properties]` 自动加载仓库根的 `.env`(Spring Boot 3.1+)。

### 本地开发

1. 复制示例并填值:

   ```bash
   cp .env.example .env
   ```

2. 编辑 `.env`,至少修改 `DB_PASSWORD` 与 `JWT_SECRET`。`JWT_SECRET` 至少 32 字节,可用:

   ```bash
   openssl rand -base64 48
   ```

3. 启动后端:`./gradlew :nexus-forge-web:bootRun`

### 必须注入的变量

| 变量 | 说明 | 默认值(dev) |
|------|------|--------------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/nexus-forge?serverTimezone=UTC` |
| `DB_USERNAME` | 数据库账号 | `postgres` |
| `DB_PASSWORD` | 数据库密码 | **无,必须注入** |
| `REDIS_HOST` | Redis 主机(用 `127.0.0.1` 而非 `localhost`,避免 Windows 偶发 IPv6 解析超时) | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码(`docker/Redis` 编排注入) | 留空 |
| `JWT_SECRET` | JWT 签名密钥(≥32 字节) | **无,必须注入** |
| `JWT_ACCESS_TTL_MS` | access Token 有效期(毫秒) | `900000`(15 分钟) |
| `JWT_REFRESH_TTL_MS` | refresh Token 有效期(毫秒) | `604800000`(7 天) |
| `STORAGE_VENDOR` | 存储后端:`rustfs` / `minio` / `aliyun` / `tencent` | `rustfs` |
| `RUSTFS_*` | RustFS 连接参数(endpoint / region / access-key / secret-key / bucket / path-style) | dev 默认 `rustfsadmin/rustfsadmin` 仅供本地 |
| `MINIO_*` | MinIO 连接参数 | dev 默认 `minioadmin/minioadmin` 仅供本地 |
| `ALIYUN_*` / `TENCENT_*` | 阿里云 OSS / 腾讯云 COS | 留空,按需填写 |
| `SPRING_AI_ENABLED` | 总开关,`false` 时 AI 模块所有 Bean 不注册 | `true` |
| `SPRING_AI_PREFERENCE_MASTER_KEY` | 用户私 Key(AES-256-GCM)加密主密钥,base64,推荐 ≥32 字节 | **无,留空时降级用 `JWT_SECRET` 派生**(dev OK,**生产建议显式配置独立密钥**) |
| `SPRING_AI_DEFAULT_VENDOR` | 默认 vendor | `qwen` |
| `SPRING_AI_QWEN_DEFAULT_MODEL` | `qwen` vendor 注册期兜底 default-model(运行期被 `ai_global_default` 表覆盖) | `qwen-turbo` |
| `DASHSCOPE_API_KEY` | 阿里云百炼(DashScope OpenAI 兼容)系统 Key | dev 留空 |

### 生产环境

绝对不要把 `.env` 提交到仓库;通过以下任一方式注入:

- 容器环境变量(`docker run -e` / `k8s envFrom`)
- CI/CD Secret 配合 `envsubst` 渲染 `application-prod.yaml`
- 配置中心(Nacos / Apollo / Spring Cloud Config)

prod profile 下**所有凭据字段**(存储 access-key/secret-key、数据库密码、JWT 签名密钥)均**无默认值**,缺失即启动失败;非凭据字段(endpoint / bucket / region 等)继承基础 `application.yaml` 的 dev 默认值,生产部署时必须用真实值覆盖。

### 凭据轮换

- 怀疑 JWT 泄露 → 重生成 `JWT_SECRET`,所有用户 token 立即失效,需重新登录
- 怀疑数据库泄露 → 立即重置 `DB_PASSWORD`,并在 `application-*.yaml` 中检查是否有样例残留
- 历史清理:仓库早期 commit 含有示例凭据(`VasyaManbo`),**视同已泄露**,必须轮换密码并视情况 `git filter-repo`

### `.gitignore` 规则

以下文件被仓库忽略,**不要**尝试提交它们:

```
.env
.env.*
!.env.example         # 例外:此文件可追踪
**/application-local.yaml
**/application-secret.yaml
```

---

## 已完成功能

### 认证(`nexus-forge-auth`)

- `POST /api/auth/login` — 用户登录,签发 access + refresh 双 Token(`TokenBundle` 返回)
- `POST /api/auth/refresh` — 用 refresh Token 换发新 access + refresh,旧 refresh 加入黑名单
- `POST /api/auth/logout` — 登出:access Token 写入 Redis 黑名单(TTL = 剩余有效期),refresh 版本号失效使历史 refresh 全部失效
- `JwtAuthenticationFilter` — 解析请求头 `Authorization: Bearer <token>`,校验黑名单,构建 `SecurityContext`
- `SecurityConfig` — 路由级权限控制(`/api/auth/login|register|refresh` 放行)、CORS 配置
- `JsonAuthHandlers` — JSON 格式的 401/403 响应
- `UserPrincipal` — `record(userId, username)`,作为 `Authentication.principal` 在 `SecurityContext` 中传递
- Token 双轨制:access 走业务接口,refresh 走刷新接口;`typ` 字段区分,防 refresh 滥用
- Redis 黑名单(`auth:blacklist:{jti}`)+ refresh 版本号(`auth:refresh:{userId}`),精确吊销与单点登录

### 用户(`nexus-forge-user`)

- `User` 实体:账号、邮箱、加密密码、昵称、头像、手机号、状态(`ACTIVE` / `DISABLED`)、角色集合、`lastLoginAt`
- `GET /api/users/me` — 基于 `@AuthenticationPrincipal UserPrincipal` 拉取当前登录用户信息
- `PATCH /api/users/me` — 修改昵称/邮箱/手机号(邮箱去重排除自身)
- `POST /api/users/me/password` — 修改密码(校验旧密码,新旧不可相同)
- `POST /api/users/me/avatar` — 上传头像(对接文件模块,自动清理旧文件)
- `DELETE /api/users/me/avatar` — 删除头像(恢复默认)
- 单元测试:注册、更新资料、修改密码 三个 Service 核心路径覆盖

### 文件(`nexus-forge-file`)
- `StorageProvider` — 统一存储接口(upload / download / delete / presignedUrl / exists)
- `S3StorageProvider` — S3 兼容协议实现,支持 MinIO / RustFS / 阿里云 OSS / 腾讯云 COS / AWS S3(共享同一套 AWS SDK v2 客户端)
- `StorageProperties` — 多厂商配置绑定(endpoint / bucket / access-key / secret-key / region / path-style);`storage.vendor` 支持 `minio` / `rustfs` / `aliyun` / `tencent` / `aws`
- `FileController` — 单/多文件上传、下载、删除、批量删除、预签名 URL(PUT/GET)、分片上传(初始化 / 预签名分片 / 完成合并)
- `FileService` — 文件业务逻辑(命名策略、类型过滤)
- `FileClientImpl` — 业务侧门面(供用户头像等场景调用)

### 核心基础设施(`nexus-forge-core`)

- **限流**: `@RateLimit` 注解 + SpEL key + Bucket4j 本地 Token Bucket(Caffeine 缓存)
- **幂等**: `@Idempotent` 注解 + SpEL key + Redis SET NX EX + SHA-256 哈希
- **请求日志**: `RequestIdFilter` 生成 `X-Trace-Id` 写入 MDC,记录访问日志
- **Web 日志 AOP**: `WebLogAspect` 记录 Controller 执行耗时
- **全局异常处理**: `GlobalExceptionHandler` 统一拦截业务异常、校验异常、404、文件过大、通用错误

### API 文档

- springdoc-openapi 3.0.3 集成,Swagger UI 挂 `/swagger-ui/index.html`
- 全局 JWT Bearer 认证方案(可在 UI 中一键填入 Token)
- 按业务分组:`public`(/auth/**)、`user`(/users/**)、`file`(/files/**)
- 三个 Controller 全部标注 `@Tag` / `@Operation` / `@ApiResponses`,DTO/VO 标注 `@Schema`

### 公共(`nexus-forge-common`)

- `Result<T>` — 统一响应包装(`code` / `message` / `data`)
- `ResultCode` — 业务错误码(`USER_NOT_FOUND`、`USER_ALREADY_EXISTS`、`EMAIL_ALREADY_EXISTS` ...)
- `Role` — `USER` / `ADMIN`,内置 Spring Security `authority`
- `UserStatus` — 用户状态枚举
- `BaseEntity` — 实体基类(`createdAt` / `updatedAt`)
- 异常体系:`BaseException` → `BusinessException` / `AuthException` / `LlmException`(AI 网关专用),由 `GlobalExceptionHandler` 统一处理
- AI 网关错误码同处 `ResultCode`(`LLM_CONFIG_MISSING` / `LLM_MODEL_NOT_FOUND` / `LLM_PROVIDER_ERROR` / `LLM_UPSTREAM_TIMEOUT` / `LLM_RATE_LIMITED` / `LLM_QUOTA_EXCEEDED` / `LLM_ALL_VENDORS_FAILED` / `LLM_CIRCUIT_OPEN` / `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED`)
- 文件 DTO: `FileClient` / `FileMeta` / `FileBizType` / `FileAccess` / `UploadCredential`

### AI 网关(`nexus-forge-ai`)

- **`ChatModel` SPI** + 4 vendor:`OpenAiChatModel`(官方) / `DeepSeekChatModel` / `QwenChatModel`(DashScope OpenAI 兼容) / `OllamaChatModel`,全部继承 `OpenAiCompatibleChatModel`;`AnthropicChatModel` skeleton(独立协议)
- **`LlmClient` 门面**:`call(req)` / `call(req, vendor, model)` / `stream(req)` / `stream(req, vendor, model)`。系统模式走 `ChatModelRouter` 降级链 + 熔断;私 Key 模式绕过降级链、quota、IP 限流
- **`PreferenceResolver` 三态分流**:`SYSTEM`(全局默认 + 系统 Key) / `USER_OVERRIDE_SYSTEM_KEY`(用户设了 vendor/model 仍用系统 Key) / `USER_PRIVATE_KEY`(用户填私 Key)。优先级链路:请求 model → 用户偏好 → 全局默认 → yaml
- **`VendorChatModelFactory`**:按 `sha256(apiKey)` 缓存动态构造的 ChatModel,避免重复创建,内部用 `static final` 子类规避 inner-class 限制
- **`ConversationService`**:`sendMessage` 持久化 user+assistant,首条 user 自动更新对话标题与 model,记录工具调用,落 `ai_message_usage` 用量
- **`RateLimitGuard`**(Caffeine 本地 Token Bucket,user + IP 维度,私 Key 跳 IP)
- **`QuotaService`**:24h 滑窗 token / request 计数,支持 per-user override,私 Key 跳过平台 quota
- **`UsageRecorder`** + Micrometer 埋点:`ai.llm.requests` / `ai.llm.tokens.{prompt,completion,total}` 计数器,按 vendor / model / source 维度拆 tag
- **REST 接口**:
  - `POST /api/ai/chat`(同步) / `POST /api/ai/stream`(SSE)
  - `POST /api/ai/conversations` / `GET /api/ai/conversations` / `GET /api/ai/conversations/{id}/messages`
  - `GET /api/ai/preference` / `PUT /api/ai/preference` / `DELETE /api/ai/preference`(用户偏好;vendor/model 可选 + 私 Key 明文加密入库)
  - `GET /api/admin/ai/global-default` / `PUT /api/admin/ai/global-default`(`@PreAuthorize("hasRole('ADMIN')")`)
  - `GET /api/ai/usage`(24h 用量摘要)
- **首次启动行为**:`ai_global_default.model` 种子为 `'__UNSET__'`(sentinel),所有 system-mode 请求返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`,直到 admin 调 `PUT /api/admin/ai/global-default` 设置真值

### AI 数据库迁移(`nexus-forge-user` Flyway)

Spring Boot 4.1 已移除 `spring-boot-flyway` 自动装配;`FlywayMigrationRunner`(`@Component` + `@PostConstruct`)替代,在 JPA 启动前跑迁移,读 `spring.flyway.{enabled, locations, baseline-on-migrate, validate-on-migrate}`。校验失败(checksum mismatch)自动 `flyway.repair()` 后重跑。

AI 相关表(在 `nexus-forge-user/src/main/resources/db/migration/`):

- `V20260801_001__add_ai_global_default.sql` — `ai_global_default` 单行表(`id=1 CHECK (id=1)`,`model='__UNSET__'` 种子)
- `V20260801_002__add_user_ai_preference.sql` — `user_ai_preference` per-user 表,`encrypted_api_key BYTEA`(AES-256-GCM 密文)+ `api_key_fingerprint VARCHAR(16)`(明文 Key 指纹展示)
- 老 SQL 加 `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... $$` 包装,容忍 JPA `ddl-auto=update` 已经建过表的现状

### 前端

- 路由守卫 + 鉴权拦截(401 → 重定向登录,403 → Toast)
- Pinia `auth store`,token + `userInfo` AES 加密持久化
- 登录 / 注册页(Zod 校验,PrimeVue Forms,粒子背景)
- 应用布局: `AppLayout`(顶栏 + 侧边栏 + 内容区),响应式适配
- `AppToolbar`: 用户头像下拉菜单(个人中心 / 修改密码 / 退出登录)
- `AppSidePanel`: 侧边栏导航(折叠 / 展开,路由高亮)
- 个人中心页 `/profile`: 基础信息(BasicPanel)、联系方式(ContactPanel)、通知偏好(NotificationPanel)、安全设置(SecurityPanel)
- `AvatarUploader`: 头像上传组件(预览遮罩,<=2MB 校验)
- 主题定制: PrimeVue 5 深色/浅色主题 + SCSS 设计令牌系统
- 请求层: Axios 封装(请求取消、401/403/413 拦截、token 注入)

---

## API 约定

- 所有业务接口统一返回 `Result<T>`:

  ```json
  {
    "code": 0,
    "message": "success",
    "data": { ... }
  }
  ```
- 受保护接口需携带请求头:`Authorization: Bearer <token>`
- 业务错误码集中在 `ResultCode`,前端根据 `code` 判断具体业务结果
- 详细 API 文档请访问运行中服务的 `/swagger-ui/index.html`

---

## 待开发


- [x] `nexus-forge-ai`:LLM 调用网关、流式响应 — 见"AI 网关"章节
- [ ] `nexus-forge-ai`:向量检索 / RAG(Function Calling 已落地,工具执行 + 重入 LLM 在 Step 12+ 跟进)
- [ ] `nexus-forge-visual`:图表 / 看板 / 大屏组件
- [x] 后端:Token 刷新(`POST /auth/refresh`)、登出黑名单 — `6e43be9`
- [ ] 后端:`JwtAuthenticationFilter` 改为从 Redis 读权限(避免 Token 膨胀,当前角色直接写 claims)
- [ ] 后端:密码重置(邮箱验证码)、第三方登录
- [ ] 前端:业务首页(`/home`)真实数据、权限路由(基于 Role)
- [ ] 前端:i18n 国际化(中文 / 英文)
- [ ] 集成测试:auth + user + file 端到端
- [x] Docker Compose 一键起 PostgreSQL / Redis — `e35aa5a` / `1c7721c`(对象存储:`docker/MinIO`、`docker/RustFS`)
- [ ] GitHub Actions CI(lint + test + build)

---

## 开发约定

### 提交规范

遵循 Conventional Commits 风格,提交信息用中文简述:

```
feat(auth): 实现 JWT 登录认证与权限校验
fix(user): 修复用户名重复校验逻辑
refactor(common): 抽取统一异常处理架构
```

`type` 可选:`feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `style`。

### 分支策略

- 主开发分支:`dev-01`
- 功能开发:从 `dev-01` 切出 `feat/<module>-<feature>`,完成后合并回 `dev-01`

### 代码风格

- 后端:启用 Lombok;Controller / Service / Repository 分层;公共枚举与 DTO 优先下沉到 `common`
- 前端:API 调用集中在 `src/api/`,组件 props 使用 TS 类型,表单用 Zod 定义 schema
