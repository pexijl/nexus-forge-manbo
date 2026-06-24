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

## 🔥 当前冲刺(2026-06-24 ~)

> 在做完整模块清单前,先打掉这一波"高 ROI、低风险"的局部任务,目标是形成"修改个人信息 → 自动出文档"的端到端闭环。

### 🟢 第 1 优先级(强烈建议先做)

**主题:补齐 `/users/me` 写类接口 + 个人中心页 + 接口文档**

- [ ] 后端:`PATCH /users/me`(昵称 / 头像 / 邮箱 / 手机)
- [ ] 后端:`POST /users/me/password`(改密码,校验旧密码)
- [ ] 后端:顺手清掉 `Result.java:40,49` 的 magic number TODO
- [ ] 前端:`/profile` 个人中心页(展示 + 修改表单,Zod 校验)
- [ ] 横切:接入 `springdoc-openapi`,Swagger UI 挂 `/swagger-ui.html`

### 🟡 第 2 优先级(有空接着做)

**主题:登录态与权限加固**

- [ ] 后端:`POST /auth/refresh`(access + refresh 双 Token)
- [ ] 后端:登出 / Token 黑名单
- [ ] 后端:`JwtAuthenticationFilter:65` 改为从 Redis 读权限
- [ ] 前端:axios 拦截器补充 401 / 403 → 自动跳登录
- [ ] 前端:Vue Router `beforeEach` 路由级权限守卫(基于 `Role`)
- [ ] 前端:主题切换按钮(明 / 暗,与 AppLayout 联动)

### ⚪ 第 3 优先级(等业务明确再做)

- [ ] Docker Compose 一键起 PostgreSQL + Redis
- [ ] GitHub Actions CI(lint + test + build)
- [ ] 单元测试起步(`UserService` / `JwtUtil` 核心用例)
- [ ] i18n(没有海外用户前不做)

### 🚫 先别碰

- `nexus-forge-file` / `nexus-forge-ai` / `nexus-forge-visual` —— 业务方向不明,搭出来也是空架子
- `nexus-forge-core` 基础设施(幂等、限流、审计) —— 缺乏业务压力,容易过度设计
- `BaseEntity` 软删除字段 —— 取决于"账号注销"是否进入产品范围

---

## nexus-forge-common

- [ ] `Result.success(String, T)` 与 `Result.fail(String)` 中的 magic number(`200` / `500`)替换为 `ResultCode` 常量
- [ ] 抽取 `PageResult<T>` 统一分页响应
- [ ] 抽取 `BaseEntity` 软删除字段(`deleted`、`deletedAt`)

## nexus-forge-core

- [ ] 请求日志中间件(请求 ID + MDC 链路追踪)
- [ ] 统一幂等组件(`@Idempotent` 注解 + Redis Token)
- [ ] 限流组件(基于 Redis 滑动窗口)
- [ ] 分布式锁抽象(本地 / Redis 可切换)
- [ ] 操作审计(`@Auditable` 注解 + 异步落库)

## nexus-forge-auth

- [x] 用户注册(`POST /auth/register`)
- [x] 用户登录 / JWT 签发(`POST /auth/login`)
- [x] `JwtAuthenticationFilter` 解析 Token 写 `SecurityContext`
- [x] `UserPrincipal` 统一认证主体
- [ ] Token 刷新机制(`POST /auth/refresh`)
- [ ] 从 Redis 读取角色与权限,避免 Token 膨胀(`JwtAuthenticationFilter:65`)
- [ ] 登出 / Token 黑名单
- [ ] 密码重置(邮箱验证码)
- [ ] 登录失败限流(同 IP / 同账号)

## nexus-forge-user

- [x] `User` 实体 + `BaseEntity` 继承
- [x] 用户注册服务
- [x] 当前用户查询(`GET /users/me`)
- [ ] 修改个人资料(`PATCH /users/me`)
- [ ] 修改密码(`POST /users/me/password`)
- [ ] 头像上传(对接 `nexus-forge-file`)
- [ ] 账号注销 / 封禁
- [ ] 第三方登录(预留扩展点)

## nexus-forge-file

### 架构决策

**统一使用 AWS S3 SDK 作为基础抽象** —— MinIO、阿里云 OSS、腾讯云 COS 均提供 S3 兼容接口,可通过 `endpoint` 切换实现零成本迁移。仅在需要厂商特有能力(如 OSS 图片处理回调)时再额外接入官方 SDK。

- **开发**:MinIO(本地 Docker) —— S3 SDK 接入,Console Web UI(`:9001`)调试
- **测试**:任意 S3 兼容服务 —— Ceph RGW / SeaweedFS / 自建 MinIO
- **生产**:阿里云 OSS / 腾讯云 COS / 自选 —— 优先走 S3 兼容模式,endpoint 切换

### StorageProvider SPI

- [ ] 定义 `StorageProvider` 接口:`upload` / `download` / `delete` / `presignedUrl` / `exists`
- [ ] 通过 `@ConditionalOnProperty(prefix = "nexus-forge.file", name = "provider")` 选择实现
- [ ] 统一配置:`endpoint` / `bucket` / `access-key` / `secret-key` / `region` / `path-style`

### MinIO 开发环境(本地)

- [ ] Docker Compose 启动 MinIO(API `:9000` + Console `:9001`)
- [ ] 启动脚本自动创建 bucket(`nexus-forge-dev`)
- [ ] `MinIOStorageProvider` 实现(基于 `software.amazon.awssdk:s3`)
- [ ] `application-dev.yaml` 完整配置示例
- [ ] Console Web UI 调试入口文档

### 测试环境(S3 兼容)

- [ ] 通用 `S3CompatibleStorageProvider`(覆盖 Ceph RGW / SeaweedFS 等)
- [ ] `application-test.yaml` 配置模板

### 生产环境(阿里云 OSS / 腾讯云 COS)

- [ ] 阿里云 OSS 适配:`endpoint = https://oss-cn-<region>.aliyuncs.com`
- [ ] 腾讯云 COS 适配:`endpoint = https://cos-<region>.tencentcos.cn`
- [ ] `application-prod.yaml` 配置模板(各 provider 切换示例)
- [ ] 若使用厂商特有能力,再单独接入官方 SDK(图片处理 / 回调 / 视频转码)

### 通用能力

- [ ] `POST /files/upload` 通用上传接口(单文件 / 多文件)
- [ ] 分片上传 / 秒传(基于文件 hash)/ 断点续传
- [ ] 图片处理(缩略图、水印)
- [ ] 文件访问权限与临时签名 URL(presigned)
- [ ] 文件元数据落库(`FileEntity` + `FileRepository`)

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
- [x] 主题(PrimeVue + Tailwind)
- [x] 登录 / 注册页 + Zod 校验
- [x] Pinia `auth store` + 持久化

### AppLayout 布局骨架 🔥

> 当前 `src/layout/AppLayout.vue` 是占位空壳,需补齐侧边栏、顶栏、主内容区三大区域。
> 可参考阿里云百炼平台的布局设计,但不必完全照搬,可根据实际业务需求调整。顶栏+主内容区, 顶栏=折叠按钮+面包屑+全局操作区(用户头像下拉+主题切换+通知中心),主内容区=路由出口+过渡动画。
> 菜单将在点击折叠按钮后抽出侧边栏,自动收起

- [ ] 🔥 整体三栏布局(Sidebar + HeaderBar + Main),支持固定 / 自适应宽度
- [ ] 🔥 Sidebar 侧边栏:Logo + 系统名 + 主导航菜单(支持折叠 / 展开)
- [ ] 🔥 Sidebar 折叠状态持久化(Pinia + localStorage,刷新后保留)
- [ ] 🔥 Sidebar 当前路由高亮 + 嵌套子菜单(最多两级)
- [ ] 🔥 Sidebar 按 `Role` 过滤菜单项
- [ ] 🔥 HeaderBar 顶栏:面包屑导航(基于当前路由)
- [ ] 🔥 HeaderBar 全局操作区:用户头像下拉(个人中心 / 修改密码 / 退出登录)
- [ ] 🔥 HeaderBar 主题切换按钮(明 / 暗)
- [ ] 🟡 HeaderBar 通知中心(预留接口)
- [ ] 🟡 Main 内容区:路由切换过渡动画(Fade / Slide)
- [ ] 🟡 响应式适配:窄屏(<768px)自动收起 Sidebar 为抽屉
- [ ] 🟡 Layout 状态 store(`useLayoutStore`:sidebarCollapsed、theme、breadcrumb)

### 业务页与权限

- [ ] 个人中心页(消费 `/users/me`)
- [ ] 业务首页(`/home`)占位 → 真实数据
- [ ] 路由级权限控制(基于 `Role`)
- [ ] 401 / 403 统一处理
- [ ] 全局错误边界 + Loading 状态
- [ ] 国际化(i18n,中文 / 英文)

## 横切关注点

- [ ] OpenAPI / Swagger 文档接入(springdoc-openapi)
- [ ] 全局统一异常响应(已部分完成,需覆盖更多场景)
- [ ] 单元测试覆盖率 ≥ 60%(核心 service / util)
- [ ] 集成测试:auth + user 端到端
- [ ] Docker Compose 一键起依赖(PostgreSQL / Redis / MinIO)
- [ ] CI:GitHub Actions 跑 lint + test + build
- [ ] 日志规范(SLF4J + Logback,JSON 结构化输出)

---

## 来源

- README.md「待开发」章节
- 代码内 `// TODO` 扫描(2026-06-23):
  - `nexus-forge-auth/.../JwtAuthenticationFilter.java:65`
  - `nexus-forge-common/.../Result.java:40, 49`
- git log 已完成功能回溯