package com.nexusforge.bootstrap;

import com.nexusforge.config.AiProperties;
import com.nexusforge.model.ChatModel;
import com.nexusforge.router.ChatModelRouter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot 自动装配入口。spring.ai.enabled=false 时整体禁用 AI 模块。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "spring.ai.enabled", havingValue = "true", matchIfMissing = true)
public class AiAutoConfiguration {

    @Bean
    public ChatModelRouter chatModelRouter(List<ChatModel> models, AiProperties props) {
        // Spring 自动注入所有 ChatModel 实现;router 内按 name() 索引
        Map<String, ChatModel> map = new HashMap<>();
        for (ChatModel m : models) map.put(m.name(), m);
        return new ChatModelRouter(map, props);
    }

    /**
     * 注册 ChatModel 实现。OpenAiChatModel 已经是 @Component,这里不再 @Bean;
     * 若 provider disabled 则通过 @ConditionalOnProperty 屏蔽。
     */
}