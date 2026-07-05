# Roadmap

> 与 `README.md` 中的"待开发"清单联动。本文件按模块拆分,每条都是一条独立的待办。
> 完成后将 `[ ]` 改成 `[x]`;若发现范围偏差,直接编辑本文件,不要改 README。

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

## 🔥 当前冲刺(2026-07-05 ~ 2026-07-06)

> 上一轮冲刺(06-24 ~ 06-25)已全部完成,当前聚焦认证加固、Docker 编排与测试覆盖。

### ✅ 上一轮已完成

- [x] `nexus-forge-file` 文件模块 —— 存储抽象层 + S3/MinIO/阿里云/腾讯云实现 + 文件控制器 + 分片上传 + 预签名 URL
- [x] 后端:`PATCH /users/me`(昵称 / 头像 / 邮箱 / 手机)
- [x] 后端:`POST /users/me/password`(改密码,校验旧密码)
- [x] 后端:清除 `Result.java` magic number TODO
- [x] 前端:`/profile` 个人中心页(4 个面板:基础信息/联系方式/通知/安全)
- [x] 横切:接入 `springdoc-openapi`,Swagger UI 挂 `/swagger-ui/index.html`
- [x] `nexus-forge-core` 基础设施(限流 / 幂等 / 请求日志 / 全局异常处理)
- [x] 前端:头像上传组件 + 用户头像管理(上传 / 删除)
- [x] 前端:axios 拦截器 401 → 跳登录 / 403 → Toast
- [x] 前端:退出登录流程(清除 token + 跳转)
- [x] `user` 模块单元测试(注册 / 更新资料 / 修改密码)

### 🔥 本轮待办

- [ ] 后端:`POST /auth/refresh`(access + refresh 双 Token)
- [ ] 后端:登出 / Token 黑名单(Redis)
- [ ] 后端:`JwtAuthenticationFilter` 改为从 Redis 读权限
- [ ] Docker Compose 一键起 PostgreSQL + Redis + MinIO
- [ ] 集成测试:auth + user + file 端到端
- [ ] GitHub Actions CI(lint + test + build)

---

## nexus-forge-common

- [x] `Result.success(String, T)` 与 `Result.fail(String)` 中的 magic number 替换为 `ResultCode` 常量
- [ ] 抽取 `PageResult<T>` 统一分页响应
- [ ] 抽取 `BaseEntity` 软删除字段(`deleted`、`deletedAt`)

## nexus-forge-core

- [x] 请求日志中间件(请求 ID + MDC 链路追踪) — `RequestIdFilter` + `WebLogAspect`
- [x] 统一幂等组件(`@Idempotent` 注解 + Redis SET NX EX) — `IdempotentAspect` + `RedisIdempotentStore`
- [x] 限流组件(基于 Bucket4j + Caffeine Token Bucket) — `@RateLimit` + `TokenBucketRateLimiter`
- [ ] 分布式锁抽象(本地 / Redis 可切换)
- [ ] 操作审计(`@Auditable` 注解 + 异步落库)

## nexus-forge-auth

- [x] 用户注册(`POST /api/auth/register`)
- [x] 用户登录 / JWT 签发(`POST /api/auth/login`)
- [x] `JwtAuthenticationFilter` 解析 Token 写 `SecurityContext`
- [x] `UserPrincipal` 统一认证主体
- [x] JSON 格式 401/403 响应(`JsonAuthHandlers`)
- [ ] Token 刷新机制(`POST /api/auth/refresh`)
- [ ] 从 Redis 读取角色与权限,避免 Token 膨胀
- [ ] 登出 / Token 黑名单
- [ ] 密码重置(邮箱验证码)
- [ ] 登录失败限流(同 IP / 同账号)

## nexus-forge-user

- [x] `User` 实体 + `BaseEntity` 继承
- [x] 用户注册服务
- [x] 当前用户查询(`GET /api/users/me`)
- [x] 修改个人资料(`PATCH /api/users/me`)
- [x] 修改密码(`POST /api/users/me/password`)
- [x] 头像上传 / 删除(`POST /api/users/me/avatar` + `DELETE /api/users/me/avatar`)
- [x] 单元测试(注册 / 更新资料 / 修改密码 / 实体默认值)
- [ ] 账号注销 / 封禁
- [ ] 第三方登录(预留扩展点)

## nexus-forge-file

### 架构决策

**统一使用 AWS S3 SDK 作为基础抽象** —— MinIO、阿里云 OSS、腾讯云 COS 均提供 S3 兼容接口,可通过 `endpoint` 切换实现零成本迁移。仅在需要厂商特有能力(如 OSS 图片处理回调)时再额外接入官方 SDK。

- **开发**:MinIO(本地 Docker) —— S3 SDK 接入,Console Web UI(`:9001`)调试
- **测试**:任意 S3 兼容服务 —— Ceph RGW / SeaweedFS / 自建 MinIO
- **生产**:阿里云 OSS / 腾讯云 COS / 自选 —— 优先走 S3 兼容模式,endpoint 切换

### StorageProvider SPI

- [x] 定义 `StorageProvider` 接口:`upload` / `download` / `delete` / `presignedUrl` / `exists`
- [x] 通过 `@ConfigurationProperties(prefix = "storage")` 选择实现
- [x] 统一配置:`endpoint` / `bucket` / `access-key` / `secret-key` / `region` / `path-style`

### MinIO 开发环境(本地)

- [x] Docker Compose 启动 MinIO(API `:9000` + Console `:9001`)
- [x] 启动脚本自动创建 bucket(`nexus-forge-dev`)
- [x] `S3StorageProvider` 实现(基于 `software.amazon.awssdk:s3`,兼容 MinIO)
- [x] `application-dev.yaml` 完整配置示例
- [ ] Console Web UI 调试入口文档

### 测试环境(S3 兼容)

- [ ] 通用 `S3CompatibleStorageProvider`(覆盖 Ceph RGW / SeaweedFS 等)
- [ ] `application-test.yaml` 配置模板

### 生产环境(阿里云 OSS / 腾讯云 COS)

- [x] 阿里云 OSS 适配:`endpoint = https://oss-cn-<region>.aliyuncs.com`
- [x] 腾讯云 COS 适配:`endpoint = https://cos-<region>.tencentcos.cn`
- [x] `application-prod.yaml` 配置模板(各 provider 切换示例)
- [ ] 若使用厂商特有能力,再单独接入官方 SDK(图片处理 / 回调 / 视频转码)

### 通用能力

- [x] `POST /api/files/upload` 通用上传接口(单文件 / 多文件)
- [x] 分片上传 / 秒传(基于文件 hash)/ 断点续传
- [x] 文件访问权限与临时签名 URL(presigned PUT + GET)
- [x] `FileClientImpl` 业务门面(供头像等场景调用)
- [ ] 图片处理(缩略图、水印)
- [ ] 文件元数据落库(`FileEntity` + `FileRepository`)
- [ ] 文件类型白名单 / 安全校验

## nexus-forge-ai

- [ ] LLM 网关抽象(`ChatModel` SPI)
- [ ] 多模型适配(OpenAI / Claude / Ollama / 国内模型)
- [ ] 流式响应(SSE / WebSocket)
- [ ] 对话上下文管理
- [ ] 向量检索 / RAG 接入
- [ ] Token 用量统计与限流

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

### AppLayout 布局骨架 🔥

> 当前 `AppLayout` 已有顶栏 + 内容区 + 路由过渡动画 + 侧边抽屉面板,但缺少**侧边导航菜单栏**和**面包屑**。目标是补齐"侧边栏(含导航菜单) + 顶栏(含面包屑 + 全局操作) + 主内容区"的三栏经典布局。

- [ ] 🔥 整体三栏布局(Sidebar + HeaderBar + Main),支持固定 / 自适应宽度
- [ ] 🔥 Sidebar 侧边栏:Logo + 系统名 + 主导航菜单(支持折叠 / 展开)
- [ ] 🔥 Sidebar 折叠状态持久化(Pinia + localStorage,刷新后保留)
- [ ] 🔥 Sidebar 当前路由高亮 + 嵌套子菜单(最多两级)
- [ ] 🔥 Sidebar 按 `Role` 过滤菜单项
- [ ] 🔥 HeaderBar 顶栏:面包屑导航(基于当前路由)
- [x] ✅ HeaderBar 全局操作区:用户头像下拉(个人中心 / 退出登录)
- [x] ✅ Main 内容区:路由切换过渡动画(Fade / Slide)
- [ ] 🟡 HeaderBar 主题切换按钮(明 / 暗)
- [ ] 🟡 HeaderBar 通知中心(预留接口)
- [ ] 🟡 响应式适配:窄屏(<768px)自动收起 Sidebar 为抽屉
- [ ] 🟡 Layout 状态 store(`useLayoutStore`: sidebarCollapsed、theme、breadcrumb)

### 业务页与权限

- [x] 个人中心页(`/profile` — 4 个子面板:基础/联系/通知/安全)
- [x] 头像上传组件(`AvatarUploader` + 后端对接)
- [ ] 🟡 业务首页(`/home`)占位 → 真实数据
- [ ] 🟡 路由级权限控制(基于 `Role`)
- [x] ✅ 401 / 403 统一处理(Axios 拦截器 + `auth:expired` 事件)
- [ ] 🟡 全局错误边界 + Loading 状态
- [ ] 🟢 国际化(i18n,中文 / 英文)

## 横切关注点

- [x] OpenAPI / Swagger 文档接入(springdoc-openapi 3.0.3, `/swagger-ui/index.html`)
- [x] 全局统一异常响应(`GlobalExceptionHandler` + `Result<T>`)
- [x] 单元测试: `user` 模块(4 个测试类,覆盖注册 / 更新 / 改密 / 实体)
- [ ] 集成测试:auth + user + file 端到端
- [ ] Docker Compose 一键起依赖(PostgreSQL / Redis / MinIO)
- [ ] CI:GitHub Actions 跑 lint + test + build
- [ ] 日志规范(SLF4J + Logback,JSON 结构化输出)
- [ ] 请求 / 响应日志脱敏(手机 / 邮箱 / Token)

---

## 来源

- README.md「待开发」章节
- 代码内 `// TODO` 扫描(2026-07-05 无残留)
- git log 已完成功能回溯
