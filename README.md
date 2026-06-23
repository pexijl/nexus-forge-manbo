# Nexus Forge

一个基于 **Java 26 + Spring Boot 4** 与 **Vue 3** 的全栈应用骨架,按业务能力拆分为 Gradle 多模块项目。

> 当前进度:已跑通 `auth` + `user` 两个核心模块的端到端流程(注册 / 登录 / 鉴权 / 当前用户),其余业务模块(`file` / `ai` / `visual` / `core`)处于规划阶段。

---

## 技术栈

### 后端

| 类别 | 技术 |
|------|------|
| 语言 / 运行时 | Java 26 |
| 框架 | Spring Boot 4.1.0、Spring Security、Spring Data JPA |
| 鉴权 | JJWT、Spring Security `OncePerRequestFilter` |
| 数据库 | PostgreSQL(多环境配置 `dev` / `prod`) |
| 构建 | Gradle(子模块禁用 `bootJar`,由 `web` 聚合打 fat jar) |
| 工具 | Lombok、Spring Boot DevTools |

### 前端(`nexus-forge-ui`)

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3.5、Vite 7、Vue Router 4 |
| UI | PrimeVue 4 + PrimeVue Forms、PrimeUIX Themes、Tailwind CSS 4 |
| 状态管理 | Pinia 3 + `pinia-plugin-persistedstate` |
| 网络 | Axios |
| 校验 | Zod 4 |
| 代码质量 | ESLint 10、vue-tsc |

---

## 模块结构

```
nexus-forge/
├── nexus-forge-web/          # 应用入口,聚合所有子模块,统一配置
├── nexus-forge-common/       # 公共基础(Result / ResultCode / 异常 / BaseEntity / 公共枚举 / UserPrincipal)
├── nexus-forge-core/         # 核心基础设施(占位)
├── nexus-forge-auth/         # 认证:Spring Security + JWT
├── nexus-forge-user/         # 用户:实体、注册、当前用户查询
├── nexus-forge-file/         # 文件(占位)
├── nexus-forge-ai/           # AI 能力(占位)
├── nexus-forge-visual/       # 可视化(占位)
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
├── security/        # Spring Security 扩展(LoginUser、UserDetailsServiceImpl)
└── util/            # 工具类(JwtUtil)
```

### 前端(`nexus-forge-ui/src`)

```
src/
├── api/             # 后端接口封装(auth.ts / user.ts)
├── stores/          # Pinia 状态(auth 等)
├── router/          # 路由配置
├── types/           # TS 类型(api.d.ts / models/)
├── views/           # 页面(按业务模块划分子目录)
├── components/      # 通用组件
└── utils/           # 工具函数(http.ts 等)
```

---

## 快速开始

### 环境要求

- JDK 26
- Node.js ≥ 20
- PostgreSQL ≥ 14(本地或 Docker)

### 后端启动

```bash
# 1. 准备数据库
psql -U postgres -c "CREATE DATABASE nexus_forge;"

# 2. 修改环境配置(如需)
# nexus-forge-web/src/main/resources/application-dev.yaml 中调整数据源与 JWT 配置

# 3. 启动
./gradlew :nexus-forge-web:bootRun
```

默认端口:`8080`,默认 profile:`dev`(可在根 `application.yaml` 修改)。

### 前端启动

```bash
cd nexus-forge-ui
npm install
npm run dev
```

默认地址:`http://localhost:5173`,通过 Vite 代理转发 `/auth`、`/users` 等到后端 `8080`。

---

## 已完成功能

### 认证(`nexus-forge-auth`)

- `POST /auth/register` — 用户注册
- `POST /auth/login` — 用户登录,签发 JWT
- `JwtAuthenticationFilter` — 解析请求头 `Authorization: Bearer <token>`,构建 `SecurityContext`
- `SecurityConfig` — 路由级权限控制、CORS 配置
- `UserPrincipal` — `record(userId, username)`,作为 `Authentication.principal` 在 `SecurityContext` 中传递

### 用户(`nexus-forge-user`)

- `User` 实体:账号、邮箱、加密密码、昵称、头像、手机号、状态(`ACTIVE` / `DISABLED`)、角色集合、`lastLoginAt`
- `GET /users/me` — 基于 `@AuthenticationPrincipal UserPrincipal` 拉取当前登录用户信息

### 公共(`nexus-forge-common`)

- `Result<T>` — 统一响应包装(`code` / `message` / `data`)
- `ResultCode` — 业务错误码(`USER_NOT_FOUND`、`USER_ALREADY_EXISTS`、`EMAIL_ALREADY_EXISTS`、`REGISTRATION_FAILED` ...)
- `Role` — `USER` / `ADMIN`,内置 Spring Security `authority`
- `UserStatus` — 用户状态枚举
- `BaseEntity` — 实体基类(`createdAt` / `updatedAt`)
- 异常体系:`BaseException` → `BusinessException` / `AuthException`,由 `GlobalExceptionHandler` 统一处理
- 启用 Spring 6 `Problem Details for HTTP APIs`

### 前端

- 路由守卫 + 鉴权拦截
- Pinia `auth store`,token + `userInfo` 持久化
- 登录 / 注册页(Zod 校验,PrimeVue Forms)

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

---

## 待开发

- [ ] `nexus-forge-core`:通用中间件(日志、缓存、幂等、限流)
- [ ] `nexus-forge-file`:对象存储 / 本地上传抽象
- [ ] `nexus-forge-ai`:LLM 调用网关、流式响应、向量检索
- [ ] `nexus-forge-visual`:图表 / 看板组件
- [ ] 前端:`/home` 业务页、`/users/me` 个人中心、权限路由
- [ ] 单元测试 / 集成测试

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