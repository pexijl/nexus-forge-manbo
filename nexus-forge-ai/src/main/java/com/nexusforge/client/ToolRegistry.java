package com.nexusforge.client;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolExecutor} 注册中心。
 *
 * <p>Spring 启动时把容器内所有 {@link ToolExecutor} bean 聚合成
 * {@code name -> ToolExecutor} 查表,供 {@link LlmClient#callWithToolLoop} 在
 * 每轮循环里根据 LLM 返回的 {@code tool_calls[].name} 查到对应执行器。
 *
 * <p>约束:
 * <ul>
 *   <li>重名启动期 fail-fast:两个 {@link ToolExecutor} 报同一个 {@link ToolExecutor#name()}
 *       会抛 {@link IllegalStateException} 并把两个类的全限定名都打在异常里,
 *       方便排障。</li>
 *   <li>不存在的工具名在循环里通过 {@link #lookup(String)} 返回 {@code null},
 *       {@code runToolLoop} 把它包成 {@link ToolResult#error(String)} 回灌给 LLM,
 *       不阻断循环。</li>
 *   <li>注册表是只读快照({@link Map#copyOf}),运行时不可改 —— 避免并发
 *       读 + 写的不一致。Tool 的开关/限流走工具内部,不通过注册表。</li>
 * </ul>
 *
 * <p>本类用 {@code @Component} 而不是 {@code @Bean} 在 {@code AiAutoConfiguration}
 * 声明,因为 ctor 依赖 {@code List<ToolExecutor>} 可以让 Spring 自动收集所有
 * 实现,无需在 {@code AiAutoConfiguration} 里再写一遍遍历 —— 与
 * {@code aiStartupLogger} 的写法保持一致。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> executors;

    public ToolRegistry(List<ToolExecutor> beans) {
        Map<String, ToolExecutor> map = new LinkedHashMap<>();
        for (ToolExecutor e : beans) {
            ToolExecutor prev = map.put(e.name(), e);
            if (prev != null) {
                throw new IllegalStateException("Duplicate tool executor name '" + e.name()
                        + "': " + prev.getClass().getName()
                        + " vs " + e.getClass().getName());
            }
        }
        this.executors = Map.copyOf(map);
    }

    /**
     * 按名查找工具。找不到返回 {@code null} —— 调用方负责把它包成
     * {@link ToolResult#error(String)},不要在这里抛异常(避免阻断循环)。
     */
    public ToolExecutor lookup(String name) {
        return executors.get(name);
    }

    /** 当前已注册的工具名集合(不可变)。调试 / 启动日志 / 未来"工具清单 API"用。 */
    public Set<String> names() {
        return executors.keySet();
    }
}
