# Nexus Forge UI

Vue 3 + TypeScript + Vite + Tailwind CSS + PrimeVue + SCSS。

> 与后端模块解耦的独立前端工程。后端文档见仓库根 `README.md` / `AGENTS.md` / `docs/ROADMAP.md` / `docs/NEXT-STEPS.md`。
>
> **当前进度(2026-09-02)**: 登录 / 注册 / 个人中心 / 三栏布局骨架 / 路由过渡动画 / Axios 拦截器 / Pinia AES 持久化 已就绪;
> **AI 业务页(聊天 / 偏好 / admin)未接入**(`src/api/` 当前只有 `auth.ts` / `user.ts`),详见 `docs/NEXT-STEPS.md` P1。

---

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 框架 | Vue | 3.5 |
| 语言 | TypeScript | 5.x |
| 构建 | Vite | 8.x |
| 路由 | Vue Router | 4.x |
| 状态管理 | Pinia + `pinia-plugin-persistedstate` | 3.x(AES 加密持久化) |
| UI 组件库 | PrimeVue + PrimeVue Forms | 5.x(锁定 `^5.0.0-rc.1`,稳定前严格锁) |
| 主题 | PrimeUIX Themes + 自定义 `MyPreset` | 5.x |
| 样式 | Tailwind CSS + SCSS 设计令牌 | 4.x |
| 网络 | Axios(请求取消、401/403 拦截、413 处理) | 最新 |
| 校验 | Zod | 4.x |
| 代码质量 | ESLint(v10 flat config)+ vue-tsc | 10.x |

---

## 快速开始

### 环境要求

- Node.js ≥ 20(CI 跑 Node 22)
- 后端服务跑在 `localhost:8080`(`/api` 代理到 Vite 开发服务器)

### 安装与启动

```bash
cd nexus-forge-ui
npm install     # CI 用 npm install 而非 npm ci(lockfile 漂移)
npm run dev     # Vite 开发服务器,默认 http://localhost:5173
```

### 常用脚本

| 脚本 | 作用 |
|---|---|
| `npm run dev` | Vite 开发服务器(默认端口 5173,HMR) |
| `npm run build` | `vue-tsc -b && vite build`(类型检查 + 生产构建到 `dist/`) |
| `npm run lint` | ESLint flat config 校验 |
| `npm run format` | Prettier 作用于 `src/**/*.{vue,ts,js,css,scss,json}` |

### 环境变量

- 通过 `.env.local` / `.env.development` / `.env.production` 注入(均 gitignore)
- 仅 `VITE_*` 前缀的变量会暴露给前端代码
- 当前使用:`VITE_SECRET_KEY`(Pinia AES 持久化主密钥,推荐 ≥32 字节)

---

## 目录结构

```
src/
├── api/                  # 后端接口封装
│   ├── auth.ts           # 登录 / 注册 / 刷新 / 登出
│   └── user.ts           # 当前用户 / 改资料 / 改密 / 头像 / 注销
├── composables/          # 组合式函数
│   ├── useAuthBoot.ts    # main.ts 调用 bootstrapAuth().finally(mount),负责 token 刷新预检 + auth store 水合
│   └── useDateFormat.ts  # 日期格式化工具
├── components/           # 通用组件(unplugin-vue-components 自动导入,不要手写 import)
│   ├── AvatarUploader.vue
│   └── effects/ParticleCanvas.vue
├── layout/               # 应用布局
│   ├── AppLayout.vue
│   └── components/
│       ├── AppSidePanel.vue
│       └── AppToolbar.vue
├── router/               # 路由配置
│   ├── index.ts          # 路由守卫(受保护路由重定向到 auth-view?tab=login&redirect=...)
│   └── routes/
│       ├── auth.ts
│       ├── home.ts
│       ├── user.ts
│       └── index.ts
├── stores/               # Pinia 状态
│   ├── auth.ts           # 令牌状态 + AES 持久化 + 单飞刷新 + ensureFreshAccess()
│   └── layout.ts
├── styles/               # SCSS 设计令牌(基础 / 工具 / 主题)
│   ├── base/_tokens.scss
│   ├── components/
│   └── main.scss
├── themes/               # PrimeVue 主题定制
│   ├── colors.ts
│   ├── components.ts
│   ├── semantic.ts
│   └── index.ts          # 导出 MyPreset(在 main.ts 注册)
├── types/                # TS 类型
│   ├── api.ts            # Axios 响应包装
│   ├── auth.ts           # TokenBundle / TokenSlot / LoginRequest / RegisterRequest
│   ├── router.d.ts       # Vue Router 模块声明
│   ├── vite-env.d.ts     # import.meta.env 类型
│   └── models/user.ts    # UserInfo / UpdateUserInfo
├── utils/                # 工具函数
│   ├── error.ts
│   └── http/             # Axios 封装
│       ├── cancel.ts     # AbortController / fetch 流式
│       ├── errors.ts     # 业务错误码 → 类型化错误
│       ├── index.ts      # Axios 实例
│       └── interceptors.ts # 注入 Bearer / 401 时刷新一次 / 错误映射 / auth:expired 事件
└── views/                # 页面(按业务模块划分子目录)
    ├── auth/             # AuthView + LoginForm / RegisterForm
    ├── home/HomeView.vue
    └── user/             # ProfileView + 4 个面板(Basic / Contact / Notification / Security)
```

---

## 关键约定

- **`src` 导入用 `@`**;tsconfig 还定义了 `@utils/*` 和 `@components/*`。
- **后端调用放在 `src/api/`**;传输行为放在 `src/utils/http/`;认证调用不要绕过配置好的 Axios 客户端。
- **认证状态属于 `stores/auth.ts`**;路由保护属于 `router/index.ts`。
- **PrimeVue 表单用 Zod schema**,Vue 组件用类型化 props/emits。
- **样式使用 `src/styles/base/_tokens.scss` 的 SCSS/CSS 令牌和主题覆盖**;避免在令牌/主题文件之外使用原始颜色字面量。
- **PrimeVue 组件经 `unplugin-vue-components` 自动导入**;生成的 `components.d.ts` 不要手工编辑。
- **Prettier 约定**: 分号、单引号、2 空格缩进、有效处尾随逗号、LF 行尾。
- **`.gitattributes` 对 Vue / SCSS / CSS 文件强制 LF**。

### 鉴权流程(关键时序)

1. `main.ts` 启动后,**挂载前**先调 `useAuthBoot.ts#bootstrapAuth()`:
   - 从 `localStorage` 恢复 token 状态(AES 解密)
   - 检查 access Token 剩余有效期,过期则用 refresh Token 换发新双 Token
   - 水合 auth store(`userInfo` / `roles`)
2. `app.mount('#app')` 在 `bootstrapAuth().finally(...)` 之后才执行 — 避免首屏渲染时拿到旧 auth state
3. Axios 拦截器(请求 / 响应):
   - 请求头自动注入 `Authorization: Bearer <accessToken>`
   - 401 时尝试刷新一次,失败派发 `auth:expired` 事件,前端路由守卫跳登录
   - 业务错误码(`Result.code !== 0`)映射为类型化错误(在 `utils/error.ts`)

### 与后端的对接点

- **Vite 代理**(`vite.config.ts`):`/api` 转发到 `http://localhost:8080`
- **统一响应**: 后端 `Result<T>`,前端在 `utils/http/interceptors.ts` 解包到 `data.data`
- **错误码**: 业务错误码集中在后端 `ResultCode`,前端按 `code` 判定具体业务结果
- **鉴权**: 业务路径需 `Authorization: Bearer <token>`,登录 / 注册 / 刷新 / Swagger 路径公开
- **SSE 流式**: `src/api/ai.ts` 计划用原生 `fetch` + `ReadableStream` + `AbortController`(Axios 不适合 SSE)

---

## 待接入(详见 `docs/NEXT-STEPS.md` P1)

- [ ] `src/api/ai.ts` — 同步 / 流式 / 对话 / 偏好 / 代理 / alias / 用量 / 模型目录
- [ ] `src/views/ai/ChatView.vue` — 聊天页(同步 + SSE 流式)
- [ ] `src/views/ai/PreferenceView.vue` — 偏好 / 私 Key / 代理 / model alias
- [ ] `src/views/admin/*` — admin UI(全局默认 / 模型目录 / vendor 配置 / 降级链 / apiKey 轮换审计)
- [ ] 路由级 Role 守卫(`router/index.ts` 按 `auth.roles` 过滤菜单)
- [ ] 业务首页(`/home`)真实数据(当前为占位)
- [ ] i18n 国际化(中文 / 英文)

---

## 相关文件

- `vite.config.ts` — `/api` 代理 + `@` 别名 + PrimeVue 插件注册
- `tsconfig.json` / `tsconfig.app.json` / `tsconfig.node.json` — 路径别名 + 严格模式
- `package.json` — 依赖 + 脚本
- `eslint.config.js` — ESLint 10 flat config
- `.prettierrc` — Prettier 配置
