# Nexus Forge

一个基于 **Java 26 + Spring Boot 4** 与 **Vue 3** 的全栈应用骨架,按业务能力拆分为 Gradle 多模块项目。

> 当前进度(2026-09-02): 后端 5 个业务模块(`auth` / `user` / `file` / `ai`) + 2 个基础设施(`common` / `core`)全部就绪。核心闭环:
> - **认证**:Token 双轨制(access + refresh) + Redis 黑名单精确吊销 + 密码重置(邮箱验证码) + 角色从 Redis 读(避免 token 膨胀)
> - **用户**:注册 / 登录 / 资料 / 头像 / 改密 + 账号生命周期(封禁 / 注销 / 恢复 + PII 不可逆擦除 + GDPR 跨模块真删)
> - **文件**:S3 兼容(MinIO / RustFS / 阿里云 OSS / 腾讯云 COS / AWS)+ `file_metadata` 落库 + 软删 + 管理员视图
> - **基础设施**:`@Idempotent` / `@RateLimit` / `RequestIdFilter` + WebLog AOP / `GlobalExceptionHandler` / 分布式锁 SPI(Redis 实现)/ `@Audited` HTTP 操作审计
> - **AI 网关(Phase 0-8 全部完成)**:Spring AI 2.0 官方 starter + 多 vendor(OpenAI / Anthropic / Ollama + DeepSeek + 阿里通义 / 智谱 / MiniMax 等 OpenAI 兼容中转);同步 + SSE 流式 + Tool 回路;系统模式 / 用户 BYOK 私 Key / 用户 model alias / 用户代理 / 三态偏好解析;**全部配置 DB 化** — 模型目录(`ai_model_catalog`)/ vendor base_url(`ai_vendor_config`)/ 系统 apiKey(`ai_vendor_config.encrypted_api_key` + AES-256-GCM + fingerprint)/ 降级链策略(`ai_fallback_chain` JSONB)全部支持运行时 admin 改 + 自动热重建;apiKey 轮换审计(`ai_api_key_audit_log` + JSONB GIN 索引,记录谁 / 何时 / 哪个 vendor / 改前改后 fingerprint / 来源 IP);Micrometer 用量 / Caffeine 限流 / 24h 配额 / 对话持久化
> - **前端**:Vue 3.5 + PrimeVue 5 + Pinia + Axios;布局三栏(顶栏 + 内容 + 侧边抽屉);登录 / 注册 / 个人中心;Token 单飞刷新 + AES 持久化
> - **文档**:`AGENTS.md` 是项目内事实来源(包含账号生命周期 / 操作审计 / 文件元数据 / 分布式锁 / 密码重置 / AI 网关各 phase 约定 + 关键踩坑);Swagger UI 挂 `/swagger-ui/index.html`;Docker 编排覆盖 PostgreSQL / Redis / RustFS / MinIO 四栈
>
> 详见 `docs/ROADMAP.md`(长期 backlog)/ `docs/NEXT-STEPS.md`(近期优先级)。

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
| 框架 | Vue 3.5、Vite 8、Vue Router 4 |
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
├── nexus-forge-ai/           # AI 网关:Spring AI 2.0 官方 starter 网关(OpenAI / Ollama / Anthropic + OpenAI 协议家族中转站 + DeepSeek 走 OpenAI 兼容 + 国内 LLM 通过 OpenAI 兼容 endpoint 接:阿里通义 qwen / 智谱 GLM / 月之暗面 Kimi / 字节豆包 / 腾讯混元 / 稀宇科技 MiniMax)、LlmClient、对话、限流、配额、用量、个性化偏好、私 Key(详见"AI 网关"章节);Spring Boot 4 `@AutoConfiguration` 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 挂载;`ProviderPropertiesBridge`(`EnvironmentPostProcessor`)统一把 `spring.ai.providers.*` 桥接到 starter namespace,`ChatModelRouter` 通过 aliasing 把 OpenAI 兼容 vendor(deepseek / dashscope / glm / ...)路由到 `openAiChatModel` bean,业务代码零改动。
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

### 已落地的数据库表(`nexus-forge-user/src/main/resources/db/migration/`)

| 迁移文件 | 表 | 用途 |
|---|---|---|
| `V20260628_001` | `users.avatar_key` | 头像 S3 key 字段(头像走对象存储) |
| `V20260720_001` | `ai_conversations` / `ai_messages` / `ai_message_usage` / `ai_global_default` / `user_ai_preference` | AI 起步(对话 / 消息 / 用量窗口化) |
| `V20260721_001` | `user_plan_quota_override` | 按用户配额覆盖(24h 滑窗) |
| `V20260801_001` | `ai_global_default` | 单行全局默认,`model='__UNSET__'` 哨兵 |
| `V20260801_002` | `user_ai_preference` | 按用户偏好 + AES 加密私 Key |
| `V20260828_001` | `*.deleted_at` | 软删除统一字段 |
| `V20260829_001` | `account_lifecycle_log` | 账号封禁 / 注销 / 恢复审计(无 FK,合规追溯) |
| `V20260830_001` | `file_metadata` | 文件元数据(PENDING / ACTIVE / DELETED 三态) |
| `V20260830_002` | `operation_audit_log` | HTTP 操作审计(15 列 + 3 索引,只追加) |
| `V20260902_001` | `ai_global_default` 数据修正 | 默认 vendor 从 `qwen` 改为 `deepseek` |
| `V20260902_002` | `ai_model_catalog` | 模型目录(每个 model 独立元数据 + JSONB capabilities) |
| `V20260902_003` | `ai_vendor_config` | vendor 启用 / base_url / 模型白名单(DB 化) |
| `V20260902_004` | `user_ai_proxy` | 按用户代理(vendor + base_url + model) |
| `V20260902_005` | `user_ai_model_alias` | 按用户 model alias(短名 → 真实 model) |
| `V20260902_006` | `ai_vendor_config.encrypted_api_key` + `api_key_fingerprint` | 系统 apiKey DB 化(AES-256-GCM) |
| `V20260902_007` | `ai_fallback_chain` | 降级链策略(JSONB 单行 + 事件驱动) |
| `V20260902_008` | `ai_api_key_audit_log` | apiKey 轮换审计(4 索引,含 JSONB GIN) |

> Spring Boot 4.1 移除了 `spring-boot-flyway` 自动装配,所有迁移由 `FlywayMigrationRunner`(`nexus-forge-user`,`@Component` + `@PostConstruct`)在 JPA 之前执行,读 `spring.flyway.{enabled, locations, baseline-on-migrate, validate-on-migrate}`;checksum 不匹配自动 `repair()` 后重试。SQL 全部幂等(`IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... $$`)。

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
├── (无 auto-config)                      # `RequestIdFilter` / `@Idempotent` / `@RateLimit` / `GlobalExceptionHandler` 等 `@Component` / `@Aspect` 类在 `com.nexusforge.{log,idempotent,ratelimit,error}` 下,由 `nexus-forge-web` 的 `@SpringBootApplication` 默认扫描 `com.nexusforge.*` 负责注册。早期有个 `NexusForgeCoreAutoConfiguration.java`,但 Spring Boot 4 已删除 `spring.factories` 机制,而模块缺 `META-INF/spring/...AutoConfiguration.imports` 资源文件,所以该 auto-config 实际从未被加载,已删除
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
├── types/           # TS 类型:`api.ts`(Axios 响应包装)、`auth.ts`(TokenBundle/TokenSlot/LoginRequest/RegisterRequest)、`models/user.ts`(UserInfo/UpdateUserInfo)、`router.d.ts`(Vue Router 模块声明)、`vite-env.d.ts`(import.meta.env 类型)
├── views/           # 页面(按业务模块划分子目录)
├── layout/          # 应用布局(AppLayout / AppToolbar / AppSidePanel)
├── components/      # 通用组件(AvatarUploader / ParticleCanvas)
├── composables/     # 组合式函数:`useAuthBoot.ts`(`main.ts` 调用 `bootstrapAuth().finally(mount)` 的实现,负责 token 刷新预检 + auth store 初始化)、`useDateFormat.ts`(日期格式化工具)
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
| `STORAGE_VENDOR` | 存储后端:`rustfs` / `minio` / `aliyun` / `tencent` / `aws` | `rustfs` |
| `RUSTFS_*` | RustFS 连接参数(endpoint / region / access-key / secret-key / bucket / path-style) | dev 默认 `rustfsadmin/rustfsadmin` 仅供本地 |
| `MINIO_*` | MinIO 连接参数 | dev 默认 `minioadmin/minioadmin` 仅供本地 |
| `ALIYUN_*` / `TENCENT_*` | 阿里云 OSS / 腾讯云 COS | 留空,按需填写 |
| `SPRING_AI_ENABLED` | AI 总开关,`false` 时所有 AI bean 不注册 | `true` |
| `SPRING_AI_DEFAULT_VENDOR` | 根级默认 vendor(运行期被 `ai_global_default` 表覆盖) | 留空,走表 |
| `SPRING_AI_PREFERENCE_MASTER_KEY` | 用户私 Key(AES-256-GCM)加密主密钥,base64,推荐 ≥32 字节 | **无,留空时降级用 `JWT_SECRET` 派生**(dev OK,**生产建议显式配置独立密钥**) |
| `OPENAI_API_KEY` / `DEEPSEEK_API_KEY` | OpenAI / DeepSeek 系统 apiKey(DeepSeek 走 OpenAI 协议复用) | dev 留空 |
| `ANTHROPIC_API_KEY` | Anthropic(独立 Messages 协议,Phase 5 起已接通 starter,默认未启用) | dev 留空 |
| `OLLAMA_BASE_URL` | Ollama 本地推理 base URL | `http://localhost:11434/v1` |
| `DASHSCOPE_API_KEY` | 阿里通义 qwen(OpenAI 兼容)系统 apiKey | dev 留空 |
| `GLM_API_KEY` | 智谱 GLM(OpenAI 兼容)系统 apiKey | dev 留空 |
| `MINIMAX_API_KEY` / `MINIMAX_BASE_URL` | MiniMax(M2/M3)系统 apiKey / base URL | 留空 / `https://api.minimax.chat/v1` |
| `SPRING_AI_QUOTA_ENABLED` | AI 24h token / request 配额总开关 | `true` |
| `SPRING_AI_RATE_LIMIT_ENABLED` | AI 秒级限流(Caffeine)总开关 | `true` |
| `MAIL_MODE` | 邮件模式:`logging`(默认,落 `build/dev-mail/*.eml`)/ `smtp`(走 `spring.mail.*`) | `logging` |
| `MAIL_FROM` | 邮件发件人 | `Nexus Forge <no-reply@nexus-forge.local>` |
| `PWD_RESET_CODE_TTL` | 密码重置验证码 TTL(秒) | `300` |
| `PWD_RESET_MAX_ATTEMPTS` | 验证码最大错误次数 | `5` |
| `PWD_RESET_RATE_WINDOW` / `PWD_RESET_RATE_EMAIL` / `PWD_RESET_RATE_IP` | 限流窗口 / 邮箱次数 / IP 次数 | `60s` / `1` / `3` |

> **vendor apiKey 优先级**: Phase 6 起 vendor 的系统 apiKey **优先**从 DB(`ai_vendor_config.encrypted_api_key`)读;只在 DB 缺值时回退到 yaml / env var 兜底。生产部署**强烈建议**用 admin API 写入(`PUT /api/admin/ai/vendors/{vendor}/api-key`)并禁用 yaml 兜底。轮换全留审计,见 `ai_api_key_audit_log`。

### 生产环境

绝对不要把 `.env` 提交到仓库;通过以下任一方式注入:

- 容器环境变量(`docker run -e` / `k8s envFrom`)
- CI/CD Secret 配合 `envsubst` 渲染 `application-prod.yaml`
- 配置中心(Nacos / Apollo / Spring Cloud Config)

prod profile 下**所有凭据字段**(存储 access-key/secret-key、数据库密码、JWT 签名密钥)均**无默认值**,缺失即启动失败;非凭据字段(endpoint / bucket / region 等)继承基础 `application.yaml` 的 dev 默认值,生产部署时必须用真实值覆盖。

### 凭据轮换

- 怀疑 JWT 泄露 → 重生成 `JWT_SECRET`,所有用户 token 立即失效,需重新登录
- 怀疑数据库泄露 → 立即重置 `DB_PASSWORD`,并在 `application-*.yaml` 中检查是否有样例残留
- 历史清理:仓库早期 commit 含有示例凭据(`VasyaMambo`,git author 邮箱 `pexijl@foxmail.com`),**视同已泄露**,必须轮换密码并视情况 `git filter-repo`

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
- **角色从 Redis 读**:`JwtAuthenticationFilter` → `UserRoleProvider`(`auth:roles:{userId}`,TTL 5min,evict 在角色变更后)→ `SecurityContext` 的 `GrantedAuthority` 全部加 `ROLE_` 前缀;**避免 token 膨胀,角色变更后只需 evict 缓存,不用重发 token**
- **密码重置**:`POST /api/auth/password/reset/request` 提交邮箱 + `POST /api/auth/password/reset/confirm` 邮箱 + 6 位验证码 + 新密码;`pwd:reset:*` Redis 命名空间(code hash 不存明文,attempts 超限自动清,邮箱 60s/IP 60s 限流);改密后只踢 refresh(`authService.logoutAllRefreshTokens`);错误码 `2013` / `2014` / `2015` / `2016`

### 用户(`nexus-forge-user`)

- `User` 实体:账号、邮箱、加密密码、昵称、头像、手机号、状态(`ACTIVE` / `DISABLED`)、角色集合、`lastLoginAt`
- `GET /api/users/me` — 基于 `@AuthenticationPrincipal UserPrincipal` 拉取当前登录用户信息
- `PATCH /api/users/me` — 修改昵称/邮箱/手机号(邮箱去重排除自身)
- `POST /api/users/me/password` — 修改密码(校验旧密码,新旧不可相同)
- `POST /api/users/me/avatar` — 上传头像(对接文件模块,自动清理旧文件)
- `DELETE /api/users/me/avatar` — 删除头像(恢复默认)
- 单元测试:注册、更新资料、修改密码 三个 Service 核心路径覆盖
- **账号生命周期**:`AccountLifecycleService` 集中封禁 / 注销 / 恢复流程
  - `POST /api/users/me/delete/request` 申请注销(发邮件验证码) / `POST /api/users/me/delete/confirm` 校验 + 真删 / `POST /api/users/me/restore` 一次性 token(14 天)恢复
  - `POST /api/admin/users/{id}/ban` / `POST /api/admin/users/{id}/unban`(`@PreAuthorize("hasRole('ADMIN')")`)
  - `GET /api/admin/users/{id}/lifecycle` / `GET /api/admin/users/lifecycle?action=BAN` 分页审计
  - `AccountAnonymizer` 注销时 PII 不可逆擦除:`username → deleted-{id}`、`email → deleted-{id}@deleted.local`、`password → bcrypt(random UUID)`、`status → BANNED`(防旧 refresh)、`deleted_at` 由 `@SQLDelete` 写
  - `UserDataDeletionEvent` 解耦跨模块真删,`AiUserDataDeletionListener` / `FileUserDataDeletionListener` 真删业务数据(GDPR 闭环)
  - `account_lifecycle_log` 表无 FK 到 `users.id` — 真删 users 时审计必须保留(合规追溯)

### 文件(`nexus-forge-file`)
- `StorageProvider` — 统一存储接口(upload / download / delete / presignedUrl / exists)
- `S3StorageProvider` — S3 兼容协议实现,支持 MinIO / RustFS / 阿里云 OSS / 腾讯云 COS / AWS S3(共享同一套 AWS SDK v2 客户端)
- `StorageProperties` — 多厂商配置绑定(endpoint / bucket / access-key / secret-key / region / path-style);`storage.vendor` 支持 `minio` / `rustfs` / `aliyun` / `tencent` / `aws`
- `FileController` — 单/多文件上传、下载、删除、批量删除、预签名 URL(PUT/GET)、分片上传(初始化 / 预签名分片 / 完成合并)
- `FileService` — 文件业务逻辑(命名策略、类型过滤)
- `FileClientImpl` — 业务侧门面(供用户头像等场景调用)
- **文件元数据落库**:`file_metadata` 表(PENDING / ACTIVE / DELETED 三态)+ `FileEntity` + `FileMetadataRepository`;`/upload` / `/upload-legacy` / `/confirm/{key}` / `/mine` / `/{id}` / `DELETE /{id}` / `/admin?ownerId=&biz=&status=` + admin `@PreAuthorize`;`@SQLDelete` 直接放 `@Entity` 上(不靠 BaseEntity 继承);GDPR 真删走 `FileUserDataDeletionListener` 监听 `UserDataDeletionEvent` 物理删 DB + 清对象存储

### 核心基础设施(`nexus-forge-core`)

- **限流**: `@RateLimit` 注解 + SpEL key + Bucket4j 本地 Token Bucket(Caffeine 缓存)
- **幂等**: `@Idempotent` 注解 + SpEL key + Redis SET NX EX + SHA-256 哈希
- **请求日志**: `RequestIdFilter` 生成 `X-Trace-Id` 写入 MDC,记录访问日志
- **Web 日志 AOP**: `WebLogAspect` 记录 Controller 执行耗时
- **全局异常处理**: `GlobalExceptionHandler` 统一拦截业务异常、校验异常、404、文件过大、通用错误;`AccessDeniedException` → 403 + 1005 FORBIDDEN
- **分布式锁 SPI**:`DistributedLock` + `RedisDistributedLock` + `DistributedLockTemplate`(三层 API:tryLock / tryLockOrThrow / tryLockWithWait / lock / runWithLock);Redis `SET key token NX PX` + Lua 比对 + DEL 保证原子;`lease` 必填防 deadlock;已用于 `FileService.uploadByBiz`(防并发上传)/ `AccountLifecycleService.requestDeletion`(防同 user 重复申请)
- **HTTP 操作审计**:`@Audited` 注解 + `AuditAspect` AOP 切面 + `operation_audit_log` 表(15 列 + 3 索引,只追加,合规追溯);`/api/admin/audit-logs?userId=&action=&resource=&page=&size=` 多维过滤 + 分页;SpEL 求值 `resourceId`(`#userId` / `#principal.userId()`);`recordArgs` 元数据入参落 JSONB(跳过 Object / 集合,防大对象 / 敏感 DTO);切面 `try/finally` 写审计,业务异常原样传播

### API 文档

- springdoc-openapi 3.0.3 集成,Swagger UI 挂 `/swagger-ui/index.html`
- 全局 JWT Bearer 认证方案(可在 UI 中一键填入 Token)
- 按业务分组:`public`(/auth/**)、`user`(/users/**)、`file`(/files/**)
- 三个 Controller 全部标注 `@Tag` / `@Operation` / `@ApiResponses`,DTO/VO 标注 `@Schema`

### 公共(`nexus-forge-common`)

- `Result<T>` — 统一响应包装(`code` / `message` / `data`)
- `PageResult<T>` — 统一分页响应(`records` / `total` / `page` 1-based / `size` / `pages` / `hasNext` / `hasPrevious`);列表接口**统一返** `Result<PageResult<T>>`,不再返 `Result<List<T>>`
- `ResultCode` — 业务错误码(`USER_NOT_FOUND` / `USER_ALREADY_EXISTS` / `EMAIL_ALREADY_EXISTS` / `LLM_CONFIG_MISSING` / `LLM_MODEL_NOT_FOUND` / `LLM_PROVIDER_ERROR` / `LLM_UPSTREAM_TIMEOUT` / `LLM_RATE_LIMITED` / `LLM_QUOTA_EXCEEDED` / `LLM_ALL_VENDORS_FAILED` / `LLM_CIRCUIT_OPEN` / `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED` / `LLM_MODEL_ALIAS_NOT_FOUND` / `RESET_CODE_*` ...)
- `Role` — `USER` / `ADMIN`,内置 Spring Security `authority`
- `UserStatus` — 用户状态枚举(`ACTIVE` / `DISABLED` / `BANNED`;`DELETED` 已 `@Deprecated`,由 `deleted_at` 取代)
- `BaseEntity` — 实体基类(`createdAt` / `updatedAt` UTC + `deletedAt` 软删字段);`@SQLDelete` / `@SQLRestriction` **必须**直接放 `@Entity` 上(Hibernate 6/7 不从 `@MappedSuperclass` 继承语义)
- 异常体系:`BaseException` → `BusinessException` / `AuthException` / `LlmException`(AI 网关专用),由 `GlobalExceptionHandler` 统一处理
- 文件 DTO: `FileClient` / `FileMeta` / `FileBizType` / `FileAccess` / `UploadCredential`
- 事件: `UserBannedEvent` / `UserDataDeletionEvent`(跨模块真删事件,解耦 user 与 file / ai)
- 通用审计接口: `com.nexusforge.audit.AuditEvent<A>` + `AuditLogger<A>`(当前实现 `AccountLifecycleAuditLogger` 写 `account_lifecycle_log`;后续模块可加自己的实现)

### AI 网关(`nexus-forge-ai`)

#### 当前能力全景(Phase 0–8 全部完成)

| 阶段 | 落地能力 |
|---|---|
| Phase 0 | `ai_global_default` 单行表 + `__UNSET__` 哨兵,未配置时 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)` |
| Phase 1 | 用户 BYOK `user_ai_preference`(AES-256-GCM)+ `api_key_fingerprint`;`VendorChatModelFactory` 按 `sha256(apiKey)` 缓存动态构造的 `OpenAiChatModel`;Anthropic 私 Key 模式占位 throw `LLM_INVALID_REQUEST` |
| Phase 2 | 按用户代理 `user_ai_proxy`(vendor + base_url + model),覆盖全局默认 |
| Phase 3 | 三态偏好解析 `SYSTEM` / `USER_OVERRIDE_SYSTEM_KEY` / `USER_PRIVATE_KEY`,请求 model → 用户偏好 → 全局默认 → yaml 链路 |
| Phase 4 | 按用户 model alias `user_ai_model_alias`,短名 → 真实 model 解析,`MODEL_ALIAS_NOT_FOUND` 错误码 |
| Phase 5 | vendor base_url DB 化 `ai_vendor_config`,admin 热改 → `SystemKeyChatModelFactory` 重建 ChatModel + 失效本地缓存 |
| Phase 6 | vendor 系统 apiKey DB 化 `ai_vendor_config.encrypted_api_key` + fingerprint;yaml 仅做启动兜底;私 Key 路径仍走 `user_ai_preference` |
| Phase 7 | 降级链策略 DB 化 `ai_fallback_chain` JSONB 单行,事件驱动 `ChatModelRouter` 重载;`isPrimaryVendorOpen` 暂恒 false(Spring AI retry / Resilience4j 留 Phase 5+) |
| Phase 8 | apiKey 轮换审计 `ai_api_key_audit_log` + JSONB GIN 索引,记录 action / actor / ip / 改前改后 fingerprint;**所有 admin 改 / 清空 vendor 系统 apiKey 必留痕** |

#### 核心组件

- **Spring AI 2.0 官方 starter** 提供 3 个 vendor 的 `ChatModel` bean:`spring-ai-starter-model-openai`(OpenAI 官方 + DeepSeek + 阿里通义 / 智谱 / MiniMax 等 OpenAI 兼容 vendor 复用)/ `spring-ai-starter-model-anthropic` / `spring-ai-starter-model-ollama`。`AiAutoConfiguration.chatModelRouter(Map<String, ChatModel>)` 按 bean 名归一化为小写 vendor 名;`ProviderPropertiesBridge`(`EnvironmentPostProcessor`,`addFirst` 优先级)把 `spring.ai.providers.*` 桥到 `spring.ai.<starter-ns>.*`,`ChatModelRouter` 通过 aliasing 把 OpenAI 兼容 vendor(`deepseek` / `dashscope` / `glm` / `kimi` / `doubao` / `hunyuan` / `siliconflow` / `oneapi` / `openrouter` / `minimax`)路由到 `openAiChatModel` bean — 业务面仍用 vendor 字符串,无感
- **`LlmClient` 门面**:`call(Prompt)` / `call(Prompt, vendor, model)` / `stream(Prompt)` / `stream(Prompt, vendor, model)`(系统模式) + `call(Prompt, ChatModel)` / `stream(Prompt, ChatModel)`(私 Key 模式,绕过降级链 / quota / IP 限流)。`callWithToolLoop(Prompt, ChatModel)` 内部包 Spring AI `DefaultToolCallingManager` 的 tool 回路
- **Tool Calling / Function Calling 闭环**:业务侧在 `@Component` 类的 `@Tool` 方法上标注,`AiAutoConfiguration.toolCallbackProvider` 的 `MethodToolCallbackProvider` 自动扫成 `ToolCallback` 列表;`LlmClient` 用 Spring AI `DefaultToolCallingManager` 检测 `AssistantMessage.getToolCalls()`、执行工具、把 `ToolResponseMessage` 重入 Prompt,直到响应不再含 tool_call 或达到 `AiProperties.maxToolTurns` 上限
- **`RateLimitGuard`**(Caffeine 本地 Token Bucket,user + IP 维度,私 Key 跳 IP;键前缀 `ai:rl:`,与 `core/RateLimitAspect` 的 `rl:` 命名空间隔离)
- **`QuotaService`**:24h 滑窗 token / request 计数,支持 per-user override,私 Key 跳过平台 quota
- **`UsageRecorder`** + Micrometer 埋点:`ai.chat.requests` / `ai.chat.tokens.{prompt,completion,total}` 计数器,按 model + source(platform / private)维度拆 tag
- **DTO 走 Spring AI**:前端的 `ChatRequestDto.messages` 是 `List<Message>`(Jackson + Spring AI `MessageTypeDeserializer` 按 role 字段多态反序列化),服务端返 `Result<ChatResponse>`(Spring AI 类型)。客户端 wire 格式跟 OpenAI Chat Completions 一致
- **sprint-ai-full-migration 已删**:`com.nexusforge.ai.*` 9 个旧 chat DTO(`ChatMessage` / `ChatRequest` / `ChatResponse` / `ChatChunk` / `ChatUsage` / `DeltaToolCall` / `ToolCall` / `ToolDefinition` / `Role`)、`com.nexusforge.model.{ChatModel,ChatCapabilities}` SPI、所有自实现 provider(`OpenAiChatModel` / `OpenAiCompatibleChatModel` / `QwenChatModel` / `DeepSeekChatModel` / `OllamaChatModel` / `AnthropicChatModel` 等)、`OpenAiJsonMapper` / `AnthropicJsonMapper` / `OpenAiStreamParser` / `AnthropicMessagesStreamParser`、`ChatModelHttpSupport` / `CircuitState`、`ToolRegistry` / `ToolExecutor` / `ToolResult` / `FunctionCallAggregator`、SSE helper(`SseEventCodec` / `SseFormat`)—所有这些已被 Spring AI 官方实现替代
- **流式输出**:`AiStreamController` 用 `StreamingResponseBody`(避开 Spring 7 + Tomcat 11 的 chunked transfer encoding EOF 问题;**不要**用 `SseEmitter`)。`writeChunks` 用单次 `Flux.subscribe` + `CountDownLatch.await` 防止 cold-Flux 双重订阅(否则会发 2 次 LLM HTTP 请求)。SSE wire 格式是 Spring AI `ChatResponse` 的 Jackson 序列化结果(每帧 `data: <json>\n\n`),字段路径跟 OpenAI `chat.completion.chunk` 兼容;错误帧 `{"error": "..."}`;流结束靠 socket 关闭(无 `data: [DONE]` 哨兵)
- **首启必走 admin 初始化**:`ai_global_default.model` 种子为 `'__UNSET__'`(sentinel),所有 system-mode 请求返回 `LLM_GLOBAL_DEFAULT_NOT_CONFIGURED (3010)`,直到 admin 调 `PUT /api/admin/ai/global-default` 设置真值

#### REST 端点(全部清单)

业务面(登录用户即可访问):

| Method | Path | 用途 |
|---|---|---|
| `POST` | `/api/ai/chat` | 同步对话 |
| `POST` | `/api/ai/chat/stream` | SSE 流式(**注意是 `/chat/stream`,不是 `/stream`**) |
| `POST` | `/api/ai/conversations` | 创建对话 |
| `GET` | `/api/ai/conversations` | 对话列表(分页) |
| `GET` | `/api/ai/conversations/{id}` | 对话详情 `ConversationDetailVo`,**消息列表在该 VO 内** |
| `POST` | `/api/ai/conversations/{id}/messages` | 发送消息,触发 LLM 调用 |
| `PATCH` | `/api/ai/conversations/{id}` | 改标题 / pin |
| `DELETE` | `/api/ai/conversations/{id}` | 软删对话 |
| `GET` | `/api/ai/preference` / `PUT` / `DELETE` | 用户偏好(私 Key 明文入库,AES-256-GCM 加密) |
| `GET` / `PUT` / `DELETE` | `/api/ai/proxy` | 用户代理 |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/ai/aliases` | 用户 model alias |
| `GET` | `/api/ai/usage` | 24h 用量摘要 |
| `GET` | `/api/ai/models` | 公开模型目录(按用户可见 vendor 过滤) |

管理端(`@PreAuthorize("hasRole('ADMIN')")`):

| Method | Path | 用途 |
|---|---|---|
| `GET` / `PUT` | `/api/admin/ai/global-default` | 全局默认 vendor / model |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/admin/ai/models` | 模型目录 CRUD |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/admin/ai/vendors` | vendor 配置 CRUD(启用 / 停用 / base_url / 模型白名单) |
| `GET` / `PUT` / `DELETE` | `/api/admin/ai/vendors/{vendor}/api-key` | vendor 系统 apiKey(改 / 清空,**必写审计**) |
| `GET` / `PUT` / `DELETE` | `/api/admin/ai/fallback-chain` | 降级链策略(整链 JSONB) |
| `GET` | `/api/admin/ai/vendors/{vendor}/api-key-audit?page=&size=` | 某 vendor 的 apiKey 轮换审计 |
| `GET` | `/api/admin/ai/api-key-audit?page=&size=` | 全表分页审计 |
| `GET` | `/api/admin/audit-logs` | HTTP 操作审计(`@Audited` 落 `operation_audit_log`,跨模块) |

### AI 数据库迁移(`nexus-forge-user` Flyway)

Spring Boot 4.1 已移除 `spring-boot-flyway` 自动装配;`FlywayMigrationRunner`(`@Component` + `@PostConstruct`)替代,在 JPA 启动前跑迁移,读 `spring.flyway.{enabled, locations, baseline-on-migrate, validate-on-migrate}`。校验失败(checksum mismatch)自动 `flyway.repair()` 后重跑。

AI 相关表(在 `nexus-forge-user/src/main/resources/db/migration/`):

- `V20260801_001__add_ai_global_default.sql` — `ai_global_default` 单行表(`id=1 CHECK (id=1)`,`model='__UNSET__'` 种子)— Phase 0
- `V20260801_002__add_user_ai_preference.sql` — `user_ai_preference` per-user 表,`encrypted_api_key BYTEA`(AES-256-GCM 密文)+ `api_key_fingerprint VARCHAR(16)`(明文 Key 指纹展示)— Phase 1
- `V20260902_001__remove_qwen_default_vendor.sql` — `ai_global_default` 默认 vendor 从 `qwen` 改为 `deepseek`(DeepSeek 走 OpenAI 协议)
- `V20260902_002__add_ai_model_catalog.sql` — `ai_model_catalog` 模型目录(JSONB capabilities)— Phase 5+
- `V20260902_003__add_ai_vendor_config.sql` — `ai_vendor_config` vendor 配置(enabled / base_url / 模型白名单)— Phase 5
- `V20260902_004__add_user_ai_proxy.sql` — `user_ai_proxy` 用户代理 — Phase 2
- `V20260902_005__add_user_ai_model_alias.sql` — `user_ai_model_alias` 用户 model alias — Phase 4
- `V20260902_006__add_ai_vendor_config_api_key.sql` — `ai_vendor_config.encrypted_api_key` + `api_key_fingerprint`,系统 apiKey DB 化 — Phase 6
- `V20260902_007__add_ai_fallback_chain.sql` — `ai_fallback_chain` JSONB 单行,降级链策略 DB 化 — Phase 7
- `V20260902_008__add_ai_api_key_audit_log.sql` — `ai_api_key_audit_log` apiKey 轮换审计(4 索引,含 `(metadata->>'vendor')` JSONB GIN)— Phase 8

所有 SQL 幂等(`IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... $$`),容忍 JPA `ddl-auto` 已建过表的现状。生产 `ddl-auto: validate`;实体字段变更必须与同一提交中的迁移配套,否则生产启动会因列不匹配失败(详见 `AGENTS.md` "Flyway 经验法则")。

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

## 待开发(2026-09-02 状态)

> 与 `docs/ROADMAP.md`(长期 backlog)/ `docs/NEXT-STEPS.md`(近期优先级)联动;此段做"高优待办"摘要,详情见那两个文件。

### 后端 AI 网关(Phase 0–8 全部完成,Phase 9+ 候选)

- [x] **Phase 0–8 全部完成** — 模型目录 / vendor base_url / 用户 BYOK / 用户 model alias / 用户代理 / 系统 apiKey DB 化 / 降级链 DB 化 / apiKey 轮换审计 全部 DB 化 + 事件驱动热重建 / 审计
- [ ] `nexus-forge-ai`:向量检索 / RAG(embedding + 向量库,留作长期项)
- [ ] `nexus-forge-ai`:Anthropic 私 Key 模式(`VendorChatModelFactory` 当前显式 throw `LLM_INVALID_REQUEST`)
- [ ] `nexus-forge-ai`:admin UI 集成 apiKey 轮换审计 + 全局默认 + 模型目录 + vendor 配置
- [ ] `nexus-forge-ai`:Redis pub/sub 跨实例 vendor config / fallback chain 热失效(当前每实例本地缓存)
- [ ] `nexus-forge-ai`:`X-Forwarded-For` 解析(反代部署场景,Phase 8 暂拿连接 IP)

### 后端基础设施

- [x] `@Idempotent` / `@RateLimit` / `RequestIdFilter` / `WebLogAspect` / `GlobalExceptionHandler`
- [x] 分布式锁 SPI(`DistributedLock` + `RedisDistributedLock` + `DistributedLockTemplate`)— 已用于 `FileService.uploadByBiz` / `AccountLifecycleService.requestDeletion`
- [x] HTTP 操作审计(`@Audited` + `AuditAspect` + `operation_audit_log`,`/api/admin/audit-logs` 分页查询)— 已应用 user.update / user.password.change / user.avatar.remove / file.upload / file.delete
- [x] 账号生命周期(`AccountLifecycleService` + PII 不可逆擦除 + GDPR 跨模块真删)— `/api/users/me/delete/{request,confirm,restore}` + `/api/admin/users/{id}/{ban,unban,lifecycle}`
- [x] 密码重置(`/api/auth/password/reset/{request,confirm}` 邮箱验证码 + 限流)
- [x] 角色从 Redis 读(`JwtAuthenticationFilter` + `UserRoleProvider` + `auth:roles:*`,避免 token 膨胀)

### 前端

- [x] 应用骨架 + 布局三栏(顶栏 + 侧边抽屉 + 内容区) + 个人中心 4 面板 + Axios 拦截器 + Pinia AES 持久化
- [ ] 路由级权限守卫(基于 `Role` 过滤菜单)— `Role` 数据已就位,前端 router 守卫待补
- [ ] 业务首页(`/home`)真实数据 — 当前为占位
- [ ] AI 业务页(聊天 / 偏好 / 管理员)— `src/api/` 当前只有 `auth.ts` / `user.ts`,**AI 网关能力已就绪,前端一行都没用**
- [ ] i18n 国际化(中文 / 英文)

### 测试 / CI / 部署

- [x] 后端单测 381 case(8 预存失败,7 个 `AiMessageUsageRepositoryTest` 需真实 DB + 1 个 `ConversationServiceTest` Spring AI 2.0 metadata 假设,与本路线无关)
- [x] 集成测试 13 IT(端到端覆盖 auth / user / file / ai,Testcontainers 真 PG / Redis / RustFS)
- [x] Docker Compose 一键起 PostgreSQL / Redis / RustFS / MinIO — `docker/{Postgres,Redis,RustFS,MinIO}`
- [x] GitHub Actions CI — `.github/workflows/ci.yml`(lint `continue-on-error: true` 待现有错误清零后移除)

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
