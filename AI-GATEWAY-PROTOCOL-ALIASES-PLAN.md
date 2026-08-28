# nexus-forge-ai 第三方 LLM 协议别名实施计划

## 0. 背景与动机

Nexus Forge AI 网关目前对外只暴露 `/api/ai/chat/stream`(`AiStreamController`,返回 `text/event-stream` + `ChatChunk` JSON,内部 vendor-neutral 形状)。这对自家前端 / 自家 SDK 是最简路径,但**对生态里默认按 OpenAI / Anthropic 协议对接的工具**(Open WebUI、LobeChat、NextChat、各类 curl 脚本、LangChain `ChatOpenAI(base_url=...)` 等)**不友好**。

目标:**让 Nexus Forge 网关对"想假装是 OpenAI / Anthropic 兼容后端"的客户端直接可用**,而不是迫使它们学 Nexus Forge 自家形状。同时把"OpenAI 兼容"作为**已声明的产品契约**承担起来——一旦开了这层,OpenAI 协议的演进(structured outputs、reasoning_tokens、parallel tool calls 等)就变成**网关契约变更**,要按协议跟。

## 1. 设计原则

1. **内部域稳定,边界做协议翻译**。`ChatRequest` / `ChatResponse` / `ChatChunk` 保持 vendor-neutral,新增协议别名走"请求入参解析 → 域对象 → 现有 `LlmClient`/`ChatModel` 流水线 → 出参序列化"的标准路径。**不**让协议细节渗透进 SPI。
2. **协议契约显式声明,不带歧义**。每个新别名路径都注明"`声称兼容 <vendor> <protocol> <version>`",范围之外的字段一律忽略或返回 `400 unsupported_field`,而不是悄悄丢弃。
3. **同步路径先行,流式跟上**。先 `/v1/chat/completions` 同步 + 流式,跑通后再做 `responses` / `messages`。每条新路径都配 `*IT` 用真 MockWebServer / WireMock 验契约。
4. **鉴权与限流复用现有链**。别名路径走同一套 `JwtAuthenticationFilter`(query token 仅 SSE 仍由 `JwtQueryTokenFilter` 兜),`RateLimitGuard` / 偏好解析 / Vendor 路由全部透传,不动 `SecurityConfig`。
5. **多协议并存,可关可开**。每个协议别名一个 feature flag(`spring.ai.aliases.openai.enabled` 等),默认开,运营可在 `application-prod.yaml` 关掉某个别名。

## 2. 目标协议范围(可扩展)

| # | 路径 | 协议 | 类型 |
|---|---|---|---|
| 1 | `POST /v1/chat/completions` | OpenAI Chat Completions | 同步 + SSE 流式 |
| 2 | `POST /v1/responses` | OpenAI Responses API | 同步 + SSE 流式 |
| 3 | `POST /v1/messages` | Anthropic Messages | 同步 + SSE 流式 |
| 4 | `GET /v1/models` | OpenAI / Anthropic 共有 models 列表 | 同步 |
| 5 | (后续) `POST /v1/embeddings` | OpenAI Embeddings | 同步 |

> **不在范围**(显式排除,避免歧义):OpenAI Assistants API(自有 thread/run 模型,与 Nexus Forge 的对话模型不在同一抽象层)、OpenAI Audio / Images / Moderations、Anthropic Tool Use beta 头以外的私有 header 协议、`/v1/realtime`(WebSocket,与 SSE 协议栈差异大)。

## 3. 内部域 ↔ 协议映射表(快照)

以 Chat Completions / Responses / Messages 三方共有的"必须映射"字段起步;后续 OpenAI 演进字段(reasoning、audio、image)各自标注落点。

| 域字段 | OpenAI Chat Completions | OpenAI Responses | Anthropic Messages | Nexus Forge 内部 |
|---|---|---|---|---|
| 模型 | `model` | `model` | `model` | `ChatRequest.model` |
| 消息列表 | `messages[{role, content, name, tool_call_id}]` | `input`(string 或 `[{role, content:parts[]}]`) | `messages[{role, content:blocks[]}]` | `ChatRequest.messages` (`Role` 枚举) |
| 温度 | `temperature` | `temperature` | `temperature` | `ChatRequest.temperature` |
| 上限 token | `max_tokens` / `max_completion_tokens`(>=o1) | `max_output_tokens` | `max_tokens` | `ChatRequest.maxTokens` |
| 工具 | `tools[]` | `tools[]` | `tools[]` | `ChatRequest.tools` |
| 工具选择 | `tool_choice` | `tool_choice` | `tool_choice:{type,name}` / `disable_parallel_tool_use` | `ChatRequest.options["tool_choice"]` (待固化) |
| 流式 | `stream:true` | `stream:true` | `stream:true` | `ChatRequest.stream` |
| 增量文本 | `choices[0].delta.content` | `response.output_text.delta` (event) | `content_block_delta`(text 块) | `ChatChunk.deltaContent` |
| 工具增量 | `choices[0].delta.tool_calls[]` | `response.tool_call.delta` 等事件 | `content_block_start` tool_use 块 + `input_json_delta` | `ChatChunk.deltaToolCalls` |
| 终止原因 | `choices[0].finish_reason` | `response.done` event | `message_delta.stop_reason` | `ChatChunk.finishReason` |
| usage | `usage` 顶层 | `response.usage` 字段 | `message_delta.usage` / 顶层 `usage` | `ChatChunk.usage` / `ChatResponse.usage` |
| 终止符 | `data: [DONE]\n\n` | 不发 `[DONE]`,改发 `response.completed` event | 不发 `[DONE]`,改发 `message_stop` event | 同 wire 协议(透传) |

> **关键不变量**:内部 `ChatChunk` 仍是 vendor-neutral,出参序列化器只负责"翻译"。后续如果加 reasoning、audio、image 字段,在 `ChatChunk` 加平铺字段 + 各 wire 序列化器映射,**不让 wire 形状渗透进 `ChatChunk`**。

## 4. 分期(粗排)

> 排期单位"周"是相对量级(单人 ~70% 投入),具体到天要等 PR review 时确认。

### P6.0 — 协议别名基础设施(1 周)

#### 新增

- `nexus-forge-ai/protocol/` 子包,放"协议无关"的别名框架:
  - `ProtocolControllerSupport` — `@RequestMapping` 通用 helper,把 vendor auth / 限流 / 偏好解析串起来。
  - `AliasRegistry` — `@Component`,注册"路径 → 协议"映射表;启动期扫描所有 `@RestController` 类,自动挂路径白名单到 `SecurityConfig`(避免每个协议手动改 SecurityConfig)。
  - `ProtocolErrorMapper` — 把内部 `LlmException` / `ResultCode` 翻译成各 wire 协议的错误信封(OpenAI `{error:{message,type,code,param}}`,Anthropic `{type:"error", error:{type,message}}`)。
- `AiProperties.Aliases` 段(`enabled`,`basePath: "/v1"`),feature flag 控制总开关与子协议开关。

#### 改动

- `SecurityConfig`:用 `AliasRegistry` 动态注册 `/v1/**` 放行(具体路径白名单保留),避免重复写 `.requestMatchers("/v1/chat/completions", "/v1/responses", "/v1/messages", "/v1/models").permitAll()`。
- `nexus-forge-web/src/main/resources/application-dev.yaml`:加 `spring.ai.aliases.openai.enabled: true` 等,默认开。

#### 测试

- `AliasRegistryTest` — 验证启动期路径扫描无重复注册、无循环依赖。
- `ProtocolErrorMapperTest` — 给一组 `LlmException` 子类型,断言每个 wire 协议序列化形状稳定(用 JSON snapshot)。

### P6.1 — OpenAI Chat Completions(2 周)

#### 新增

- `nexus-forge-ai/protocol/openai/`
  - `OpenAiChatCompletionsController`(`POST /v1/chat/completions`)
  - `OpenAiRequestAdapter` — OpenAI wire → `ChatRequest`(`messages[].role` 映射,`tool_choice` 落 `options`,`stream` 字段路由到 controller 内部分支)
  - `OpenAiResponseSerializer` — `ChatResponse` → `{id, object:"chat.completion", created, model, choices[], usage}`
  - `OpenAiStreamFrameSerializer` — `Flux<ChatChunk>` → `{id, object:"chat.completion.chunk", model, choices:[{delta:{content,tool_calls[]}, finish_reason, index}], usage?}` + 终止帧 `data: [DONE]\n\n`
  - `OpenAiModelsController`(`GET /v1/models`)— 把 `ChatModel.name()` + 当前可用 vendor/model 列表映射成 `{object:"list", data:[{id,object:"model",created,owned_by}]}`

#### 改动

- `OpenAiJsonMapper` 不动(已经是上游协议 → `ChatRequest`);新增的 `OpenAiRequestAdapter` 是**网关出参方向**的协议转换器,与 mapper 解耦。

#### 测试

- `OpenAiChatCompletionsControllerIT` — 用 `MockWebServer` mock 一个"原版 OpenAI"风格的请求体,过网关,断言:
  - 同步响应字段全部对齐 OpenAI v1(`choices[0].message.role == "assistant"`,`finish_reason == "stop"` 等)。
  - SSE 流式响应每帧 `object=="chat.completion.chunk"`,终止帧是 `[DONE]`。
  - 错误响应形状为 `{error:{message, type, code, param}}`,HTTP 状态码按 OpenAI 约定(400 / 401 / 429 / 500 / 503)。
- `OpenAiModelsControllerIT` — `GET /v1/models` 返回的 `data[]` 至少包含每个 enabled vendor 的 default model。
- 兼容性验证(手动 + 文档):用 LangChain `ChatOpenAI(base_url="http://localhost:8080/v1", api_key="<jwt>")` 真接一次,跑一次 `chat.invoke("hi")` 与 `chat.stream("hi")`。

### P6.2 — OpenAI Responses API(2 周)

> Responses API 是 OpenAI 2025 推的新协议,**用 `input` 替代 `messages`,事件类型更多(`response.created` / `response.output_item.added` / `response.output_text.delta` / `response.function_call_arguments.delta` / `response.completed`)**,与 Chat Completions 不完全兼容。要做就做正,不能"半兼容"。

#### 新增

- `nexus-forge-ai/protocol/openai/OpenAiResponsesController`(`POST /v1/responses`,`POST /v1/responses/{response_id}` 取历史如果需要)
- `OpenAiResponsesRequestAdapter` — `input` 是 string 或 items 数组时映射为单条 / 多条 `ChatMessage`;`instructions` → system message。
- `OpenAiResponsesStreamSerializer` — 把 `Flux<ChatChunk>` 翻译成 Responses API 的事件序列(`response.created` 一次 → `response.output_item.added`(text 项)→ `response.output_text.delta` × N → `response.output_item.done` → `response.completed` + usage),无 `[DONE]`。
- 同步响应包络:`{id, object:"response", status, output:[{type:"message", role:"assistant", content:[{type:"output_text", text}]}], usage}`。

#### 测试

- `OpenAiResponsesControllerIT` — 对照 OpenAI 官方文档的事件流 fixture 录一遍,逐事件断言 schema。
- 兼容验证:用官方 `openai` Python SDK(设 `base_url=http://localhost:8080/v1`)走 `client.responses.create(model="...", input="hi", stream=True)`,肉眼对比事件序列。

### P6.3 — Anthropic Messages(1.5 周)

> Anthropic Messages 协议特征:`max_tokens` 必填(OpenAI 是可选)、`content` 是 `[{type:"text"|"image"|"tool_use"|"tool_result"}]` 数组、SSE 事件类型(`message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`)、无 `[DONE]`,错误信封装 `{type:"error", error:{type, message}}`。

#### 新增

- `nexus-forge-ai/protocol/anthropic/AnthropicMessagesController`(`POST /v1/messages`)
- `AnthropicRequestAdapter` — `messages[].content` 数组拆分为 `ChatMessage`;`system` 字段落为第一条 system message;`max_tokens` 必填校验(缺失 → 400 `{type:"error", error:{type:"invalid_request_error", message:"max_tokens is required"}}`)。
- `AnthropicStreamFrameSerializer` — `message_start`(role + model + id + usage=0)→ `content_block_start`(text 块 index=0)→ `content_block_delta` × N(`delta:{type:"text_delta", text:"..."}`)→ `content_block_stop` → `message_delta`(stop_reason + final usage)→ `message_stop`(无 `[DONE]`)。

#### 测试

- `AnthropicMessagesControllerIT` — MockWebServer 回放一段标准 Anthropic SSE 流,反向验证(网关入参 → 网关出参 wire)形状;同时正向录一遍("Anthropic 官方 SDK + 网关"端到端)。
- `AnthropicMissingMaxTokensTest` — 入参缺 `max_tokens` 时返回 400 错误信封符合 Anthropic 规范。

### P6.4 — Models 列表统一化(0.5 周,与 P6.1 部分重叠)

`GET /v1/models` 在 OpenAI / Anthropic 上字段略有差异(Anthropic 无 `created`/`owned_by`,有 `display_name`)。**做一个网关内部 `ModelDescriptor` 中间层**,OpenAI 侧字段缺省时填合理值,Anthropic 侧把 `display_name` 映射到 `id` 并标注 `owned_by="anthropic"`。

#### 新增

- `nexus-forge-ai/protocol/ModelsController` — 单一端点,内部根据 `Accept` / `User-Agent` 或固定路径(`/v1/models` vs `/v1/models` 兼容两者)返回不同形状。
- 或更简单:两个 controller 分别注册,共用 `ModelDescriptor`。

### P6.5 — 鉴权 / API Key 模式(0.5 周,作为 P6.1 的前导或同步)

> OpenAI / Anthropic 客户端默认是 `Authorization: Bearer <api_key>`,但 Nexus Forge 是 JWT。要让 LangChain / Open WebUI 直接对接,**网关必须接受并校验一个"代理 API key"**——可以复用现有 JWT(用户体验一致)或额外发一个 `x-api-key`。

候选方案(待选型,PR 前确认):

- **方案 A(推荐)**:客户端继续用 `Authorization: Bearer <jwt>`,文档明确"在 Nexus Forge 网关前请把用户 JWT 当作 API key 用"。LangChain `ChatOpenAI(api_key="<jwt>")` 直接通。
- **方案 B**:新增 `POST /api/auth/exchange-api-key`(已登录用户拿 JWT 后可换出一个长期 `nfk_xxx` API key),网关接受 `Authorization: Bearer nfk_xxx` 或 `x-api-key: nfk_xxx`。更贴近 OpenAI 习惯,但多一份存储与轮换逻辑。

建议 P6.5 先做方案 A,后续在产品决策明确后做方案 B。

### P6.6 — 文档与兼容性矩阵(0.5 周)

- `README.md` 加"协议别名"章节,列出每个别名路径与"声称兼容范围"。
- `docs/AI-GATEWAY-PROTOCOL-ALIASES.md`(从本计划抽出验收章节,git-tracked)沉淀 wire schema 对照表与"不在范围"清单。
- 兼容性验证脚本 `bin/verify-protocol-compat.sh`:起一个 mock 上游 + curl 跑每个路径,把响应 dump 到 `build/protocol-compat/`,CI 跑一次确保没回归。

## 5. 关键文件清单(汇总)

### 新增

- `nexus-forge-ai/src/main/java/com/nexusforge/protocol/`
  - `ProtocolControllerSupport.java`
  - `AliasRegistry.java`
  - `ProtocolErrorMapper.java`
  - `ModelDescriptor.java`
- `nexus-forge-ai/src/main/java/com/nexusforge/protocol/openai/`
  - `OpenAiChatCompletionsController.java`
  - `OpenAiResponsesController.java`
  - `OpenAiModelsController.java`
  - `OpenAiRequestAdapter.java`
  - `OpenAiResponseSerializer.java`
  - `OpenAiStreamFrameSerializer.java`
  - `OpenAiResponsesStreamSerializer.java`
- `nexus-forge-ai/src/main/java/com/nexusforge/protocol/anthropic/`
  - `AnthropicMessagesController.java`
  - `AnthropicRequestAdapter.java`
  - `AnthropicStreamFrameSerializer.java`
- 测试(每个 controller 一个 `*IT.java` + 序列化器各 1 个单元测试)

### 改动

- `nexus-forge-ai/src/main/java/com/nexusforge/config/AiProperties.java` — `Aliases` 子段
- `nexus-forge-auth/src/main/java/com/nexusforge/config/SecurityConfig.java` — `/v1/**` 走 `AliasRegistry` 动态白名单
- `nexus-forge-web/src/main/resources/application-*.yaml` — alias 默认值
- `README.md` — 新增章节

### 不改

- `ChatRequest` / `ChatResponse` / `ChatChunk` — 保持 vendor-neutral
- `ChatModel` SPI 与现有 OpenAI/Anthropic/DeepSeek/Qwen 实现 — 不动
- `LlmClient` — 不动;`callWithToolLoop` / `stream` 现有契约不变
- `OpenAiJsonMapper` / `AnthropicJsonMapper` — 不动(它们是**入站** wire → 域对象;新增的 `RequestAdapter` 是**出站** 域对象 → wire,职责相反)

## 6. 兼容性边界(必须显式声明)

> 一旦开了 `POST /v1/chat/completions`,任何兼容性问题都是 Nexus Forge 的契约违约。每个别名路径的 README 都引用本节。

| 项目 | OpenAI Chat Completions | OpenAI Responses | Anthropic Messages |
|---|---|---|---|
| 模型发现 | ✅ `/v1/models` | ✅ `/v1/models` | ✅ `/v1/models`(字段降级) |
| 同步调用 | ✅ | ✅ | ✅ |
| SSE 流式 | ✅ | ✅ | ✅ |
| Tools(含 function calling) | ✅ | ✅ | ✅ |
| Tool use 增量流 | ✅ | ✅(多事件) | ✅ |
| 多模态(image input) | ⏸ P7+ | ⏸ P7+ | ⏸ P7+ |
| Structured outputs(`response_format`) | ⏸ P7+(域层需先固化) | ⏸ P7+ | N/A |
| reasoning_tokens(o1 类) | ⏸ P7+ | ⏸ P7+ | ⏸ P7+ |
| Prompt caching | ❌(OpenAI 自动,网关不感知) | ⏸ P7+ | ⏸ P7+ |
| Assistants API(thread/run) | ❌ 不在范围 | N/A | N/A |
| Real-time WebSocket | ❌ 不在范围 | N/A | N/A |
| Batch API | ❌ 不在范围 | N/A | N/A |

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| OpenAI 协议演进快,做了一半过半年又出新事件类型 | 中 | P6.6 兼容性矩阵**按版本钉**;每次 OpenAI 大版本变更走一次 PR 评估;失败模式:`{error: {type: "unsupported_version"}}` + 文档跳转 |
| 协议翻译造成额外延迟(请求 + 出参两次序列化) | 低 | 出参序列化用 Jackson + 预热;压测中观测(在 P5 Step 8 metrics 上加 alias 维度) |
| 协议别名与 `/api/ai/*` 自家 API 风格不一致,前端/SDK 切哪边? | 中 | 文档明示:`/api/ai/*` 继续是 Nexus Forge 自家契约(权限/特性更全),`/v1/*` 是兼容外部客户端的薄壳;新增字段先在自家 API,稳定后再考虑别名 |
| 鉴权模式(方案 A vs B)未定,接口可能改 | 中 | P6.5 在 P6.1 之前定;选 A 就文档说"用 JWT 当 API key",选 B 在 controller 加 `x-api-key` 支持且向后兼容 |
| 现有 `ChatChunk` 平铺字段遇到 Responses API 多事件时不够用 | 中 | 在 P6.2 实施时审视是否要扩展 `ChatChunk` 字段(如 `outputIndex`、`contentBlockIndex`),域层加字段不动 SPI 形状 |

## 8. 验收(每期 P6.x 通用)

1. `./gradlew :nexus-forge-ai:test --tests <新 controller IT>` BUILD SUCCESSFUL
2. `./gradlew :nexus-forge-ai:test`(全量)BUILD SUCCESSFUL,无回归
3. 真实第三方客户端实测:
   - OpenAI: `curl -X POST http://localhost:8080/v1/chat/completions -H "Authorization: Bearer $JWT" -d '{"model":"qwen3-27b","messages":[{"role":"user","content":"hi"}]}'` 返回符合 OpenAI v1 schema 的 JSON
   - OpenAI SSE: `curl -N ... -d '{"stream":true,...}'` 收到 `data: {...}\n\n` 序列与 `data: [DONE]\n\n` 终止帧
   - LangChain `ChatOpenAI(base_url="http://localhost:8080/v1", api_key="$JWT").invoke("hi")` 直接通
   - Anthropic: 类似,验证 `message_start` / `content_block_delta` / `message_stop` 事件序列
4. `bin/verify-protocol-compat.sh` 跑通,响应 dump 与基线对比无回归

## 9. 不在本计划范围(显式排除,避免范围蔓延)

- OpenAI Assistants / Threads / Realtime(协议模型差异,与 `ConversationService` 抽象不对齐)
- OpenAI Audio (`/v1/audio/*`)、Images(`/v1/images/*`)、Moderations、`/v1/files`(不在 AI 网关抽象里)
- Anthropic Tool Use 的私有 beta header(`anthropic-beta: ...`)
- Prompt caching / structured outputs / reasoning(等 `ChatChunk` 域层先固化,本计划只搭桥)
- 多模态 image input(等 Nexus Forge 自家 `/api/ai/chat` 先支持再复制到别名)
- API Key 兑换(方案 B)— P6.5 仅落方案 A;B 单独 PR

## 10. 后续延伸(本计划完成后另立)

- `/v1/embeddings`(OpenAI Embeddings)— 等向量检索(RAG)落地后接
- Anthropic `/v1/messages/batches`(Batch API)
- Google Gemini API(协议差异较大,需要新一类适配)
- Cohere Rerank / Embed