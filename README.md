# Nexus Forge

一个基于 **Java 26 + Spring Boot 4** 与 **Vue 3** 的全栈应用骨架,按业务能力拆分为 Gradle 多模块项目。

> 当前进度: `auth` / `user` / `file` / `core` 四个后端模块均已实现; `core` 提供了限流、幂等、请求日志等基础设施; 前端已补齐布局骨架与个人中心页; 接入 Swagger UI 在线文档; `user` 模块已有单元测试覆盖。

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
├── nexus-forge-file/         # 文件:对象存储抽象、S3/MinIO/阿里云/腾讯云实现
├── nexus-forge-ai/           # AI 能力(规划中)
├── nexus-forge-visual/       # 可视化(规划中)
└── nexus-forge-ui/           # 前端(Vue 3)
```

### 模块依赖方向

```
web ─┬─► auth ─┬─► common
     ├─► user ─┘
     ├─► core ► common
     ├─► file ► common
     ├─► ai   ► common
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
- PostgreSQL ≥ 14(本地或 Docker)
- Redis(本地或 Docker) —— 幂等与限流功能需要
- MinIO 或 S3 兼容服务(for 文件存储,可选)

### 后端启动

```bash
# 1. 准备数据库
psql -U postgres -c "CREATE DATABASE nexus_forge;"

# 2. 修改环境配置(如需)
# nexus-forge-web/src/main/resources/application-dev.yaml 中调整数据源与 JWT 配置

# 3. 启动
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
| `JWT_SECRET` | JWT 签名密钥(≥32 字节) | **无,必须注入** |
| `JWT_TTL_MS` | JWT 有效期(毫秒) | `7200000`(2 小时) |
| `STORAGE_VENDOR` | 存储后端:`minio` / `aliyun` / `tencent` | `minio` |
| `MINIO_*` | MinIO 连接参数 | dev 默认 `minioadmin/minioadmin` 仅供本地 |
| `ALIYUN_*` / `TENCENT_*` | 阿里云 OSS / 腾讯云 COS | 留空,按需填写 |

> dev profile 下 `DB_PASSWORD` / `JWT_SECRET` **无默认值** —— 缺失时会启动失败,避免用空凭据静默运行。

### 生产环境

绝对不要把 `.env` 提交到仓库;通过以下任一方式注入:

- 容器环境变量(`docker run -e` / `k8s envFrom`)
- CI/CD Secret 配合 `envsubst` 渲染 `application-prod.yaml`
- 配置中心(Nacos / Apollo / Spring Cloud Config)

prod profile 下所有凭据均**无默认值**,缺失即启动失败。

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

- `POST /api/auth/register` — 用户注册
- `POST /api/auth/login` — 用户登录,签发 JWT
- `JwtAuthenticationFilter` — 解析请求头 `Authorization: Bearer <token>`,构建 `SecurityContext`
- `SecurityConfig` — 路由级权限控制、CORS 配置
- `JsonAuthHandlers` — JSON 格式的 401/403 响应
- `UserPrincipal` — `record(userId, username)`,作为 `Authentication.principal` 在 `SecurityContext` 中传递

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
- `S3StorageProvider` — S3/MinIO/阿里云 OSS/腾讯云 COS 实现
- `StorageProperties` — 多厂商配置绑定(endpoint / bucket / access-key / secret-key / region / path-style)
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
- 异常体系:`BaseException` → `BusinessException` / `AuthException`,由 `GlobalExceptionHandler` 统一处理
- 文件 DTO: `FileClient` / `FileMeta` / `FileBizType` / `FileAccess` / `UploadCredential`

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

- [ ] `nexus-forge-ai`:LLM 调用网关、流式响应、向量检索 / RAG
- [ ] `nexus-forge-visual`:图表 / 看板 / 大屏组件
- [ ] 后端:Token 刷新(`POST /auth/refresh`)、登出黑名单
- [ ] 后端:密码重置(邮箱验证码)、第三方登录
- [ ] 前端:业务首页(`/home`)真实数据、权限路由(基于 Role)
- [ ] 前端:i18n 国际化(中文 / 英文)
- [ ] 集成测试:auth + user + file 端到端
- [ ] Docker Compose 一键起 PostgreSQL + Redis(目前仅 MinIO 有 Compose)
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
