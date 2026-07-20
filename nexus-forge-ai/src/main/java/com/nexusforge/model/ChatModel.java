package com.nexusforge.model;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * LLM 提供者 SPI 标准接口
 * 所有 ChatModel 大模型对接实现类必须遵守以下规范约束:
 * <ul>
 *   <li>{@link #call(ChatRequest)} 同步对话接口为阻塞调用；
 *       P1 版本内部可使用 java.net.http.HttpClient 发起请求，
 *       P2 及以上版本推荐使用 WebClient（由各实现自主选型，外层门面 LlmClient 统一管控超时与请求取消逻辑）。</li>
 *   <li>接口实现出现业务/网络/模型报错时，统一抛出 {@link LlmException} 自定义异常，禁止抛出原生第三方异常。</li>
 *   <li>{@link #name()} 返回当前模型厂商唯一标准标识字符串，示例：{@code openai}/{@code anthropic}/{@code ollama}。</li>
 * </ul>
 */
public interface ChatModel {

    /**
     * 获取当前大模型服务商唯一规范名称
     * @return 厂商标准标识，如 openai、anthropic、ollama
     */
    String name();

    /**
     * 查询当前模型支持的功能能力集
     * 包含流式输出、工具调用、识图、JSON结构化输出等能力标识
     * @return 模型能力描述实体 {@link ChatCapabilities}
     */
    ChatCapabilities capabilities();

    /**
     * 同步阻塞对话请求
     * 一次性获取完整模型返回结果，无分段流式数据
     * @param request 对话入参请求体
     * @return 完整对话响应对象
     * @throws LlmException 调用异常、参数错误、模型服务异常统一抛出该异常
     */
    ChatResponse call(ChatRequest request);

    /**
     * 流式对话接口（P2 版本主推）
     * 返回 Flux 分段数据流，实现打字机逐字输出效果；
     * P1 旧版本若不支持流式能力，可直接返回 Flux.error 抛出不支持异常
     * @param request 对话入参请求体
     * @return 分段输出数据块流式序列
     */
    Flux<ChatChunk> stream(ChatRequest request);
}